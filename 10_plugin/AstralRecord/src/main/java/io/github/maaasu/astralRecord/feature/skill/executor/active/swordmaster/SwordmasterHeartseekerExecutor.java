package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** 弱った敵へ踏み込まず刺突を放ち、撃破で自己回復する断命剣です。 */
public final class SwordmasterHeartseekerExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_heartseeker";

    /**
     * 共有サービスで初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     */
    public SwordmasterHeartseekerExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "hitRadius", "maxTargets", "damageRatio", "executeThreshold", "executeMultiplier", "healHpRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        Location end = context.services().targeting().clippedEnd(origin, context.direction(), value(context, "range"));
        List<AstEntity> targets = context.services().targeting().inLineBeforeBlock(
                context.player(), origin, context.direction(), origin.distance(end),
                value(context, "hitRadius"), (int) value(context, "maxTargets"));
        if (targets.isEmpty()) return SkillCastResult.failure(PlayerMsgId.P_5805);
        AstEntity target = targets.getFirst();
        boolean execute = target.currentHealth() / Math.max(1.0D, target.maxHealth()) <= value(context, "executeThreshold");
        trail(context, origin, target.location().clone().add(0.0D, 0.9D, 0.0D), SharedParticleDefinitions.SWORDMASTER_GOLD);
        Hit hit = hit(context, target, DamageElement.NONE,
                value(context, "damageRatio") * (execute ? value(context, "executeMultiplier") : 1.0D));
        if (hit.killed()) heal(context, value(context, "healHpRatio"));
        return context.success();
    }
}
