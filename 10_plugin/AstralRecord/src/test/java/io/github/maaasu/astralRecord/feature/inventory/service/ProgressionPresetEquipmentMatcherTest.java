package io.github.maaasu.astralRecord.feature.inventory.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ProgressionPresetEquipmentMatcherTest {
    private record Held(UUID id, String item, int enhanceLevel) { }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/3-メソッド仕様/02_3-コマンド.md
     * 章・見出し: # 02_3-コマンド > ## 1. command メソッド仕様 > ### 戦闘検証用の進行プリセット
     * 検証契約: バッグが満杯でも装備中の同種2枠を入れ替えて再利用し、同枠一致を優先し、個体を重複割当しない。
     */
    @Test
    void swapsEquippedAccessoriesBeforeUsingBagCapacity() {
        Held first = new Held(UUID.fromString("00000000-0000-0000-0000-000000000001"), "talisman_a", 2);
        Held second = new Held(UUID.fromString("00000000-0000-0000-0000-000000000002"), "talisman_b", 3);
        Map<String, Held> current = new LinkedHashMap<>();
        current.put("TALISMAN_1", first);
        current.put("TALISMAN_2", second);
        Map<String, Held> desired = new LinkedHashMap<>();
        desired.put("TALISMAN_1", second);
        desired.put("TALISMAN_2", first);
        var swapped = match(current, desired);
        assertSame(second, swapped.get("TALISMAN_1"));
        assertSame(first, swapped.get("TALISMAN_2"));
        assertEquals(current.size(), swapped.size());

        Map<String, Held> sameItems = new LinkedHashMap<>();
        sameItems.put("TALISMAN_1", new Held(first.id(), "talisman_a", 2));
        sameItems.put("TALISMAN_2", new Held(second.id(), "talisman_a", 2));
        var unchanged = match(sameItems, sameItems);
        assertSame(sameItems.get("TALISMAN_1"), unchanged.get("TALISMAN_1"));
        assertSame(sameItems.get("TALISMAN_2"), unchanged.get("TALISMAN_2"));

        Held duplicate = new Held(first.id(), "talisman_a", 2);
        var repeated = match(Map.of("TALISMAN_1", first, "TALISMAN_2", duplicate),
            Map.of("TALISMAN_1", first, "TALISMAN_2", duplicate));
        assertEquals(1, repeated.size());
    }

    private static Map<String, Held> match(Map<String, Held> current, Map<String, Held> desired) {
        return ProgressionPresetEquipmentMatcher.reuse(current, desired.keySet(),
            (slot, candidate) -> candidate.item().equals(desired.get(slot).item())
                && candidate.enhanceLevel() == desired.get(slot).enhanceLevel(), Held::id);
    }
}
