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

/** 命中するたび威力を15%ずつ落として四体まで貫くハンターの貫通矢です。 */
public final class HunterPiercingArrowExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_piercing_arrow";

    /** 共有発動スキルサービスで初期化します。 */
    public HunterPiercingArrowExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        AstEntity attacker = context.attacker();
        int[] hitIndex = {0};
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                16.0D, 1.55D, 0.50D, true, 4,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        context.services().projectiles().launch(
                context.player(), context.eyeLocation(), context.direction(), projectile,
                (target, ignored) -> {
                    double ratio = 1.30D * Math.pow(0.85D, hitIndex[0]++);
                    context.services().combat().hit(
                            attacker, target, AttackType.RANGED, DamageElement.NONE, ratio);
                },
                ignored -> { }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0F, 0.9F);
        return context.success();
    }
}
