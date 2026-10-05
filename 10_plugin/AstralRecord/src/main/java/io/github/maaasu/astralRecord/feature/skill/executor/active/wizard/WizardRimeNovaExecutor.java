package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 発動地点から氷の波を広げ、追う敵を凍結させて元素連携の距離を確保します。 */
public final class WizardRimeNovaExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_rime_nova";
    private static final int STEP_TICKS = 2;
    private static final int WAVE_FRAMES = 5;
    private static final int TOTAL_FRAMES = 9;
    private static final int CRYSTAL_COUNT = 6;

    /** @param services 共通の対象選択・戦闘・演出サービス */
    public WizardRimeNovaExecutor(@NotNull ActiveSkillServices services) {
        // 半径の上限だけは近接範囲魔法の共通上限から独立させます。
        super(ID, services, "maxTargets", "damageRatio", "burningMultiplier",
                "conditionChance", "conditionTicks", "frozenTicks");
    }

    /**
     * 共通の効果値に加え、ライムノヴァ専用の半径と短時間凍結を検証します。
     * @param skill 検証対象のスキル定義
     * @throws SkillParameterException 半径・時間・効果値が有限の許容範囲外の場合
     */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        double radius = params.getDouble("radius", Double.NaN);
        if (!Double.isFinite(radius) || radius <= 0.0D || radius > 10.0D) {
            throw new SkillParameterException("radius", "ライムノヴァの半径は0mより大きく10m以下が必要です");
        }
        if (params.getDouble("frozenTicks", Double.NaN) > 60.0D) {
            throw new SkillParameterException("frozenTicks", "ライムノヴァの凍結時間は60tick以下が必要です");
        }
    }

    /**
     * 発動地点を固定し、MP消費後の次tickから有限の氷波と氷柱を開始します。
     * @param context レベル・シジル解決済みの発動コンテキスト
     * @return 共通の消費とクールダウン開始を行う成功結果
     */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location center = context.player().getLocation().add(0.0D, 0.6D, 0.0D);
        NovaState state = new NovaState(center, value(context, "radius"));
        String scope = ID + ":" + UUID.randomUUID();
        state.drawSigil(context.services());
        context.services().tasks().repeat(context.player().getUniqueId(), scope, 1L, STEP_TICKS,
                TOTAL_FRAMES, frame -> {
                    if (!context.player().isOnline() || context.player().isDead()
                            || context.player().getWorld() != center.getWorld()
                            || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) {
                        context.services().tasks().cancel(context.player().getUniqueId(), scope);
                        return;
                    }
                    if (frame == 0) {
                        state.spawnCrystals();
                        context.services().effects().sound(center, Sound.BLOCK_GLASS_BREAK, 1.2F, 0.65F);
                        context.services().effects().point(center, SharedParticleDefinitions.WIZARD_RIME_BURST);
                    }
                    if (frame < WAVE_FRAMES) {
                        double waveRadius = state.radius * (frame + 1.0D) / WAVE_FRAMES;
                        state.drawWave(context.services(), waveRadius, frame);
                        strikeWave(context, state, waveRadius);
                    }
                    state.updateCrystals(context.services(), frame);
                    if (frame == WAVE_FRAMES - 1) {
                        context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 1.65F);
                    }
                }, state::destroy);
        return context.success();
    }

    /** 波が届いた敵へ一発動一回の氷攻撃を行い、有効命中だけに凍結と冷気を付与します。 */
    private static void strikeWave(PlayerActiveSkillContext context, NovaState state, double waveRadius) {
        int maxTargets = (int) value(context, "maxTargets");
        if (state.struck.size() >= maxTargets) return;
        // 既に攻撃した対象が近くに残っていても、未攻撃の対象を最大数まで取得します。
        var targets = context.services().targeting().inSphere(context.player(), state.center, waveRadius,
                maxTargets + state.struck.size(), true);
        var frozen = new ActiveSkillCondition(ConditionType.FROZEN, value(context, "conditionChance"),
                (int) value(context, "frozenTicks"), 1.0D);
        for (var target : targets) {
            if (state.struck.size() >= maxTargets) break;
            if (!state.struck.add(target.id())) continue;
            boolean burning = context.services().combat().hasCondition(target, ConditionType.BURNING);
            double ratio = value(context, "damageRatio") * (burning ? value(context, "burningMultiplier") : 1.0D);
            var result = hit(context, target, DamageElement.ICE, ratio, frozen,
                    condition(context, ConditionType.CHILLED));
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                Location impact = target.location().clone().add(0.0D, 0.7D, 0.0D);
                context.services().effects().point(impact, SharedParticleDefinitions.WIZARD_RIME_HIT);
                if (burning) {
                    context.services().effects().point(impact, SharedParticleDefinitions.WIZARD_RIME_STEAM);
                    context.services().effects().sound(impact, Sound.BLOCK_FIRE_EXTINGUISH, 0.45F, 1.6F);
                }
            }
        }
    }

    /** 一発動の位置・対象履歴・氷柱を保持し、中断を含む終了時に氷柱を破棄します。 */
    private static final class NovaState {
        private final Location center;
        private final double radius;
        private final Set<UUID> struck = new HashSet<>();
        private final List<BlockDisplay> crystals = new ArrayList<>(CRYSTAL_COUNT);

        /** 発動位置と最大半径を複製して固定します。 */
        private NovaState(Location center, double radius) {
            this.center = center.clone();
            this.radius = radius;
        }

        /** 六本の枝と小枝を持つ雪の結晶を発動地点の足元へ一度描画します。 */
        private void drawSigil(ActiveSkillServices services) {
            List<Location> points = new ArrayList<>(144);
            for (int ray = 0; ray < CRYSTAL_COUNT; ray++) {
                double angle = ray * Math.PI / 3.0D;
                for (int step = 1; step <= 8; step++) {
                    double distance = step * 0.22D;
                    points.add(point(angle, distance, -0.52D));
                    if (step == 4 || step == 6) {
                        Location branch = point(angle, distance, -0.52D);
                        for (int side : new int[]{-1, 1}) {
                            for (int twig = 1; twig <= 4; twig++) {
                                double branchAngle = angle + side * Math.PI / 3.0D;
                                points.add(branch.clone().add(Math.cos(branchAngle) * twig * 0.14D,
                                        0.0D, Math.sin(branchAngle) * twig * 0.14D));
                            }
                        }
                    }
                }
            }
            services.effects().points(center, points, SharedParticleDefinitions.WIZARD_RIME_SIGIL);
            services.effects().ring(center.clone().add(0.0D, -0.52D, 0.0D), 2.0D, 36,
                    SharedParticleDefinitions.WIZARD_RIME_WAVE);
        }

        /** 攻撃半径の前縁を青白い二層の波と上へ砕ける雪片で描画します。 */
        private void drawWave(ActiveSkillServices services, double waveRadius, int frame) {
            services.effects().ring(center.clone().add(0.0D, -0.45D, 0.0D), waveRadius, 64,
                    SharedParticleDefinitions.WIZARD_RIME_WAVE);
            services.effects().ring(center.clone().add(0.0D, 0.2D, 0.0D), waveRadius, 48,
                    SharedParticleDefinitions.WIZARD_RIME_SIGIL);
            List<Location> flakes = new ArrayList<>(24);
            for (int index = 0; index < 24; index++) {
                flakes.add(point(Math.PI * 2.0D * index / 24.0D + frame * 0.08D, waveRadius, 0.15D));
            }
            services.effects().points(center, flakes, SharedParticleDefinitions.WIZARD_RIME_SNOW);
        }

        /** 六方向へ非永続の氷柱を生成します。部分生成の例外もrepeatのcleanupが回収します。 */
        private void spawnCrystals() {
            var world = center.getWorld();
            if (world == null) return;
            for (int index = 0; index < CRYSTAL_COUNT; index++) {
                Material material = index % 2 == 0 ? Material.PACKED_ICE : Material.BLUE_ICE;
                BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
                    entity.setBlock(material.createBlockData());
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);
                    entity.setTeleportDuration(STEP_TICKS);
                    entity.setInterpolationDuration(STEP_TICKS);
                });
                crystals.add(display);
            }
        }

        /** 波の前縁へ氷柱を移動・伸長し、到達後は縮小して最後に砕きます。 */
        private void updateCrystals(ActiveSkillServices services, int frame) {
            double progress = Math.min(1.0D, (frame + 1.0D) / WAVE_FRAMES);
            double fade = frame < WAVE_FRAMES ? 1.0D : (TOTAL_FRAMES - frame) / (double) (TOTAL_FRAMES - WAVE_FRAMES + 1);
            for (int index = 0; index < crystals.size(); index++) {
                BlockDisplay display = crystals.get(index);
                if (!display.isValid()) continue;
                double angle = index * Math.PI / 3.0D;
                Location location = point(angle, radius * progress, -0.52D);
                display.teleport(location);
                float width = (float) (0.38D * fade);
                float height = (float) ((0.9D + progress * 0.9D) * fade);
                display.setTransformation(new Transformation(new Vector3f(-width / 2.0F, 0.0F, -width / 2.0F),
                        new Quaternionf().rotateY((float) angle).rotateZ(0.18F),
                        new Vector3f(width, height, width), new Quaternionf()));
                if (frame == TOTAL_FRAMES - 1) {
                    services.effects().point(location.clone().add(0.0D, 0.5D, 0.0D),
                            SharedParticleDefinitions.WIZARD_RIME_SHATTER);
                }
            }
        }

        /** 固定中心から水平な極座標と高さを実世界の位置へ変換します。 */
        private Location point(double angle, double distance, double height) {
            return center.clone().add(Math.cos(angle) * distance, height, Math.sin(angle) * distance);
        }

        /** 正常終了・死亡・退出・世界移動・例外のいずれでも氷柱を削除します。 */
        private void destroy() {
            crystals.stream().filter(Entity::isValid).forEach(Entity::remove);
            crystals.clear();
        }
    }
}
