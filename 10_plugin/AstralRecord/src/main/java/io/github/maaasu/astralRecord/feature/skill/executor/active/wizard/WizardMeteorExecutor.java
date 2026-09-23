package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 発動時に固定した照準地点へ、斜め上空から隕石を落とすウィザード魔法です。 */
public final class WizardMeteorExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "wizard_meteor";
    private static final int ANIMATION_STEP_TICKS = 2;
    private static final int ARRIVAL_HOLD_TICKS = 4;
    private static final int SIGIL_STEP_TICKS = 4;
    private static final double METEOR_HEIGHT = 18.0D;
    private static final int DISPLAY_COUNT = 7;

    /**
     * 共有発動スキルサービスで初期化します。
     *
     * @param services 対象判定、演出、遅延処理の共有サービス
     */
    public WizardMeteorExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "radius");
        requirePositive(params, "damageRatio");
        int delayTicks = params.getInt("impactDelayTicks", 0);
        if (delayTicks <= ARRIVAL_HOLD_TICKS || delayTicks % ANIMATION_STEP_TICKS != 0) {
            throw new SkillParameterException("impactDelayTicks", "メテオの着弾遅延は6tick以上の偶数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 16.0D);
        double radius = params.getDouble("radius", 5.0D);
        double damageRatio = params.getDouble("damageRatio", 6.05D);
        int delayTicks = params.getInt("impactDelayTicks", 60);
        int flightTicks = delayTicks - ARRIVAL_HOLD_TICKS;
        MeteorTarget target = impactTarget(context, range);
        Location impact = target.location();
        MeteorState state = new MeteorState(target);
        AstEntity attacker = context.attacker();
        UUID casterId = context.player().getUniqueId();
        String scope = "wizard-meteor:" + UUID.randomUUID();

        try {
            state.spawnDisplays(flightTicks);
            context.services().tasks().repeat(
                    casterId,
                    scope,
                    0L,
                    1L,
                    delayTicks + 1,
                    elapsedTicks -> {
                        if (elapsedTicks >= delayTicks) {
                            state.destroy();
                            detonate(context, attacker, impact, radius, damageRatio);
                            return;
                        }
                        if (elapsedTicks <= flightTicks && elapsedTicks % ANIMATION_STEP_TICKS == 0) {
                            state.updateDisplays(elapsedTicks, flightTicks);
                            if (elapsedTicks < flightTicks) {
                                state.drawTrail(context.services());
                            }
                        }
                        if (elapsedTicks % SIGIL_STEP_TICKS == 0) {
                            state.drawSigil(context.services());
                        }
                    },
                    state::destroy
            );
        } catch (RuntimeException exception) {
            state.destroy();
            throw exception;
        }
        return context.success();
    }

    /** 発動時の視線とBlock衝突面から、変更されない着弾地点と魔法陣の面法線を解決します。 */
    private static @NotNull MeteorTarget impactTarget(@NotNull PlayerActiveSkillContext context, double range) {
        Location eye = context.eyeLocation();
        Vector direction = context.direction();
        SkillTargetingService.BlockHit blockHit = context.services().targeting().blockHit(eye, direction, range);
        return blockHit == null
                ? new MeteorTarget(eye.add(direction.multiply(range)), new Vector(0.0D, 1.0D, 0.0D))
                : new MeteorTarget(
                        blockHit.location().add(blockHit.normal().multiply(0.12D)),
                        blockHit.normal()
                );
    }

    /** 隕石と魔法陣を消し、爆発演出と球形範囲への一撃を適用します。 */
    private static void detonate(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull Location impact,
            double radius,
            double damageRatio
    ) {
        Location burst = impact.clone().add(0.0D, 0.45D, 0.0D);
        context.services().effects().point(burst, SharedParticleDefinitions.WIZARD_METEOR_EXPLOSION);
        context.services().effects().point(burst, SharedParticleDefinitions.WIZARD_METEOR_FLAME_BURST);
        context.services().effects().point(burst, SharedParticleDefinitions.WIZARD_METEOR_SMOKE_BURST);
        context.services().effects().sound(impact, Sound.ENTITY_GENERIC_EXPLODE, 2.0F, 0.72F);
        context.services().targeting().inSphere(context.player(), impact, radius, Integer.MAX_VALUE, true)
                .forEach(target -> context.services().combat().hit(
                        attacker, target, AttackType.MAGIC, DamageElement.FIRE, damageRatio
                ));
    }

    /** スキルマスターの正数パラメーターを検証します。 */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "メテオの params[" + key + "] は正数が必要です");
        }
    }

    /** 発動地点と、魔法陣が向くBlock衝突面の外向き法線を保持します。 */
    private record MeteorTarget(@NotNull Location location, @NotNull Vector normal) {
    }

    /** 発動ごとの隕石表示と、着弾位置に固定された魔法陣を管理します。 */
    private static final class MeteorState {
        private static final Material[] DISPLAY_MATERIALS = {
                Material.MAGMA_BLOCK,
                Material.BLACKSTONE,
                Material.GILDED_BLACKSTONE,
                Material.MAGMA_BLOCK,
                Material.BLACKSTONE,
                Material.MAGMA_BLOCK,
                Material.GILDED_BLACKSTONE
        };

        private final Location impact;
        private final Vector planeNormal;
        private final Vector planeX;
        private final Vector planeZ;
        private final Vector sourceOffset;
        private final List<Location> sigilRings;
        private final List<Location> sigilRunes;
        private final List<BlockDisplay> displays = new ArrayList<>(DISPLAY_COUNT);
        private Location meteorCenter;

        /** ランダムな方位と鉛直角を固定し、命中面に沿う魔法陣の粒子座標を準備します。 */
        private MeteorState(@NotNull MeteorTarget target) {
            this.impact = target.location().clone();
            this.planeNormal = target.normal().clone().normalize();
            Vector reference = Math.abs(planeNormal.getY()) > 0.9D
                    ? new Vector(1.0D, 0.0D, 0.0D)
                    : new Vector(0.0D, 1.0D, 0.0D);
            this.planeX = reference.crossProduct(planeNormal).normalize();
            this.planeZ = planeNormal.clone().crossProduct(planeX).normalize();
            double azimuth = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0D);
            double tilt = Math.toRadians(ThreadLocalRandom.current().nextDouble(25.0D, 40.0D));
            double horizontalDistance = METEOR_HEIGHT * Math.tan(tilt);
            this.sourceOffset = new Vector(
                    Math.cos(azimuth) * horizontalDistance,
                    METEOR_HEIGHT,
                    Math.sin(azimuth) * horizontalDistance
            );
            this.meteorCenter = this.impact.clone().add(sourceOffset);
            this.sigilRings = new ArrayList<>(96);
            this.sigilRunes = new ArrayList<>(72);
            addRing(sigilRings, 4.6D, 48);
            addRing(sigilRings, 3.65D, 36);
            addRing(sigilRings, 1.3D, 20);
            addStar(sigilRunes);
        }

        /** 一時的な BlockDisplay の核と破片を生成します。 */
        private void spawnDisplays(int flightTicks) {
            World world = impact.getWorld();
            if (world == null) {
                return;
            }
            for (Material material : DISPLAY_MATERIALS) {
                BlockDisplay display = world.spawn(meteorCenter, BlockDisplay.class, entity -> {
                    entity.setBlock(material.createBlockData());
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);
                    entity.setTeleportDuration(ANIMATION_STEP_TICKS);
                    entity.setInterpolationDuration(ANIMATION_STEP_TICKS);
                });
                displays.add(display);
            }
            updateDisplays(0, flightTicks);
        }

        /** 経過時間に応じて斜め軌道上の核と破片を補間移動します。 */
        private void updateDisplays(int elapsedTicks, int flightTicks) {
            double fraction = Math.clamp((double) elapsedTicks / flightTicks, 0.0D, 1.0D);
            double progress = Math.pow(fraction, 1.45D);
            meteorCenter = impact.clone().add(sourceOffset.clone().multiply(1.0D - progress));
            for (int index = 0; index < displays.size(); index++) {
                BlockDisplay display = displays.get(index);
                if (!display.isValid()) {
                    continue;
                }
                double orbit = index * Math.PI * 0.72D + elapsedTicks * 0.11D;
                double distance = index == 0 ? 0.0D : 0.58D + (index % 3) * 0.22D;
                Location location = meteorCenter.clone().add(
                        Math.cos(orbit) * distance,
                        index == 0 ? 0.0D : ((index % 3) - 1) * 0.44D,
                        Math.sin(orbit) * distance
                );
                display.teleport(location);
                float scale = index == 0 ? 1.3F : 0.35F + (index % 3) * 0.10F;
                display.setTransformation(new Transformation(
                        new Vector3f(-scale / 2.0F, -scale / 2.0F, -scale / 2.0F),
                        new Quaternionf().rotateXYZ(
                                (float) (elapsedTicks * 0.07D + index),
                                (float) (elapsedTicks * 0.09D + orbit),
                                (float) (elapsedTicks * 0.05D + index)
                        ),
                        new Vector3f(scale, scale, scale),
                        new Quaternionf()
                ));
            }
        }

        /** 着弾地点に固定した赤橙の同心円と五芒星を表示します。 */
        private void drawSigil(@NotNull ActiveSkillServices services) {
            services.effects().points(impact, sigilRings, SharedParticleDefinitions.WIZARD_METEOR_SIGIL_RING);
            services.effects().points(impact, sigilRunes, SharedParticleDefinitions.WIZARD_METEOR_SIGIL_RUNE);
        }

        /** 隕石の後方へ炎と煙を表示します。 */
        private void drawTrail(@NotNull ActiveSkillServices services) {
            Vector trailingDirection = sourceOffset.clone().normalize().multiply(0.8D);
            services.effects().point(meteorCenter, SharedParticleDefinitions.WIZARD_METEOR_TRAIL_FLAME);
            services.effects().point(
                    meteorCenter.clone().add(trailingDirection),
                    SharedParticleDefinitions.WIZARD_METEOR_TRAIL_SMOKE
            );
        }

        /** 表示エンティティを中断時にも確実に削除します。 */
        private void destroy() {
            displays.stream().filter(Entity::isValid).forEach(Entity::remove);
            displays.clear();
        }

        /** 指定半径の魔法陣外周を均等な粒子点として追加します。 */
        private void addRing(@NotNull List<Location> points, double radius, int count) {
            for (int index = 0; index < count; index++) {
                double angle = Math.PI * 2.0D * index / count;
                points.add(sigilPoint(Math.cos(angle) * radius, Math.sin(angle) * radius));
            }
        }

        /** 魔法陣中央の五芒星を線分群として追加します。 */
        private void addStar(@NotNull List<Location> points) {
            for (int edge = 0; edge < 5; edge++) {
                double fromAngle = -Math.PI / 2.0D + Math.PI * 2.0D * edge / 5.0D;
                double toAngle = -Math.PI / 2.0D + Math.PI * 2.0D * ((edge + 2) % 5) / 5.0D;
                for (int step = 0; step <= 10; step++) {
                    double progress = step / 10.0D;
                    points.add(sigilPoint(
                            ((1.0D - progress) * Math.cos(fromAngle) + progress * Math.cos(toAngle)) * 2.65D,
                            ((1.0D - progress) * Math.sin(fromAngle) + progress * Math.sin(toAngle)) * 2.65D
                    ));
                }
            }
        }

        /** 衝突面に沿う局所座標を実際の粒子位置へ変換します。 */
        private @NotNull Location sigilPoint(double x, double z) {
            return impact.clone()
                    .add(planeNormal.clone().multiply(0.04D))
                    .add(planeX.clone().multiply(x))
                    .add(planeZ.clone().multiply(z));
        }
    }
}
