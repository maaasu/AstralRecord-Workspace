package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** 装備1個の補正集計を、実戦計算と表示の両方で共有します。 */
public final class EquipmentStatContributionCalculator {
    private EquipmentStatContributionCalculator() { }

    /**
     * 確定roll、未移行の採集・釣りレベル、強化、enchant、runeの順に集計します。
     * FLATは加算し、SCALARは同ステータスの最後の値を採用します。装備可否・耐久判定は呼出側で行います。
     *
     * @param equipment 装備マスタ
     * @param instance 装備個体。変更しません
     * @param runeResolver 装着ルーンの解決関数。表示側は通信しないキャッシュ参照を渡します
     * @return 装備単体の集計。未知ステータスは従来どおり集計しません
     */
    public static @NotNull Result calculate(@NotNull ItemEquipment equipment, @NotNull EquipmentInstance instance,
                                           @NotNull Function<String, ItemModel> runeResolver) {
        Result result = new Result();
        boolean gatheringRoll = false;
        boolean fishingRoll = false;
        for (var roll : instance.getStatRolls()) {
            StatusType status = resolveStatus(roll.getStatus());
            if (status == null) continue;
            if (status == StatusType.GATHERING_LEVEL) gatheringRoll = true;
            if (status == StatusType.FISHING_LEVEL) fishingRoll = true;
            ItemEquipmentStat definition = equipment.findStatDefinition(roll);
            result.add(status, definition == null ? ItemEquipmentStatType.FLAT : definition.getType(),
                parseRoll(roll.getMin()), parseRoll(roll.getMax()), -1);
        }
        // 従来の補完順序も維持します。
        if (!gatheringRoll) supplement(equipment, result, StatusType.GATHERING_LEVEL);
        if (!fishingRoll) supplement(equipment, result, StatusType.FISHING_LEVEL);

        Map<String, Amount> enhancement = new LinkedHashMap<>();
        if (equipment.getEnhance() != null && instance.getEnhanceLevel() > 0) {
            for (var level : equipment.getEnhance().getLevels()) {
                if (level.getLevel() > instance.getEnhanceLevel()) continue;
                for (var increase : level.getStatIncrease()) {
                    StatusType status = resolveStatus(increase.getStatus());
                    if (status == null) continue;
                    String key = status.name() + "#" + increase.getType().name();
                    enhancement.computeIfAbsent(key, ignored -> new Amount(status, increase.getType()))
                        .addEnhancement(increase.getMin(), increase.getMax());
                }
            }
        }
        for (Amount amount : enhancement.values()) {
            result.add(amount.status, amount.type, amount.min, amount.max, -1);
        }
        for (int index = 0; index < instance.getEnchants().size(); index++) {
            var enchant = instance.getEnchants().get(index);
            StatusType status = resolveStatus(enchant.getStatus());
            result.enchantStatuses.put(index, status);
            if (status != null) {
                result.add(status, ItemEquipmentStatType.fromApiValue(enchant.getType()),
                    enchant.getValue(), enchant.getValue(), index);
            }
        }
        for (var equippedRune : instance.getRunes()) {
            ItemModel rune = runeResolver.apply(equippedRune.getItemId());
            if (rune == null || rune.getRune() == null) continue;
            for (ItemEquipmentStat stat : rune.getRune().getStats()) {
                StatusType status = resolveStatus(stat.getStatus());
                if (status != null) result.add(status, stat.getType(), stat.getMin(), stat.getMax(), -1);
            }
        }
        return result;
    }

    /** rollを持たない旧個体に、マスタで明示された対象レベルだけを補完します。 */
    private static void supplement(ItemEquipment equipment, Result result, StatusType status) {
        for (ItemEquipmentStat stat : equipment.getStats()) {
            if (status.getId().equalsIgnoreCase(stat.getStatus())) {
                result.add(status, stat.getType(), stat.getMin(), stat.getMax(), -1);
            }
        }
    }

