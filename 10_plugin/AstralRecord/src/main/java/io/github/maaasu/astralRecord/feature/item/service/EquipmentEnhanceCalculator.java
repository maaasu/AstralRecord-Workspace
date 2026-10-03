package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Objects;

/** 強化値の入力補正と、強化に伴う装備個体の耐久補正を計算します。 */
final class EquipmentEnhanceCalculator {
    private EquipmentEnhanceCalculator() {
    }

    /**
     * 現在状態の上限と、1 から連続して存在する強化段階の両方を満たす上限を返します。
     *
     * @param equipment 強化定義を持つ装備マスタ
     * @param transcendenceRank 現在の超越ランク
     * @return 通常強化で到達可能な最大強化値
     */
    static int maxLevel(@NotNull ItemEquipment equipment, int transcendenceRank) {
        int cap = OrbEligibility.effectiveEnhanceMaxLevel(equipment, transcendenceRank);
        int reachable = 0;
        var levels = Objects.requireNonNull(equipment.getEnhance()).getLevels().stream()
            .map(ItemEquipmentEnhanceLevel::getLevel).distinct().sorted(Comparator.naturalOrder()).toList();
        for (int level : levels) {
            if (level <= reachable) {
                continue;
            }
            if (level > cap || (long) level != (long) reachable + 1L) {
                break;
            }
            reachable = level;
        }
        return reachable;
    }

    /**
     * 符号付き入力は現在値との差分、符号なし入力は設定値として、実効上限内へ補正します。
     *
     * @param input 十進整数の入力
     * @param currentLevel 現在の強化値
     * @param maxLevel 現在状態の強化上限
     * @return 0 から上限までの設定値。不正な入力の場合は null
     */
    static @Nullable Integer resolveLevel(@NotNull String input, int currentLevel, int maxLevel) {
        if (!input.matches("[+-]?[0-9]+")) {
            return null;
        }
        BigInteger requested = new BigInteger(input);
        if (input.startsWith("+") || input.startsWith("-")) {
            requested = requested.add(BigInteger.valueOf(currentLevel));
        }
        return requested.max(BigInteger.ZERO).min(BigInteger.valueOf(Math.max(0, maxLevel))).intValue();
    }

    /**
     * 累積強化耐久の差分を最大耐久・現在耐久へ適用した新しい個体を返します。
     * 個体識別子、超越、乱数ロール、エンチャント、ルーンは保持し、現在個体を変更しません。
     *
     * @param equipment 強化定義を持つ装備マスタ
     * @param current 現在の装備個体
     * @param level 設定する補正済み強化値
     * @return 強化値・耐久値を更新した装備個体
     * @throws ArithmeticException 補正後の最大耐久が int の範囲を超える場合
     */
    static @NotNull EquipmentInstance withLevel(
        @NotNull ItemEquipment equipment,
        @NotNull EquipmentInstance current,
        int level
    ) {
        long delta = durabilityBonus(equipment, level) - durabilityBonus(equipment, current.getEnhanceLevel());
        int durabilityMax = Math.toIntExact(Math.max(0L, current.getDurabilityMax() + delta));
        int durabilityValue = (int) Math.max(0L, Math.min(durabilityMax, current.getDurabilityValue() + delta));
        return new EquipmentInstance(
            current.getEquipmentInstanceId(), current.getAccountId(), current.getItemId(), level,
            current.getRuneMaxSlots(), current.getTranscendenceRank(), durabilityMax, durabilityValue,
            current.getCreatedAt(), current.getUpdatedAt(), current.getStatRolls(), current.getEnchants(), current.getRunes()
        );
    }

    /**
     * 指定レベルまでの差分定義を合計し、累積の最大耐久補正を返します。
     *
     * @param equipment 強化定義を持つ装備マスタ
     * @param level 強化値
     * @return 累積耐久補正
     */
    private static long durabilityBonus(@NotNull ItemEquipment equipment, int level) {
        return Objects.requireNonNull(equipment.getEnhance()).getLevels().stream()
            .filter(definition -> definition.getLevel() <= level)
            .map(ItemEquipmentEnhanceLevel::getDurabilityBonus)
            .filter(Objects::nonNull)
            .mapToLong(Integer::longValue)
            .sum();
    }
}
