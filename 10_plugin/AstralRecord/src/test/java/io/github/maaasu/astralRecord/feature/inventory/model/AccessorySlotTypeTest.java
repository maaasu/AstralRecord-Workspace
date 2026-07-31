package io.github.maaasu.astralRecord.feature.inventory.model;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessorySlotTypeTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 5. アクセサリスロット種別
     * 検証契約: offhand1・amulet1・talisman2・charm3・core1・relic2を連続index 1〜10へ割り当てる。
     */
    @Test
    void definesRequestedSlotCountsAndContinuousIndexes() {
        Set<Integer> indexes = Arrays.stream(AccessorySlotType.values())
            .map(AccessorySlotType::getSlotIndex)
            .collect(Collectors.toSet());

        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), indexes);
        assertEquals(1, countSlots("AMULET"));
        assertEquals(2, countSlots("TALISMAN"));
        assertEquals(3, countSlots("CHARM"));
        assertEquals(1, countSlots("CORE"));
        assertEquals(2, countSlots("RELIC"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 5. アクセサリスロット種別
     * 検証契約: slot indexとequipment tagを明示対応だけで解決し不明値へunsafe fallbackしない。
     */
    @Test
    void resolvesSlotAndEquipmentTagWithoutUnsafeFallback() {
        assertSame(AccessorySlotType.OFF_HAND, AccessorySlotType.fromSlotIndex(1));
        assertSame(AccessorySlotType.RELIC_2, AccessorySlotType.fromSlotIndex(10));
        assertNull(AccessorySlotType.fromSlotIndex(0));
        assertNull(AccessorySlotType.fromSlotIndex(11));
        assertSame(AccessorySlotType.TALISMAN_1, AccessorySlotType.fromEquipmentTag("talisman"));
        assertTrue(AccessorySlotType.CHARM_2.matchesEquipmentTag(" CHARM "));
        assertFalse(AccessorySlotType.CHARM_2.matchesEquipmentTag("RELIC"));
        assertNull(AccessorySlotType.fromEquipmentTag("NECKLACE"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 5. アクセサリスロット種別
     * 検証契約: OFF_HAND subweaponを種類別accessory slotから分離する。
     */
    @Test
    void separatesSubweaponFromTypedAccessory() {
        assertSame(EquipmentType.OFF_HAND, EquipmentType.fromItemEquipmentSlot(ItemEquipmentSlot.SUBWEAPON));
        assertSame(EquipmentType.UNSUPPORTED, EquipmentType.fromItemEquipmentSlot(ItemEquipmentSlot.ACCESSORY));
    }

    private long countSlots(String tag) {
        return Arrays.stream(AccessorySlotType.values())
            .filter(type -> type.matchesEquipmentTag(tag))
            .count();
    }
}
