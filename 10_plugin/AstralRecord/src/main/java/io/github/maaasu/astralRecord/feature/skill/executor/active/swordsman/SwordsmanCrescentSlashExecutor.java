package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 前方の扇形を薙ぎ払うソードマンの半月斬りです。 */
public final class SwordsmanCrescentSlashExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_crescent_slash";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanCrescentSlashExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.player().getLocation().add(0.0D, 1.0D, 0.0D);
        context.services().effects().arc(
                origin,
                context.direction(),
                3.1D,
                110.0D,
                11,
                SharedParticleDefinitions.SKILL_SWORD_SWEEP
        );
        context.services().targeting().inCone(context.player(), 3.5D, 110.0D, 5, true)
                .forEach(target -> context.services().combat().hit(
                        context.attacker(), target, AttackType.MELEE, DamageElement.NONE, 1.15D));
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9F, 0.9F);
        return context.success();
    }
}
