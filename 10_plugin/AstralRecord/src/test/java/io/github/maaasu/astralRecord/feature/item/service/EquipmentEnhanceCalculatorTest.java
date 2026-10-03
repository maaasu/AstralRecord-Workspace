package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentRune;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EquipmentEnhanceCalculatorTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 符号なしは設定値、正負の符号付きは現在値との差分として解釈する。
     */
    @Test
    void distinguishesAbsoluteAndRelativeLevels() {
        assertEquals(2, EquipmentEnhanceCalculator.resolveLevel("2", 7, 10));
        assertEquals(9, EquipmentEnhanceCalculator.resolveLevel("+2", 7, 10));
        assertEquals(5, EquipmentEnhanceCalculator.resolveLevel("-2", 7, 10));
        assertEquals(0, EquipmentEnhanceCalculator.resolveLevel("0", 7, 10));
        assertEquals(7, EquipmentEnhanceCalculator.resolveLevel("+0", 7, 10));
        assertEquals(7, EquipmentEnhanceCalculator.resolveLevel("-0", 7, 10));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 設定値と差分の桁数に依存せず、結果を0から現在状態の上限へ補正する。
     */
    @Test
    void clampsBeyondLongRangeWithoutOverflow() {
        String huge = "9999999999999999999999999999999999999999999999999999";
        assertEquals(10, EquipmentEnhanceCalculator.resolveLevel(huge, 7, 10));
        assertEquals(10, EquipmentEnhanceCalculator.resolveLevel("+" + huge, 7, 10));
        assertEquals(0, EquipmentEnhanceCalculator.resolveLevel("-" + huge, 7, 10));
        assertEquals(0, EquipmentEnhanceCalculator.resolveLevel("-8", 7, 10));
        assertEquals(10, EquipmentEnhanceCalculator.resolveLevel("100", 7, 10));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 十進整数以外の入力では強化値を解釈せず拒否する。
     */
    @Test
    void rejectsMalformedLevelInputs() {
        for (String input : List.of("", "+", "-", "1.5", "1e3", "++1", "+-1", " 2", "2 ", "two")) {
            assertNull(EquipmentEnhanceCalculator.resolveLevel(input, 7, 10), input);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 超越の上限拡張があっても、連続した強化定義の欠損を超える値へ設定しない。
     */
    @Test
    void limitsMaximumToContiguousDefinitions() {
        ItemEquipment equipment = equipment(7, List.of(level(5, 0), level(2, 0), level(1, 0), level(2, 0)), List.of());
        assertEquals(2, EquipmentEnhanceCalculator.maxLevel(equipment, 0));
        assertEquals(0, EquipmentEnhanceCalculator.maxLevel(equipment(7, List.of(level(2, 0)), List.of()), 0));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 現在ランクまでの超越上限をランク順で反映し、将来ランクの上限は適用しない。
     */
    @Test
    void appliesOnlyCurrentTranscendenceOverridesInRankOrder() {
        ItemEquipment equipment = equipment(2, List.of(level(1, 0), level(2, 0), level(3, 0), level(4, 0)),
            List.of(transcendence(2, 3), transcendence(1, 4)));
        assertEquals(2, EquipmentEnhanceCalculator.maxLevel(equipment, 0));
        assertEquals(4, EquipmentEnhanceCalculator.maxLevel(equipment, 1));
        assertEquals(3, EquipmentEnhanceCalculator.maxLevel(equipment, 2));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 強化の上下変更は累積耐久差分だけを適用し、個体の他データと入力個体を保持する。
     */
    @Test
    void adjustsDurabilityAndPreservesOtherInstanceState() {
        ItemEquipment equipment = equipment(3, List.of(level(1, 20), level(2, 15), level(3, 15)), List.of());
        EquipmentInstance original = instance(1, 120, 90);
        EquipmentInstance raised = EquipmentEnhanceCalculator.withLevel(equipment, original, 3);
        assertEquals(150, raised.getDurabilityMax());
        assertEquals(120, raised.getDurabilityValue());
        assertEquals(original, EquipmentEnhanceCalculator.withLevel(equipment, raised, 1));
        assertEquals(original.getStatRolls(), raised.getStatRolls());
        assertEquals(original.getEnchants(), raised.getEnchants());
        assertEquals(original.getRunes(), raised.getRunes());
        assertEquals(original.getTranscendenceRank(), raised.getTranscendenceRank());
        assertEquals(original.getRuneMaxSlots(), raised.getRuneMaxSlots());
        assertEquals(1, original.getEnhanceLevel());
        assertEquals(90, original.getDurabilityValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 強化減算で現在耐久が負になる場合は0へ補正し、最大耐久を超える現在耐久も補正する。
     */
    @Test
    void clampsDurabilityAfterReducingEnhancement() {
        ItemEquipment equipment = equipment(3, List.of(level(1, 20), level(2, 15), level(3, 15)), List.of());
        EquipmentInstance reduced = EquipmentEnhanceCalculator.withLevel(equipment, instance(3, 150, 5), 0);
        assertEquals(100, reduced.getDurabilityMax());
        assertEquals(0, reduced.getDurabilityValue());
        assertEquals(100, EquipmentEnhanceCalculator.withLevel(equipment, instance(3, 150, 200), 0).getDurabilityValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### 管理者強化値変更
     * 検証契約: 最大耐久がint範囲を超える強化変更は、個体へ適用する前に拒否する。
     */
    @Test
    void rejectsDurabilityOverflowWithoutChangingCurrentInstance() {
        ItemEquipment equipment = equipment(1, List.of(level(1, 1)), List.of());
        EquipmentInstance current = instance(0, Integer.MAX_VALUE, 10);
        assertThrows(ArithmeticException.class, () -> EquipmentEnhanceCalculator.withLevel(equipment, current, 1));
        assertEquals(0, current.getEnhanceLevel());
        assertEquals(Integer.MAX_VALUE, current.getDurabilityMax());
    }

    private static ItemEquipment equipment(int max, List<ItemEquipmentEnhanceLevel> levels,
        List<ItemEquipmentTranscendence> transcendence) {
        ItemEquipment equipment = mock(ItemEquipment.class);
        when(equipment.getEnhance()).thenReturn(new ItemEquipmentEnhance(max, levels));
        when(equipment.getTranscendence()).thenReturn(transcendence);
        return equipment;
    }

    private static ItemEquipmentEnhanceLevel level(int level, int bonus) {
        return new ItemEquipmentEnhanceLevel(level, List.of(), bonus, 1.0D, ItemEquipmentEnhanceFailAction.NONE, null);
    }

    private static ItemEquipmentTranscendence transcendence(int rank, int max) {
        return new ItemEquipmentTranscendence(null, rank, 0, List.of(), 0, null, max, null);
    }

    private static EquipmentInstance instance(int level, int max, int value) {
        return new EquipmentInstance("instance", "account", "equipment", level, 2, 1, max, value, "created", "updated",
            List.of(new EquipmentStatRoll("roll", "ATTACK", "1", "2", 0)),
            List.of(new EquipmentEnchant("enchant", "instance", 0, "master", "effect", "ATTACK", "FLAT", 1.0D)),
            List.of(new EquipmentRune("rune", "instance", 0, "rune-item")));
    }
}
