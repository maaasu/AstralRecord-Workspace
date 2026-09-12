package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import org.jetbrains.annotations.NotNull;

/**
 * ディフェンスコンバージョンの有効状態だけを提供するパッシブスキル実装です。
 * <p>
 * 防御力参照への変換は共通ダメージ経路が {@link #ID} の有効状態を判定して適用します。
 */
public final class PaladinDefenseConversionSkillExecutor implements SkillExecutor {
    public static final String ID = "paladin_defense_conversion";

    @Override
    public @NotNull String implementationId() {
        return ID;
    }

    @Override
    public @NotNull SkillKind kind() {
        return SkillKind.PASSIVE;
    }

    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }
}
