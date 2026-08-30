package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
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
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** 正面へ純粋な魔力の波を放つ、冒険者の前方範囲魔法です。 */
public final class AdventurerManaBurstExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "adventurer_mana_burst";
    private static final double MAX_CONE_ANGLE = 180.0D;
    private static final double EMISSION_FORWARD_OFFSET = 0.35D;
    private static final double EMISSION_VERTICAL_OFFSET = -0.25D;
    private static final int WAVE_POINTS = 12;

    /** 共有発動スキルサービスで初期化します。 */
    public AdventurerManaBurstExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "damageRatio");
        double angle = params.getDouble("angle", 0.0D);
        if (!(angle > 0.0D && angle <= MAX_CONE_ANGLE)) {
            throw new SkillParameterException(
                    "angle",
                    "マナバーストの params[angle] は0より大きく180以下の全角が必要です"
            );
        }
        if (params.getInt("maxTargets", 0) < 1) {
            throw new SkillParameterException("maxTargets", "マナバーストの params[maxTargets] は1以上の整数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Player player = context.player();
        Location eyeLocation = context.eyeLocation();
        Vector direction = context.direction();
        Location origin = eyeLocation.clone()
                .add(direction.clone().multiply(EMISSION_FORWARD_OFFSET))
                .add(0.0D, EMISSION_VERTICAL_OFFSET, 0.0D);
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 7.0D);
        double angle = params.getDouble("angle", 60.0D);
        double damageRatio = params.getDouble("damageRatio", 1.32D);
        int maxTargets = params.getInt("maxTargets", 6);

        Location visibleEnd = context.services().targeting().clippedEnd(eyeLocation, direction, range);
        double visibleRange = Math.max(0.5D, origin.distance(visibleEnd));
        renderWave(context, origin, direction, visibleRange, angle);

        context.services().targeting()
                .inCone(player, range, angle, maxTargets, true)
                .forEach(target -> {
                    context.services().combat().hit(
                            context.attacker(), target, AttackType.MAGIC, DamageElement.NONE, damageRatio
                    );
                    Location impact = target.location().clone().add(0.0D, 0.9D, 0.0D);
                    context.services().effects().point(impact, SharedParticleDefinitions.MAGIC_IMPACT_ENCHANT);
                    context.services().effects().point(impact, SharedParticleDefinitions.MAGIC_IMPACT_DUST);
                });

        context.services().effects().sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 1.35F);
        return context.success();
    }

    private void renderWave(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Location origin,
            @NotNull Vector direction,
            double visibleRange,
            double angle
    ) {
        double previousRadius = 0.0D;
        for (double radius : new double[]{
                Math.min(1.8D, visibleRange),
                Math.min(3.6D, visibleRange),
                visibleRange
        }) {
            if (radius <= previousRadius + 1.0E-6D) {
                continue;
            }
            context.services().effects().viewArcSegment(
                    origin,
                    direction,
                    radius,
                    -angle / 2.0D,
                    angle / 2.0D,
                    WAVE_POINTS,
                    SharedParticleDefinitions.MAGIC_PROJECTILE_CORE_DUST
            );
            previousRadius = radius;
        }
        context.services().effects().viewArcSegment(
                origin,
                direction,
                visibleRange,
                -angle / 2.0D,
                angle / 2.0D,
                WAVE_POINTS,
                SharedParticleDefinitions.MAGIC_IMPACT_ENCHANT
        );
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "マナバーストの params[" + key + "] は正数が必要です");
        }
    }
}
