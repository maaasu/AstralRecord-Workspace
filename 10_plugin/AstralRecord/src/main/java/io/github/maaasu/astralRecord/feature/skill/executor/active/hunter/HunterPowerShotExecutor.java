package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 高威力の一矢を射るハンターの強弓射ちです。 */
public final class HunterPowerShotExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_power_shot";

    /** 共有発動スキルサービスで初期化します。 */
    public HunterPowerShotExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        AstEntity attacker = context.attacker();
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                18.0D, 1.6D, 0.45D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        context.services().projectiles().launch(
                context.player(), context.eyeLocation(), context.direction(), projectile,
                (target, ignored) -> context.services().combat().hit(
                        attacker, target, AttackType.RANGED, DamageElement.NONE, 1.55D),
                ignored -> { }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0F, 0.75F);
        return context.success();
    }
}
