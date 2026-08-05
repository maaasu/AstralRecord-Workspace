package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 着弾地点で爆発し、燃焼させるメイジの火焔弾です。 */
public final class MageFireballExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_fireball";

    /** 共有発動スキルサービスで初期化します。 */
    public MageFireballExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        AstEntity attacker = context.attacker();
        SkillParamReader params = new SkillParamReader(
                context.source().skill().getId(),
                context.source().skill().getParams()
        );
        double range = params.getDouble("range", 14.0D);
        double projectileSpeed = params.getDouble("projectileSpeed", 1.25D);
        double projectileHitRadius = params.getDouble("projectileHitRadius", 0.65D);
        double radius = params.getDouble("radius", 2.3D);
        double damageRatio = params.getDouble("damageRatio", 1.25D);
        int maxTargets = params.getInt("maxTargets", 8);
        double burnChance = params.getDouble("burnChance", 60.0D);
        long burnDurationTicks = Math.max(1L, Math.round(params.getDouble("burnDurationTicks", 60.0D)));
        boolean[] detonated = {false};
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                range, projectileSpeed, projectileHitRadius, false, 1,
                SharedParticleDefinitions.SKILL_MAGE_FIRE,
                SharedParticleDefinitions.SKILL_MAGE_FIRE
        );
        if (context.source().hasSigil("homing_fireball_sigil")) {
            context.services().projectiles().launchHoming(
                    context.player(), context.eyeLocation(), context.direction(), projectile,
                    0.22D, 6.0D,
                    (target, impact) -> detonate(
                            context, attacker, impact, detonated, radius, maxTargets,
                            damageRatio, burnChance, burnDurationTicks
                    ),
                    end -> detonate(
                            context, attacker, end, detonated, radius, maxTargets,
                            damageRatio, burnChance, burnDurationTicks
                    )
            );
        } else {
            context.services().projectiles().launch(
                    context.player(), context.eyeLocation(), context.direction(), projectile,
                    (target, impact) -> detonate(
                            context, attacker, impact, detonated, radius, maxTargets,
                            damageRatio, burnChance, burnDurationTicks
                    ),
                    end -> detonate(
                            context, attacker, end, detonated, radius, maxTargets,
                            damageRatio, burnChance, burnDurationTicks
                    )
            );
        }
        context.services().effects().sound(context.eyeLocation(), Sound.ITEM_FIRECHARGE_USE, 1.0F, 1.0F);
        return context.success();
    }

    private void detonate(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull Location center,
            boolean @NotNull [] detonated,
            double radius,
            int maxTargets,
            double damageRatio,
            double burnChance,
            long burnDurationTicks
    ) {
        if (detonated[0]) {
            return;
        }
        detonated[0] = true;
        context.services().effects().ring(center, radius, 16, SharedParticleDefinitions.SKILL_MAGE_FIRE);
        context.services().targeting().inRadius(context.player(), center, radius, radius, maxTargets, true)
                .forEach(target -> context.services().combat().hit(
                        attacker,
                        target,
                        AttackType.MAGIC,
                        DamageElement.FIRE,
                        damageRatio,
                        new ActiveSkillCondition(ConditionType.BURNING, burnChance, burnDurationTicks, 1.0D)
                ));
        context.services().effects().sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8F, 1.25F);
    }
}
