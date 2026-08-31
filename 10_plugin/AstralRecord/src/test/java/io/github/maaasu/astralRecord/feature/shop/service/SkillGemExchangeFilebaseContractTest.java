package io.github.maaasu.astralRecord.feature.shop.service;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillGemExchangeFilebaseContractTest {

    private static final Map<Integer, ExpectedEntry> EXPECTED_ENTRIES = Map.ofEntries(
            entry(0, "item:00_skill_gem_adventurer_meditation", 1),
            entry(1, "item:00_skill_gem_adventurer_astral_edge", 1),
            entry(2, "item:00_skill_gem_adventurer_smash", 1),
            entry(3, "item:00_skill_gem_adventurer_blast_arrow", 1),
            entry(4, "item:00_skill_gem_adventurer_quick_shot", 1),
            entry(5, "item:00_skill_gem_adventurer_lightning_bolt", 1),
            entry(6, "item:00_skill_gem_adventurer_mana_burst", 1),
            entry(7, "item:00_skill_gem_swordsman_shield_drain", 2),
            entry(8, "item:00_skill_gem_swordsman_challenging_roar", 2),
            entry(9, "item:00_skill_gem_swordsman_flame_rush", 3),
            entry(10, "item:00_skill_gem_swordsman_bastion_strike", 3),
            entry(11, "item:00_skill_gem_administrator_shield_recharge", 3),
            entry(12, "item:00_skill_gem_swordsman_last_shield", 3),
            entry(13, "item:00_skill_gem_swordsman_shield_activate", 3),
            entry(14, "item:00_skill_gem_hunter_arrow_rain", 2),
            entry(15, "item:00_skill_gem_hunter_fade_shot", 2),
            entry(16, "item:00_skill_gem_hunter_heal_arrow", 3),
            entry(17, "item:00_skill_gem_hunter_crash_arrow", 3),
            entry(18, "item:00_skill_gem_hunter_spell_step", 3),
            entry(21, "item:00_skill_gem_mage_fireball", 2),
            entry(22, "item:00_skill_gem_mage_heal_aura", 2),
            entry(23, "item:00_skill_gem_mage_sparking", 3),
            entry(24, "item:00_skill_gem_mage_frost_blizzard", 3),
            entry(25, "item:00_skill_gem_mage_arcane_flow", 3),
            entry(26, "item:00_skill_gem_mage_frost_ball", 3)
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_2-ユースケース.md
     * 章・見出し: # 20_2-ユースケース > ## UC-20-06 星晶錬成を利用する > ### skill_gem_exchange の職業別配置
     * 検証契約: skill_gem_exchangeは指定された25個のスキルジェムをslot 0〜27の4行へ配置し、空きslotと各交換素材・数量を固定する。
     */
    @Test
    void skillGemExchangeUsesCareerRowsAndRawGemCosts() {
        YamlConfiguration shop = YamlConfiguration.loadConfiguration(
                repositoryRoot().resolve("40_filebase/45.features.shop/v1.skill_gem_exchange.yml").toFile()
        );

        assertEquals("skill_gem_exchange", shop.getString("id"));
        List<Map<?, ?>> items = shop.getMapList("items");
        assertEquals(EXPECTED_ENTRIES.size(), items.size());

        Map<Integer, Map<?, ?>> itemsBySlot = new HashMap<>();
        for (Map<?, ?> item : items) {
            int slot = integerValue(item.get("slot"));
            assertTrue(EXPECTED_ENTRIES.containsKey(slot), "unexpected skill gem slot: " + slot);
            assertTrue(itemsBySlot.put(slot, item) == null, "duplicate skill gem slot: " + slot);

            ExpectedEntry expected = EXPECTED_ENTRIES.get(slot);
            assertEquals(1, integerValue(item.get("page")), "page at slot " + slot);
            assertEquals("skill_gem", item.get("category"), "category at slot " + slot);
            assertEquals(1, integerValue(item.get("amount")), "amount at slot " + slot);
            assertEquals(expected.itemRef(), reference(item.get("itemId")), "item at slot " + slot);

            Object requiredItems = item.get("requiredItems");
            assertTrue(requiredItems instanceof List<?>, "requiredItems at slot " + slot);
            List<?> costs = (List<?>) requiredItems;
            assertEquals(1, costs.size(), "cost count at slot " + slot);
            assertTrue(costs.getFirst() instanceof Map<?, ?>, "cost at slot " + slot);
            Map<?, ?> cost = (Map<?, ?>) costs.getFirst();
            assertEquals("material", cost.get("category"), "cost category at slot " + slot);
            assertEquals("item:skill_gem_raw", reference(cost.get("itemId")), "cost item at slot " + slot);
            assertEquals(expected.costAmount(), integerValue(cost.get("amount")), "cost amount at slot " + slot);
        }

        assertEquals(EXPECTED_ENTRIES.keySet(), itemsBySlot.keySet());
        Set<Integer> emptySlots = new HashSet<>(IntStream.rangeClosed(0, 27).boxed().toList());
        emptySlots.removeAll(itemsBySlot.keySet());
        assertEquals(Set.of(19, 20, 27), emptySlots);
    }

    private static Map.Entry<Integer, ExpectedEntry> entry(int slot, String itemRef, int costAmount) {
        return Map.entry(slot, new ExpectedEntry(itemRef, costAmount));
    }

    private static int integerValue(Object value) {
        assertTrue(value instanceof Number, "expected numeric YAML value but got: " + value);
        return ((Number) value).intValue();
    }

    private static String reference(Object value) {
        assertTrue(value instanceof Map<?, ?>, "expected ref map but got: " + value);
        Object ref = ((Map<?, ?>) value).get("ref");
        assertTrue(ref instanceof String, "expected string ref but got: " + ref);
        return (String) ref;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("40_filebase"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("repository root was not found from the test directory");
    }

    private record ExpectedEntry(String itemRef, int costAmount) {
    }
}
