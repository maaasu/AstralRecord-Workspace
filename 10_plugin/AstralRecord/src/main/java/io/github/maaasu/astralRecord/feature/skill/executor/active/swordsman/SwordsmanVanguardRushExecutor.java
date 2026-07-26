package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** 安全な移動経路に沿って敵を切り抜けるソードマンの先陣突撃です。 */
public final class SwordsmanVanguardRushExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_vanguard_rush";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanVanguardRushExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillMovementService.MovementResult movement = context.services().movement().dash(
                context.player(), context.attacker(), 5.5D);
        if (!movement.moved()) {
            return SkillCastResult.failure(PlayerMsgId.P_5805);
        }
        Location start = movement.start().clone().add(0.0D, 1.0D, 0.0D);
        Location end = movement.end().clone().add(0.0D, 1.0D, 0.0D);
        Vector path = end.toVector().subtract(start.toVector());
        double distance = path.length();
        context.services().effects().line(start, end, 0.45D, SharedParticleDefinitions.SKILL_SWORD_EDGE);
        if (distance > 1.0E-6D) {
            context.services().targeting().inLine(context.player(), start, path, distance, 0.9D, 5)
                    .forEach(target -> context.services().combat().hit(
                            context.attacker(), target, AttackType.MELEE, DamageElement.NONE, 1.20D));
        }
        context.services().effects().sound(end, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.9F);
        return context.success();
    }
}
