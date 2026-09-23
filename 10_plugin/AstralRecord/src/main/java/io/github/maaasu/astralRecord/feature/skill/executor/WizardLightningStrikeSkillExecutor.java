package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

/** 感電の付与成功時に落雷するバインド型パッシブの定義を検証します。 */
public final class WizardLightningStrikeSkillExecutor implements SkillExecutor {
    public static final String ID = "wizard_lightning_strike";

    /** @return このパッシブの実装ID */
    @Override
    public @NotNull String implementationId() {
        return ID;
    }

    /** @return パッシブ種別 */
    @Override
    public @NotNull SkillKind kind() {
        return SkillKind.PASSIVE;
    }

    /**
     * パッシブは直接詠唱できないため失敗を返します。
     *
     * @param context 詠唱要求
     * @return 失敗結果
     */
    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }

    /**
     * 落雷の射程と倍率が有効な正数か検証します。
     *
     * @param skill 読み込まれたスキル定義
     * @throws SkillParameterException ID、射程または倍率が不正な場合
     */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        if (!ID.equals(skill.getId()) || !ID.equals(skill.getImplementationId())) {
            throw new SkillParameterException("id", "skillId と implementationId を一致させてください");
        }
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        double range = params.getDouble("range", 0.0D);
        if (!Double.isFinite(range) || range <= 0.0D) {
            throw new SkillParameterException("range", "落雷の発動範囲は正数が必要です");
        }
        double damageRatio = params.getDouble("damageRatio", 0.0D);
        if (!Double.isFinite(damageRatio) || damageRatio <= 0.0D) {
            throw new SkillParameterException("damageRatio", "落雷のダメージ倍率は正数が必要です");
        }
    }
}
