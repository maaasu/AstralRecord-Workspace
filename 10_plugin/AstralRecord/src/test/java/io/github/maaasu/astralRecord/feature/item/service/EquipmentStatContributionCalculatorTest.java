package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.*;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.maaasu.astralRecord.feature.item.service.EquipmentStatContributionCalculator.ScalarInactiveReason.*;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentStatContributionCalculatorTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 装備単体補正集計
     * 検証契約: FLAT定義の存在と実効値を分け、加算の相殺もゼロとしてSCALARを無効判定する。
     */
    @Test
    void zeroAndCancelledFlatDoNotActivateScalar() {
        var equipment = equipment(List.of(stat("ATTACK", ItemEquipmentStatType.FLAT, 0, 0)), null);
        var scalar = enchant("ATTACK", "SCALAR", 1.5);
        var zero = calculate(equipment, instance(List.of(roll("ATTACK", 0, 0, 0)), List.of(scalar), List.of(), 0), null);
        assertEquals(NO_FLAT, zero.scalarInactiveReason(0));
        assertEquals(new EquipmentStatContributionCalculator.Range(0, 0), zero.resolve(StatusType.ATTACK));
        var cancelled = calculate(equipment, instance(List.of(roll("ATTACK", 10, 10, 0)),
            List.of(enchant("ATTACK", "FLAT", -10), scalar), List.of(), 0), null);
        assertEquals(NO_FLAT, cancelled.scalarInactiveReason(1));
        assertEquals(new EquipmentStatContributionCalculator.Range(0, 0), cancelled.resolve(StatusType.ATTACK));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 装備単体補正集計
     * 検証契約: 強化、別enchant、runeのFLATを全て足してから装備単体SCALARを適用する。
     */
    @Test
    void enhancementEnchantAndRuneFlatActivateScalar() {
        var enhance = new ItemEquipmentEnhance(2, List.of(new ItemEquipmentEnhanceLevel(1,
            List.of(new ItemEquipmentEnhanceStatIncrease("ATTACK", ItemEquipmentStatType.FLAT, 2, 3)), null, 1,
            ItemEquipmentEnhanceFailAction.NONE, null)));
        var result = calculate(equipment(List.of(), enhance), instance(List.of(),
            List.of(enchant("ATTACK", "SCALAR", 2), enchant("ATTACK", "FLAT", 4)),
            List.of(new EquipmentRune("r", "i", 0, "rune")), 1),
            rune(List.of(stat("ATTACK", ItemEquipmentStatType.FLAT, 5, 5))));
        assertEquals(NONE, result.scalarInactiveReason(0));
        assertEquals(new EquipmentStatContributionCalculator.Range(22, 24), result.resolve(StatusType.ATTACK));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 装備単体補正集計
     * 検証契約: 同一statusのroll方式をsortOrderで解決し、最後のSCALARだけを採用し4端点を評価する。
     */
    @Test
    void lastScalarWinsAndNegativeRangesUseAllProducts() {
        var equipment = equipment(List.of(stat("ATTACK", ItemEquipmentStatType.FLAT, -10, 20),
            stat("ATTACK", ItemEquipmentStatType.SCALAR, 7, 7)), null);
        var instance = instance(List.of(roll("ATTACK", -10, 20, 0), roll("ATTACK", 7, 7, 1)),
            List.of(enchant("ATTACK", "SCALAR", 2), enchant("ATTACK", "SCALAR", 3)),
            List.of(new EquipmentRune("r", "i", 0, "rune")), 0);
        var enchantsOnly = calculate(equipment, instance, null);
        assertEquals(OVERRIDDEN, enchantsOnly.scalarInactiveReason(0));
        assertEquals(NONE, enchantsOnly.scalarInactiveReason(1));
        assertEquals(new EquipmentStatContributionCalculator.Range(-30, 60), enchantsOnly.resolve(StatusType.ATTACK));
        var withRune = calculate(equipment, instance, rune(List.of(stat("ATTACK", ItemEquipmentStatType.SCALAR, -1, 2))));
        assertEquals(OVERRIDDEN, withRune.scalarInactiveReason(1));
        assertEquals(new EquipmentStatContributionCalculator.Range(-20, 40), withRune.resolve(StatusType.ATTACK));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 装備単体補正集計
     * 検証契約: 強化の逆順端点を個別正規化せず累積後に正規化し既存値を保持する。
     */
    @Test
    void enhancementEndpointsAreSummedBeforeNormalization() {
        var enhancement = new ItemEquipmentEnhance(2, List.of(
            new ItemEquipmentEnhanceLevel(1, List.of(new ItemEquipmentEnhanceStatIncrease("ATTACK", ItemEquipmentStatType.FLAT, 2, 1)), null, 1, ItemEquipmentEnhanceFailAction.NONE, null),
            new ItemEquipmentEnhanceLevel(2, List.of(new ItemEquipmentEnhanceStatIncrease("ATTACK", ItemEquipmentStatType.FLAT, -2, 0)), null, 1, ItemEquipmentEnhanceFailAction.NONE, null)));
        var result = calculate(equipment(List.of(), enhancement), instance(List.of(), List.of(), List.of(), 2), null);
        assertEquals(new EquipmentStatContributionCalculator.Range(0, 1), result.resolve(StatusType.ATTACK));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 装備単体補正集計
     * 検証契約: 旧個体の採集・釣りレベルだけを補完し、既存rollの二重加算と他stat補完を防ぐ。
     */
    @Test
    void missingToolLevelsAreSupplementedWithoutDoublingRolls() {
        var equipment = equipment(List.of(stat("GATHERING_LEVEL", ItemEquipmentStatType.FLAT, 4, 4),
            stat("FISHING_LEVEL", ItemEquipmentStatType.FLAT, 7, 7), stat("ATTACK", ItemEquipmentStatType.FLAT, 10, 10)), null);
        var result = calculate(equipment, instance(List.of(roll("GATHERING_LEVEL", 2, 2, 0)), List.of(), List.of(), 0), null);
        assertEquals(new EquipmentStatContributionCalculator.Range(2, 2), result.resolve(StatusType.GATHERING_LEVEL));
        assertEquals(new EquipmentStatContributionCalculator.Range(7, 7), result.resolve(StatusType.FISHING_LEVEL));
        assertNull(result.resolve(StatusType.ATTACK));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 装備単体補正集計
     * 検証契約: SCALAR単体と未知statusは値を生成せず、不正rollの既存0変換も保持する。
     */
    @Test
    void scalarOnlyAndUnknownStatusesDoNotGenerateContribution() {
        var result = calculate(equipment(List.of(), null), instance(List.of(new EquipmentStatRoll("r", "DEFENSE", "bad", "bad", 0)),
            List.of(enchant("ATTACK", "SCALAR", 2), enchant("unknown", "SCALAR", 2)), List.of(), 0), null);
        assertNull(result.resolve(StatusType.ATTACK));
        assertEquals(NO_FLAT, result.scalarInactiveReason(0));
        assertEquals(UNKNOWN_STATUS, result.scalarInactiveReason(1));
        assertEquals(new EquipmentStatContributionCalculator.Range(0, 0), result.resolve(StatusType.DEFENSE));
    }

    private static EquipmentStatContributionCalculator.Result calculate(ItemEquipment equipment, EquipmentInstance instance, ItemModel rune) {
        return EquipmentStatContributionCalculator.calculate(equipment, instance, id -> rune);
    }

    private static ItemEquipment equipment(List<ItemEquipmentStat> stats, ItemEquipmentEnhance enhance) {
        return new ItemEquipment(ItemEquipmentSlot.WEAPON, ItemEquipmentHandType.ONE, null, 0, List.of(), null,
            stats, null, enhance, null, null, List.of());
    }

    private static EquipmentInstance instance(List<EquipmentStatRoll> rolls, List<EquipmentEnchant> enchants, List<EquipmentRune> runes, int level) {
        return new EquipmentInstance("i", "a", "item", level, 1, 0, 0, 0, "", "", rolls, enchants, runes);
    }

    private static ItemEquipmentStat stat(String status, ItemEquipmentStatType type, double min, double max) {
        return new ItemEquipmentStat(status, type, min, max);
    }

    private static EquipmentStatRoll roll(String status, double min, double max, int order) {
        return new EquipmentStatRoll("r", status, Double.toString(min), Double.toString(max), order);
    }

    private static EquipmentEnchant enchant(String status, String type, double value) {
        return new EquipmentEnchant("e", "i", 0, "master", "effect", status, type, value);
    }

    private static ItemModel rune(List<ItemEquipmentStat> stats) {
        return new ItemModel(1, "rune", "rune", "ルーン", "PAPER", "common", 1, 0, null, null, List.of(),
            false, false, null, null, null, new ItemRune(List.of(), 0, stats, List.of()), null, null, null);
    }
}
