package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.StatusPassiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillStatusModifier;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 既存ステータス補正を保ち、固有戦闘効果のパラメータを検証します。 */
public final class PhantomArcherPassiveExecutor implements SkillExecutor {
    public static final String ATTUNEMENT_ID = "phantom_archer_spectral_attunement";
    public static final String CURSE_ID = "phantom_archer_curse_weaving";
    public static final String WRAITH_ID = "phantom_archer_wraith_step";
    private final String skillId;
    private final StatusPassiveSkillExecutor status = new StatusPassiveSkillExecutor();

    /**
     * 固有パッシブの実装を作成します。
     * @param skillId 担当するスキルID兼implementationId
     */
    public PhantomArcherPassiveExecutor(@NotNull String skillId) {
        this.skillId = skillId;
    }

    /** {@inheritDoc} */
    @Override public @NotNull String implementationId() { return skillId; }

    /** {@inheritDoc} */
    @Override public @NotNull SkillKind kind() { return SkillKind.PASSIVE; }

    /** {@inheritDoc} */
    @Override public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }

    /** {@inheritDoc} */
    @Override public void validateParams(@NotNull SkillDefinition skill) {
        if (!skill.getPassiveBindRequired() || skill.getMaxLevel() != 1
                || !skill.getLevels().isEmpty() || !skill.getAllowedSigilIds().isEmpty()
                || !skill.getSigilSlotsByLevel().isEmpty()) {
            throw new SkillParameterException("passive", "バインド必須・最大Lv1・シジル枠なしが必要です");
        }
        status.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        if (CURSE_ID.equals(skillId)) positive(p, "markBonusRatio");
        if (WRAITH_ID.equals(skillId)) {
            positive(p, "phantomDamageRatio");
            int ticks = p.getInt("readyDurationTicks", -1);
            if (ticks < 1 || ticks > 400)
                throw new SkillParameterException("readyDurationTicks", "1から400の整数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override public @NotNull List<PassiveSkillStatusModifier> passiveStatusModifiers(
            @NotNull PassiveSkillContext context) {
        return status.passiveStatusModifiers(context);
    }

    /** {@inheritDoc} */
    @Override public void onDeactivate(@NotNull PassiveSkillContext context) {
        if (WRAITH_ID.equals(skillId))
            PhantomArcherRuntime.clearReadyArrow(context.player().getBukkit().getUniqueId());
    }

    /** 指定された倍率が有限の正数か確認します。 */
    private void positive(SkillParamReader p, String key) {
        double value = p.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D)
            throw new SkillParameterException(key, "有限の正数が必要です");
    }
}
