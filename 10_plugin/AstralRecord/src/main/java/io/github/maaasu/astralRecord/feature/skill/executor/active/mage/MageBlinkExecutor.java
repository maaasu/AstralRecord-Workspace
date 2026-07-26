package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 視線方向へ安全に七メートル瞬間移動するメイジの星渡りです。 */
public final class MageBlinkExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_blink";

    /** 共有発動スキルサービスで初期化します。 */
    public MageBlinkExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillMovementService.MovementResult movement = context.services().movement().blink(
                context.player(), context.attacker(), 7.0D);
        if (!movement.moved()) {
            return SkillCastResult.failure(PlayerMsgId.P_5805);
        }
        context.services().effects().point(
                movement.start().clone().add(0.0D, 1.0D, 0.0D),
                SharedParticleDefinitions.SKILL_MAGE_PORTAL
        );
        context.services().effects().point(
                movement.end().clone().add(0.0D, 1.0D, 0.0D),
                SharedParticleDefinitions.SKILL_MAGE_PORTAL
        );
        context.services().effects().sound(movement.end(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.9F, 1.2F);
        return context.success();
    }
}
