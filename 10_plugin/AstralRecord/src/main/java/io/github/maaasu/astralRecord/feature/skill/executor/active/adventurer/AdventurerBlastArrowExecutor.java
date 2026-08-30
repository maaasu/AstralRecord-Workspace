package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
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
import org.jetbrains.annotations.NotNull;

/** 着弾した地点から衝撃波を広げる冒険者の範囲射撃です。 */
public final class AdventurerBlastArrowExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "adventurer_blast_arrow";

    /** 共有発動スキルサービスで初期化します。 */
    public AdventurerBlastArrowExecutor(@NotNull ActiveSkillServices services) {
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
        requirePositive(params, "projectileSpeed");
        requirePositive(params, "projectileHitRadius");
        if (params.getInt("maxTargets", 0) < 1) {
            throw new SkillParameterException("maxTargets", "ブラストアローの params[maxTargets] は1以上の整数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 14.0D);
        double radius = params.getDouble("radius", 2.25D);
        double damageRatio = params.getDouble("damageRatio", 1.44D);
        int maxTargets = params.getInt("maxTargets", 6);
        double projectileSpeed = params.getDouble("projectileSpeed", 1.35D);
        double projectileHitRadius = params.getDouble("projectileHitRadius", 0.45D);
        boolean[] detonated = {false};
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                range, projectileSpeed, projectileHitRadius, false, 1,
                SharedParticleDefinitions.ADVENTURER_BLAST_ARROW_TRAIL,
                SharedParticleDefinitions.ADVENTURER_BLAST_ARROW_IMPACT
        );
        context.services().projectiles().launchWithTermination(
                context.player(), context.eyeLocation(), context.direction(), projectile,
                (target, impact) -> detonate(
                        context, context.attacker(), impact, impact, detonated, radius, maxTargets, damageRatio
                ),
                termination -> {
                    if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        detonate(
                                context,
                                context.attacker(),
                                termination.location(),
                                termination.effectLocation(),
                                detonated, radius, maxTargets, damageRatio
                        );
                    }
                }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_ARROW_SHOOT, 1.15F, 0.90F);
        return context.success();
    }

    private void detonate(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull Location displayCenter,
            @NotNull Location effectCenter,
            boolean @NotNull [] detonated,
            double radius,
            int maxTargets,
            double damageRatio
    ) {
        if (detonated[0]) {
            return;
        }
        detonated[0] = true;
        context.services().effects().point(displayCenter, SharedParticleDefinitions.ADVENTURER_BLAST_ARROW_IMPACT);
        context.services().effects().ring(displayCenter, radius, 18, SharedParticleDefinitions.ADVENTURER_BLAST_ARROW_SHOCKWAVE);
        context.services().targeting().inRadius(context.player(), effectCenter, radius, radius, maxTargets, true)
                .forEach(target -> context.services().combat().hit(
                        attacker, target, AttackType.RANGED, DamageElement.NONE, damageRatio
                ));
        context.services().effects().sound(displayCenter, Sound.ENTITY_ARROW_HIT, 0.9F, 0.85F);
        context.services().effects().sound(displayCenter, Sound.ENTITY_GENERIC_EXPLODE, 0.55F, 1.65F);
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ブラストアローの params[" + key + "] は正数が必要です");
        }
    }
}
