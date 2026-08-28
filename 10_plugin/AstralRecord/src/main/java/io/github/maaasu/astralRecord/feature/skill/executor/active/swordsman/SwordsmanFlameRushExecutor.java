package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 炎をまとった横薙ぎと縦斬りを続けて放つ、ソードマンの範囲近接スキルです。 */
public final class SwordsmanFlameRushExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_flame_rush";
    private static final String HORIZONTAL_SCOPE = ID + ":horizontal";
    private static final double DEFAULT_RANGE = 6.0D;
    private static final double DEFAULT_TARGET_ANGLE = 60.0D;
    private static final int DEFAULT_MAX_TARGETS = 5;
    private static final List<Double> DEFAULT_DAMAGE_RATIOS = List.of(0.65D, 0.75D);
    private static final int DEFAULT_SECOND_HIT_DELAY_TICKS = 4;
    private static final double DEFAULT_BURNING_CHANCE = 0.0D;
    private static final long DEFAULT_BURNING_DURATION_TICKS = 100L;
    private static final double[] HORIZONTAL_SWEEP_RADIUS_BASES = {1.6D, 2.7D, 3.8D};
    private static final double HORIZONTAL_SWEEP_START_ANGLE = 55.0D;
    private static final double HORIZONTAL_SWEEP_END_ANGLE = -55.0D;
    private static final int HORIZONTAL_SWEEP_FRAMES = 6;

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanFlameRushExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        double targetAngle = params.getDouble("targetAngle", 0.0D);
        if (!(targetAngle > 0.0D && targetAngle <= 180.0D)) {
            throw new SkillParameterException("targetAngle", "フレイムラッシュの対象角度は0より大きく180以下が必要です");
        }
        if (params.getInt("maxTargets", 0) < 1) {
            throw new SkillParameterException("maxTargets", "フレイムラッシュの最大対象数は1以上が必要です");
        }
        List<Double> damageRatios = params.getDoubleList("damageRatios", List.of());
        if (damageRatios.size() != 2 || damageRatios.stream().anyMatch(value -> !(value > 0.0D))) {
            throw new SkillParameterException("damageRatios", "フレイムラッシュの倍率は正数2件の配列が必要です");
        }
        if (params.getInt("secondHitDelayTicks", -1) < 0) {
            throw new SkillParameterException("secondHitDelayTicks", "フレイムラッシュの2撃目遅延は0以上が必要です");
        }
        double burningChance = params.getDouble("burningChance", 0.0D);
        if (!(burningChance >= 0.0D && burningChance <= 100.0D)) {
            throw new SkillParameterException("burningChance", "炎上付与確率は0以上100以下が必要です");
        }
        if (params.getInt("burningDurationTicks", 0) < 1) {
            throw new SkillParameterException("burningDurationTicks", "炎上時間は1 tick以上が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", DEFAULT_RANGE);
        double targetAngle = params.getDouble("targetAngle", DEFAULT_TARGET_ANGLE);
        int maxTargets = params.getInt("maxTargets", DEFAULT_MAX_TARGETS);
        List<Double> damageRatios = params.getDoubleList("damageRatios", DEFAULT_DAMAGE_RATIOS);
        int secondHitDelayTicks = params.getInt("secondHitDelayTicks", DEFAULT_SECOND_HIT_DELAY_TICKS);
        ActiveSkillCondition burning = burningCondition(context, params);
        Player player = context.player();
        World castWorld = player.getWorld();
        Location origin = context.eyeLocation().add(context.direction().multiply(0.4D)).subtract(0.0D, 0.3D, 0.0D);
        List<AstEntity> targets = context.services().targeting()
                .inCone(player, range, targetAngle, maxTargets, true);

        renderHorizontalSlash(context, player, castWorld, origin, range);
        AstEntity attacker = context.attacker();
        for (AstEntity target : targets) {
            // 初撃は対象位置を保って縦斬りへつなぐため、ノックバックを適用しません。
            context.services().combat().hit(
                    attacker,
                    target,
                    AttackType.MELEE,
                    DamageElement.FIRE,
                    damageRatios.get(0)
            );
        }

        context.services().tasks().later(player.getUniqueId(), ID + ":vertical", secondHitDelayTicks, () -> {
            if (!player.isOnline() || player.getWorld() != castWorld) {
                return;
            }
            for (AstEntity target : targets) {
                Location impact = target.location().clone().add(0.0D, 1.0D, 0.0D);
                renderVerticalSlash(context, impact);
                if (burning == null) {
                    context.services().combat().hit(
                            attacker,
                            target,
                            AttackType.MELEE,
                            DamageElement.FIRE,
                            damageRatios.get(1)
                    );
                } else {
                    context.services().combat().hit(
                            attacker,
                            target,
                            AttackType.MELEE,
                            DamageElement.FIRE,
                            damageRatios.get(1),
                            burning
                    );
                }
            }
        });
        return context.success();
    }

    private @Nullable ActiveSkillCondition burningCondition(
            @NotNull PlayerActiveSkillContext context,
            @NotNull SkillParamReader params
    ) {
        double chance = params.getDouble("burningChance", DEFAULT_BURNING_CHANCE);
        if (chance <= 0.0D) {
            return null;
        }
        return new ActiveSkillCondition(
                ConditionType.BURNING,
                chance,
                params.getInt("burningDurationTicks", (int) DEFAULT_BURNING_DURATION_TICKS),
                1.0D
        );
    }

    private void renderHorizontalSlash(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player player,
            @NotNull World castWorld,
            @NotNull Location origin,
            double range
    ) {
        double[] sweepRadii = scaledSweepRadii(range);
        var direction = context.direction();
        context.services().tasks().repeat(
                player.getUniqueId(),
                HORIZONTAL_SCOPE,
                0L,
                1L,
                HORIZONTAL_SWEEP_FRAMES,
                frame -> {
                    if (!player.isOnline() || player.getWorld() != castWorld) {
                        context.services().tasks().cancel(player.getUniqueId(), HORIZONTAL_SCOPE);
                        return;
                    }
                    double headStart = HORIZONTAL_SWEEP_START_ANGLE
                            + (HORIZONTAL_SWEEP_END_ANGLE - HORIZONTAL_SWEEP_START_ANGLE) * frame / HORIZONTAL_SWEEP_FRAMES;
                    double headEnd = HORIZONTAL_SWEEP_START_ANGLE
                            + (HORIZONTAL_SWEEP_END_ANGLE - HORIZONTAL_SWEEP_START_ANGLE) * (frame + 1) / HORIZONTAL_SWEEP_FRAMES;
                    for (double radius : sweepRadii) {
                        context.services().effects().viewArcSegment(
                                origin,
                                direction,
                                radius,
                                headStart,
                                headEnd,
                                8,
                                SharedParticleDefinitions.SWORDSMAN_FLAME_RUSH_HORIZONTAL_DUST
                        );
                        context.services().effects().viewArcSegment(
                                origin,
                                direction,
                                radius,
                                headStart,
                                headEnd,
                                6,
                                SharedParticleDefinitions.SWORDSMAN_FLAME_RUSH_HORIZONTAL_FLAME
                        );
                    }
                }
        );
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.1F, 1.2F);
    }

    private void renderVerticalSlash(@NotNull PlayerActiveSkillContext context, @NotNull Location impact) {
        context.services().effects().line(
                impact.clone().subtract(0.0D, 1.2D, 0.0D),
                impact.clone().add(0.0D, 1.8D, 0.0D),
                0.12D,
                SharedParticleDefinitions.SWORDSMAN_FLAME_RUSH_VERTICAL_DUST
        );
        context.services().effects().line(
                impact.clone().subtract(0.0D, 1.2D, 0.0D),
                impact.clone().add(0.0D, 1.8D, 0.0D),
                0.12D,
                SharedParticleDefinitions.SWORDSMAN_FLAME_RUSH_VERTICAL_FLAME
        );
        context.services().effects().point(impact, SharedParticleDefinitions.SWORDSMAN_FLAME_RUSH_VERTICAL_FLAME);
        context.services().effects().point(impact, SharedParticleDefinitions.SKILL_MAGE_FIRE);
        context.services().effects().sound(impact, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.0F, 1.35F);
    }

    private static double[] scaledSweepRadii(double range) {
        double scale = Math.min(range, DEFAULT_RANGE) / DEFAULT_RANGE;
        double[] radii = new double[HORIZONTAL_SWEEP_RADIUS_BASES.length];
        for (int i = 0; i < HORIZONTAL_SWEEP_RADIUS_BASES.length; i++) {
            radii[i] = HORIZONTAL_SWEEP_RADIUS_BASES[i] * scale;
        }
        return radii;
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "フレイムラッシュの params[" + key + "] は正数が必要です");
        }
    }
}
