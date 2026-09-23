package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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
        double burningChance = params.getDouble("burningChance", -1.0D);
        if (!(burningChance >= 0.0D && burningChance <= 100.0D)) {
            throw new SkillParameterException("burningChance", "メテオの燃焼確率は0～100%が必要です");
        }
        if (params.getInt("burningDurationTicks", 0) < 1) {
            throw new SkillParameterException("burningDurationTicks", "メテオの燃焼時間は1tick以上が必要です");
        }
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
        MeteorTarget target = impactTarget(context, range);
        ActiveSkillCondition burning = new ActiveSkillCondition(
                ConditionType.BURNING,
                params.getDouble("burningChance", 25.0D),
                params.getInt("burningDurationTicks", 100),
                1.0D
        );
        summon(context.services(), context.player(), context.attacker(), target, radius, damageRatio, delayTicks, 1.0D, burning);
        return context.success();
    }

    /**
     * 炎上地点へ小型メテオを発生させます。
     *
     * @param services 共有戦闘・演出サービス
     * @param caster 発動者
     * @param attacker 発動時点の攻撃者
     * @param impact 固定した着弾地点
     */
    public static void summonBurnStrike(
            @NotNull ActiveSkillServices services,
            @NotNull AstPlayer caster,
            @NotNull AstEntity attacker,
            @NotNull Location impact
    ) {
        summon(services, caster.getBukkit(), attacker,
                new MeteorTarget(impact.clone()),
                2.5D, 1.5D, 20, 0.75D);
    }

    /**
     * 着弾地点と演出倍率を固定してメテオの時限処理を開始します。
     *
     * @param services 戦闘、演出、遅延処理の共有サービス
     * @param caster 発動者
     * @param attacker 発動時点の攻撃者
     * @param target 固定した着弾地点
     * @param radius 爆発範囲
     * @param damageRatio 魔法攻撃倍率
     * @param delayTicks 着弾までの時間
     * @param visualScale 演出倍率
     * @param conditions 命中時の状態異常。空なら状態異常を付与しない
     */
    private static void summon(
            @NotNull ActiveSkillServices services,
            @NotNull Player caster,
            @NotNull AstEntity attacker,
            @NotNull MeteorTarget target,
            double radius,
            double damageRatio,
            int delayTicks,
            double visualScale,
            @NotNull ActiveSkillCondition... conditions
    ) {
        int flightTicks = delayTicks - ARRIVAL_HOLD_TICKS;
        Location impact = target.location();
        MeteorState state = new MeteorState(target, visualScale);
        UUID casterId = caster.getUniqueId();
        String scope = "wizard-meteor:" + UUID.randomUUID();

        try {
            state.spawnDisplays(flightTicks);
            services.tasks().repeat(
                    casterId,
                    scope,
                    0L,
                    1L,
                    delayTicks + 1,
                    elapsedTicks -> {
                        if (elapsedTicks >= delayTicks) {
                            state.destroy();
                            detonate(services, caster, attacker, impact, radius, damageRatio, visualScale, conditions);
                            return;
                        }
                        if (elapsedTicks <= flightTicks && elapsedTicks % ANIMATION_STEP_TICKS == 0) {
                            state.updateDisplays(elapsedTicks, flightTicks);
                            if (elapsedTicks < flightTicks) {
                                state.drawTrail(services);
                            }
                        }
                        if (elapsedTicks % SIGIL_STEP_TICKS == 0) {
                            state.drawSigil(services);
                        }
                    },
                    state::destroy
            );
        } catch (RuntimeException exception) {
            state.destroy();
            throw exception;
        }
    }

    /**
     * 発動時の視線とBlock衝突面から、変更されない着弾地点を解決します。
     *
     * @param context 発動者の視線と地形判定
     * @param range 最大射程
     * @return 発動時に固定した着弾地点
     */
    private static @NotNull MeteorTarget impactTarget(@NotNull PlayerActiveSkillContext context, double range) {
        Location eye = context.eyeLocation();
        Vector direction = context.direction();
        SkillTargetingService.BlockHit blockHit = context.services().targeting().blockHit(eye, direction, range);
        return blockHit == null
                ? new MeteorTarget(eye.add(direction.multiply(range)))
                : new MeteorTarget(blockHit.location().add(blockHit.normal().multiply(0.12D)));
    }

    /**
     * 隕石と魔法陣を消し、爆発演出と球形範囲への一撃を適用します。
     *
     * @param services 戦闘、対象判定、演出の共有サービス
     * @param caster 発動者
     * @param attacker 発動時点の攻撃者
     * @param impact 固定した着弾地点
     * @param radius 爆発範囲
     * @param damageRatio 魔法攻撃倍率
     * @param visualScale 演出倍率
     * @param conditions 命中時の状態異常。空なら状態異常を付与しない
     */
    private static void detonate(
            @NotNull ActiveSkillServices services,
            @NotNull Player caster,
            @NotNull AstEntity attacker,
            @NotNull Location impact,
            double radius,
            double damageRatio,
            double visualScale,
            @NotNull ActiveSkillCondition... conditions
    ) {
        Location burst = impact.clone().add(0.0D, 0.45D, 0.0D);
        services.effects().point(burst, scaledParticle(SharedParticleDefinitions.WIZARD_METEOR_EXPLOSION, visualScale));
        services.effects().point(burst, scaledParticle(SharedParticleDefinitions.WIZARD_METEOR_FLAME_BURST, visualScale));
        services.effects().point(burst, scaledParticle(SharedParticleDefinitions.WIZARD_METEOR_SMOKE_BURST, visualScale));
        services.effects().sound(impact, Sound.ENTITY_GENERIC_EXPLODE, (float) (2.0D * visualScale), 0.72F);
        services.targeting().inSphere(caster, impact, radius, Integer.MAX_VALUE, true)
                .forEach(target -> services.combat().hit(
                        attacker, target, AttackType.MAGIC, DamageElement.FIRE, damageRatio, conditions
                ));
    }

    /** 既存パーティクル定義の拡散範囲と密度をメテオの表示倍率へ揃えます。 */
    private static @NotNull SharedParticleDefinition scaledParticle(
            @NotNull SharedParticleDefinition definition,
            double visualScale
    ) {
        if (visualScale == 1.0D) {
            return definition;
        }
        return definition.withOffsets(
                definition.offsetX() * visualScale,
                definition.offsetY() * visualScale,
                definition.offsetZ() * visualScale
        ).withCount(Math.max(1, (int) Math.round(definition.count() * visualScale * visualScale)));
    }

    /** スキルマスターの正数パラメーターを検証します。 */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "メテオの params[" + key + "] は正数が必要です");
        }
    }

    /** 発動時に固定した着弾地点を保持します。 */
    private record MeteorTarget(@NotNull Location location) {
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
        private final Vector sourceOffset;
        private final double visualScale;
        private final List<Location> sigilRings;
        private final List<Location> sigilRunes;
        private final List<BlockDisplay> displays = new ArrayList<>(DISPLAY_COUNT);
        private Location meteorCenter;

        /** ランダムな方位と鉛直角を固定し、水平な魔法陣の粒子座標を準備します。 */
        private MeteorState(@NotNull MeteorTarget target, double visualScale) {
            this.impact = target.location().clone();
            this.visualScale = visualScale;
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
            addRing(sigilRings, 4.6D * visualScale, 48);
            addRing(sigilRings, 3.65D * visualScale, 36);
            addRing(sigilRings, 1.3D * visualScale, 20);
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
                double distance = index == 0 ? 0.0D : (0.58D + (index % 3) * 0.22D) * visualScale;
                Location location = meteorCenter.clone().add(
                        Math.cos(orbit) * distance,
                        index == 0 ? 0.0D : ((index % 3) - 1) * 0.44D * visualScale,
                        Math.sin(orbit) * distance
                );
                display.teleport(location);
                float scale = (float) ((index == 0 ? 1.3F : 0.35F + (index % 3) * 0.10F) * visualScale);
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
            Vector trailingDirection = sourceOffset.clone().normalize().multiply(0.8D * visualScale);
            services.effects().point(meteorCenter,
                    scaledParticle(SharedParticleDefinitions.WIZARD_METEOR_TRAIL_FLAME, visualScale));
            services.effects().point(
                    meteorCenter.clone().add(trailingDirection),
                    scaledParticle(SharedParticleDefinitions.WIZARD_METEOR_TRAIL_SMOKE, visualScale)
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
                            ((1.0D - progress) * Math.cos(fromAngle) + progress * Math.cos(toAngle)) * 2.65D * visualScale,
                            ((1.0D - progress) * Math.sin(fromAngle) + progress * Math.sin(toAngle)) * 2.65D * visualScale
                    ));
                }
            }
        }

        /**
         * 水平な局所座標を実際の粒子位置へ変換します。
         *
         * @param x 着弾地点から東西方向の距離
         * @param z 着弾地点から南北方向の距離
         * @return 着弾地点より0.04m高い水平面の粒子位置
         */
        private @NotNull Location sigilPoint(double x, double z) {
            return impact.clone().add(x, 0.04D, z);
        }
    }
}
