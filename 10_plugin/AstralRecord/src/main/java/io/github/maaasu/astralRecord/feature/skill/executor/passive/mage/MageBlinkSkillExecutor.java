package io.github.maaasu.astralRecord.feature.skill.executor.passive.mage;

import io.github.maaasu.astralRecord.feature.skill.active.service.SkillPackDisplay;
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
    private final SkillPackDisplay packDisplay;

    /**
     * 地上への安全な瞬間移動とパック演出を接続します。
     *
     * @param movementService 地上への安全な瞬間移動サービス
     * @param packDisplay パック対応の閲覧者に表示する演出サービス
     */
    public MageBlinkSkillExecutor(@NotNull SkillMovementService movementService,
            @NotNull SkillPackDisplay packDisplay) {
        this.packDisplay = packDisplay;
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
        if (!result.moved()) {
            return SkillCastResult.failure(null);
        }
        var player = caster.player().getBukkit();
        packDisplay.show(ID, SkillPackDisplay.Phase.CAST, result.start().clone().add(0.0D, 0.8D, 0.0D), 1.5F, player);
        packDisplay.show(ID, SkillPackDisplay.Phase.IMPACT, result.end().clone().add(0.0D, 0.8D, 0.0D), 1.5F, player);
        return SkillCastResult.succeeded();
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