    /** 実戦経路の既存roll変換と同じく、半角区切り前の値を使用します。 */
    private static double parseRoll(String value) {
        try {
            return Double.parseDouble(value.trim().split("~")[0].trim());
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    /** 既存ステータス解決規則を共通化します。 */
    private static @Nullable StatusType resolveStatus(String raw) {
        try {
            return StatusType.valueOf(raw.trim().replace(' ', '_').replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 計算済みの下限・上限。 */
    public record Range(double min, double max) { }

    /** SCALARの装備単体での非適用理由。装備可否・耐久の状態は含みません。 */
    public enum ScalarInactiveReason { NONE, NO_FLAT, OVERRIDDEN, UNKNOWN_STATUS }

    /** 装備単体の集計結果。計算後は読取専用として使用します。 */
    public static final class Result {
        private final Map<StatusType, Amount> flat = new EnumMap<>(StatusType.class);
        private final Map<StatusType, Amount> scalar = new EnumMap<>(StatusType.class);
        private final Map<StatusType, Integer> scalarOwner = new EnumMap<>(StatusType.class);
        private final Map<Integer, StatusType> enchantStatuses = new LinkedHashMap<>();

        private Result() { }

        private void add(StatusType status, ItemEquipmentStatType type, double min, double max, int enchantIndex) {
            Map<StatusType, Amount> target = type == ItemEquipmentStatType.SCALAR ? scalar : flat;
            Amount amount = target.computeIfAbsent(status, ignored -> new Amount(status, type));
            if (type == ItemEquipmentStatType.SCALAR) {
                amount.min = Math.min(min, max);
                amount.max = Math.max(min, max);
                scalarOwner.put(status, enchantIndex);
            } else {
                amount.add(min, max);
            }
        }

        /**
         * FLAT合計へ最後のSCALARを掛けた値を返します。負値を含む範囲は4端点から上下限を求めます。
         *
         * @param status 対象ステータス
         * @return FLATがない場合はnull。実効ゼロのFLATはゼロ範囲を返します
         */
        public @Nullable Range resolve(@NotNull StatusType status) {
            Amount base = flat.get(status);
            if (base == null) return null;
            Amount multiplier = scalar.get(status);
            if (multiplier == null) return new Range(base.min, base.max);
            double[] values = { base.min * multiplier.min, base.min * multiplier.max,
                base.max * multiplier.min, base.max * multiplier.max };
            double min = values[0], max = values[0];
            for (double value : values) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            return new Range(min, max);
        }

        /**
         * 付与済みSCALARが集計へ適用されない理由を返します。
         *
         * @param enchantIndex 個体enchantリスト内の位置。SCALARの行だけで呼び出します
         * @return 未解決、後続SCALARによる上書き、FLAT合計ゼロを区別した結果
         */
        public @NotNull ScalarInactiveReason scalarInactiveReason(int enchantIndex) {
            StatusType status = enchantStatuses.get(enchantIndex);
            if (status == null) return ScalarInactiveReason.UNKNOWN_STATUS;
            if (!Integer.valueOf(enchantIndex).equals(scalarOwner.get(status))) return ScalarInactiveReason.OVERRIDDEN;
            Amount base = flat.get(status);
            return base == null || (base.min == 0.0D && base.max == 0.0D)
                ? ScalarInactiveReason.NO_FLAT : ScalarInactiveReason.NONE;
        }
    }

    private static final class Amount {
        private final StatusType status;
        private final ItemEquipmentStatType type;
        private double min;
        private double max;

        private Amount(StatusType status, ItemEquipmentStatType type) {
            this.status = status;
            this.type = type;
        }

        private void addEnhancement(double lower, double upper) {
            min += lower;
            max += upper;
        }

        private void add(double lower, double upper) {
            min += Math.min(lower, upper);
            max += Math.max(lower, upper);
        }
    }
}
