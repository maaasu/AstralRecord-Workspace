package io.github.maaasu.astralRecord.feature.condition.model;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 状態異常付与要求です。 */
public record ConditionApplyRequest(
        @NotNull AstEntity target,
        @Nullable AstEntity source,
        @NotNull ConditionType type,
        @Nullable AttackType attackType,
        long durationTicks,
        double chance,
        double strength,
        @Nullable Double basePower,
        @Nullable Double powerCoefficient,
        @Nullable Double healthRate,
        @Nullable Integer tickIntervalTicks,
        @NotNull ConditionApplyReason reason
) {
    public ConditionApplyRequest {
        durationTicks = durationTicks <= 0L ? type.defaultDurationTicks() : durationTicks;
        chance = Math.max(0.0D, Math.min(100.0D, chance));
        strength = Math.max(0.0D, strength);
    }

    /**
     * 攻撃種別を省略した互換用の状態異常付与要求を作成します。
     *
     * @param target 対象
     * @param source 付与元
     * @param type 状態異常種別
     * @param durationTicks 持続時間
     * @param chance 付与確率
     * @param strength 同種比較用の強さ
     * @param basePower 固定値
     * @param powerCoefficient 基準能力値係数
     * @param healthRate HP割合。DoTでは0へ正規化される
     * @param tickIntervalTicks pulse間隔
     * @param reason 付与理由
     */
    public ConditionApplyRequest(
            @NotNull AstEntity target,
            @Nullable AstEntity source,
            @NotNull ConditionType type,
            long durationTicks,
            double chance,
            double strength,
            @Nullable Double basePower,
            @Nullable Double powerCoefficient,
            @Nullable Double healthRate,
            @Nullable Integer tickIntervalTicks,
            @NotNull ConditionApplyReason reason
    ) {
        this(target, source, type, null, durationTicks, chance, strength, basePower,
                powerCoefficient, healthRate, tickIntervalTicks, reason);
    }
}
