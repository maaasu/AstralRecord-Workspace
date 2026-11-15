package io.github.maaasu.astralRecord.feature.account.repository;

import io.github.maaasu.astralRecord.feature.account.model.ProgressionPreset;
import io.github.maaasu.astralRecord.feature.item.model.EnchantEntry;
import io.github.maaasu.astralRecord.feature.item.model.EnchantEquipmentType;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.EnchantTarget;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.service.PresetEquipmentEnchant;
import io.github.maaasu.astralRecord.infrastructure.api.RuntimeFilebaseSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressionPresetEnchantTest {
    private static final String ITEM_ID = "00000000-0000-0000-0000-000000000001";

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### 戦闘検証用の進行プリセット
     * 検証契約: 付与値の元の精度を保持し、許容外の桁数・範囲・重複する効果IDを拒否する。
     */
    @Test
    void readsDecimalValuesWithoutRoundingAndRejectsInvalidPresetRows() {
        var row = "{enchantMasterId: 'enchant:basic', effectId: damage, value: %s}";
        var enchant = readEquipment("[" + row.formatted("'99999999999.9999'") + "]").enchants().getFirst();
        assertEquals(new BigDecimal("99999999999.9999"), enchant.value());
        assertEquals("enchant:basic", enchant.enchantMasterId());
        assertEquals(2, readEquipment("[]").enhanceLevel());
        assertTrue(readEquipment("[]").enchants().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> readEquipment("[" + row.formatted("1.125") + "]"));
        assertThrows(IllegalArgumentException.class, () -> readEquipment("[" + row.formatted("'1.000000000000000000000000000001'") + "]"));
        assertThrows(IllegalArgumentException.class, () -> readEquipment("[" + row.formatted("'100000000000.0001'") + "]"));
        assertThrows(IllegalArgumentException.class, () ->
            readEquipment("[" + row.formatted("'1'") + ", " + row.formatted("'2'").replace("damage", "DAMAGE") + "]"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### 戦闘検証用の進行プリセット
     * 検証契約: マスターの装備区分と値を照合し、値や枠が違う個体は変更せず、一致する既存個体の固定状態を保持する。
     */
    @Test
    void resolvesTargetAndRangeAndPreservesMatchingLockedEquipment() {
        var attack = new EnchantEntry("damage", "ATTACK", "FLAT", "1~4", 1);
        var strength = new EnchantEntry("strength", "STRENGTH", "FLAT", "2", 1);
        var master = new EnchantMaster(1, "basic",
            List.of(new EnchantTarget(EnchantEquipmentType.WEAPON, List.of(attack, strength))));
        var value = new BigDecimal("4.0000");
        var effect = PresetEquipmentEnchant.resolve(master, ItemEquipmentSlot.WEAPON, "damage", value);
        var second = PresetEquipmentEnchant.resolve(master, ItemEquipmentSlot.SUBWEAPON, "strength", new BigDecimal("2.0"));
        assertNotNull(effect);
        assertNotNull(second);
        assertNull(PresetEquipmentEnchant.resolve(master, ItemEquipmentSlot.CHEST, "damage", value));
        assertNull(PresetEquipmentEnchant.resolve(master, ItemEquipmentSlot.WEAPON, "damage", new BigDecimal("4.0001")));
        assertNull(PresetEquipmentEnchant.resolve(master, ItemEquipmentSlot.WEAPON, "strength", new BigDecimal("1.9999")));

        var unlocked = effect.attach(ITEM_ID, 0);
        assertFalse(unlocked.isLocked());
        assertEquals(0, unlocked.getSlotIndex());
        assertEquals(ITEM_ID, unlocked.getEquipmentInstanceId());
        UUID.fromString(unlocked.getEnchantId());

        var locked = new EquipmentEnchant(unlocked.getEnchantId(), ITEM_ID, 0, "basic", "damage", "ATTACK", "FLAT", 4, true);
        var secondSlot = second.attach(ITEM_ID, 1);
        assertTrue(PresetEquipmentEnchant.matchesAll(List.of(effect, second), List.of(secondSlot, locked)));
        assertFalse(PresetEquipmentEnchant.matchesAll(List.of(second, effect), List.of(locked, secondSlot)));
        var different = new PresetEquipmentEnchant("basic", "damage", "ATTACK", "FLAT", 3.9999);
        assertFalse(PresetEquipmentEnchant.matchesAll(List.of(different, second), List.of(locked, secondSlot)));
        assertTrue(locked.isLocked());
        assertEquals(4, locked.getValue());
    }

    private static ProgressionPreset.Equipment readEquipment(String enchants) {
        String raw = """
            schemaVersion: 1
            type: PROGRESSION_PRESET
            id: probe
            name: Probe
            classId: fighter
            buildId: default
            levels:
              - level: 1
                classLevel: 1
                nodes: []
                equipment:
                  - slot: MAIN_HAND
                    item: "item:sword"
                    enhanceLevel: 2
                    enchants: %s
                skills: [{slot: 1, skill: "skill:strike"}]
            """.formatted(enchants);
        var snapshot = new RuntimeFilebaseSnapshot(List.of(
            new RuntimeFilebaseSnapshot.Entry("90.shared.progression_preset/v1.probe.yml", raw)));
        return ProgressionPresetRepository.load(snapshot).getFirst().atLevel(1).equipment().getFirst();
    }
}
