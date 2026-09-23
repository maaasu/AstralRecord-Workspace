package io.github.maaasu.astralRecord.feature.skill.executor.passive.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

/** 空中のバインド済みシフト入力から地上へ瞬間移動するメイジスキルです。 */
public final class MageBlinkSkillExecutor implements SkillExecutor {

    public static final String ID = "mage_blink";
    private static final double MAX_DISTANCE = 10.0D;

    private final SkillMovementService movementService;

    /** 地上への安全な瞬間移動サービスで初期化します。 */
    public MageBlinkSkillExecutor(@NotNull SkillMovementService movementService) {
        this.movementService = movementService;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull String implementationId() {
        return ID;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull SkillKind kind() {
        return SkillKind.PASSIVE;
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        if (!(context.caster() instanceof PlayerSkillCaster caster)) {
            return SkillCastResult.failure(PlayerMsgId.P_5805);
        }
        double maxDistance = new SkillParamReader(ID, context.skill().getParams())
                .getDouble("maxDistance", MAX_DISTANCE);
        var result = movementService.blinkGrounded(
                caster.player().getBukkit(),
                AstEntity.player(caster.player(), context.statusSnapshot()),
                maxDistance
        );
        return result.moved() ? SkillCastResult.succeeded() : SkillCastResult.failure(null);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        if (!ID.equals(skill.getId())) {
            throw new SkillParameterException("id", "skillId と implementationId を一致させてください");
        }
        double maxDistance = new SkillParamReader(ID, skill.getParams())
                .getDouble("maxDistance", Double.NaN);
        if (!Double.isFinite(maxDistance) || maxDistance <= 0.0D || maxDistance > MAX_DISTANCE) {
            throw new SkillParameterException("maxDistance", "0 より大きく 10 以下を指定してください");
        }
    }
}
