package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 前方の地面を砕き、敵を押し飛ばすソードマンの地裂斬です。 */
public final class SwordsmanEarthbreakerExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_earthbreaker";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanEarthbreakerExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location source = context.player().getLocation().clone();
        Location visualOrigin = source.clone().add(0.0D, 0.15D, 0.0D);
        context.services().effects().arc(
                visualOrigin,
                context.direction(),
                5.2D,
                90.0D,
                13,
                SharedParticleDefinitions.SKILL_SWORD_EDGE
        );
        context.services().targeting().inCone(context.player(), 5.5D, 90.0D, 8, true).forEach(target -> {
            DamageResult result = context.services().combat().hit(
                    context.attacker(), target, AttackType.MELEE, DamageElement.NONE, 2.10D);
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                context.services().combat().knockback(target, source, 1.25D, 0.25D);
            }
        });
        context.services().effects().sound(source, Sound.ENTITY_GENERIC_EXPLODE, 0.9F, 0.75F);
        return context.success();
    }
}
