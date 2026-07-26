package io.github.maaasu.astralRecord.feature.skill.active.model;

import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import org.jetbrains.annotations.NotNull;

/**
 * 発動スキルが命中時に付与する状態異常を型付きで表します。
 *
 * @param type 状態異常種別
 * @param chance 基礎付与確率（%）
 * @param durationTicks 効果時間（tick）
 * @param strength 同種状態異常を比較する強さ
 */
public record ActiveSkillCondition(
        @NotNull ConditionType type,
        double chance,
        long durationTicks,
        double strength
) {

    /**
     * 入力値を状態異常サービスの許容範囲へ正規化します。
     */
    public ActiveSkillCondition {
        chance = Math.clamp(chance, 0.0D, 100.0D);
        durationTicks = Math.max(1L, durationTicks);
        strength = Math.max(0.0D, strength);
    }

    /**
     * 確定付与する標準強度の状態異常を作成します。
     *
     * @param type 状態異常種別
     * @param durationTicks 効果時間（tick）
     * @return 状態異常定義
     */
    public static @NotNull ActiveSkillCondition certain(
            @NotNull ConditionType type,
            long durationTicks
    ) {
        return new ActiveSkillCondition(type, 100.0D, durationTicks, 1.0D);
    }
}
