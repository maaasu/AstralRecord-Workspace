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

/** 細い直線上の敵をまとめて貫くソードマンの貫き突きです。 */
public final class SwordsmanPiercingThrustExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_piercing_thrust";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanPiercingThrustExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        Location end = context.services().targeting().clippedEnd(origin, context.direction(), 5.0D);
        context.services().effects().line(origin, end, 0.38D, SharedParticleDefinitions.SKILL_SWORD_EDGE);
        context.services().targeting().inLine(context.player(), origin, context.direction(), 5.0D, 0.55D, 3)
                .forEach(target -> context.services().combat().hit(
                        context.attacker(), target, AttackType.MELEE, DamageElement.NONE, 1.55D));
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9F, 1.15F);
        return context.success();
    }
}
