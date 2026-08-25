package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemRune;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneTargetMatcherTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.5 `ItemRune`
     * 検証契約: ルーンの対象slotと対象tagは別軸で評価し、両方指定時はAND、各配列内はORで判定する。
     */
    @Test
    void matchesSlotAndOptionalTagConditions() {
        ItemEquipment sword = equipment(ItemEquipmentSlot.WEAPON, "SWORD");
        ItemEquipment bow = equipment(ItemEquipmentSlot.WEAPON, "BOW");
        ItemEquipment lowerCaseBow = equipment(ItemEquipmentSlot.WEAPON, "bow");

        ItemRune allWeapons = new ItemRune(List.of("WEAPON"), 0, List.of());
        ItemRune swordsOnly = new ItemRune(List.of("WEAPON"), 0, List.of(), List.of("SWORD"));
        ItemRune anySlotSwords = new ItemRune(List.of("ANY"), 0, List.of(), List.of("SWORD"));
        ItemRune bowOnly = new ItemRune(List.of("WEAPON"), 0, List.of(), List.of("BOW"));

        assertTrue(RuneTargetMatcher.matches(allWeapons, sword));
        assertTrue(RuneTargetMatcher.matches(allWeapons, bow));
        assertTrue(RuneTargetMatcher.matches(swordsOnly, sword));
        assertFalse(RuneTargetMatcher.matches(swordsOnly, bow));
        assertTrue(RuneTargetMatcher.matches(anySlotSwords, sword));
        assertFalse(RuneTargetMatcher.matches(bowOnly, lowerCaseBow));
    }

    private static ItemEquipment equipment(ItemEquipmentSlot slot, String tag) {
        return new ItemEquipment(
            slot,
            ItemEquipmentHandType.ONE,
            tag,
            0,
            List.of(),
            null,
            List.of(),
            null,
            null,
            null,
            null,
            List.of());
    }
}
