package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

/**
 * ディバインチェイサーの有効状態を提供するパッシブスキル実装です。
 * <p>
 * 実際の命中判定と追撃は、共通のスキル命中通知へ接続した runtime が処理します。
 */
public final class PaladinDivineChaserSkillExecutor implements SkillExecutor {
    public static final String ID = "paladin_divine_chaser";

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

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        if (!ID.equals(skill.getId()) || !ID.equals(skill.getImplementationId())) {
            throw new SkillParameterException("id", "skillId と implementationId を一致させてください");
        }
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        double damageRatio = params.getDouble("damageRatio", 0.0D);
        if (!(damageRatio > 0.0D && damageRatio <= 1.0D)) {
            throw new SkillParameterException("damageRatio", "ディバインチェイサーの倍率は0より大きく1以下が必要です");
        }
        if (!(params.getDouble("sphereRadius", 0.0D) > 0.0D)) {
            throw new SkillParameterException("sphereRadius", "ディバインチェイサーの球体半径は正数が必要です");
        }
        if (params.getInt("spherePoints", 0) < 4) {
            throw new SkillParameterException("spherePoints", "ディバインチェイサーの球体表示点数は4以上が必要です");
        }
        if (!(params.getDouble("projectileSpeed", 0.0D) > 0.0D)) {
            throw new SkillParameterException("projectileSpeed", "ディバインチェイサーの追撃速度は正数が必要です");
        }
        if (!(params.getDouble("projectileHitRadius", 0.0D) > 0.0D)) {
            throw new SkillParameterException("projectileHitRadius", "ディバインチェイサーの追撃判定半径は正数が必要です");
        }
    }
}
