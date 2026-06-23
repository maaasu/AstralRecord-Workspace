package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleUseServiceTest {

    @Test
    void rollRewardsDoesNotPickSamePoolContentTwiceInSingleRoll() {
        LootPoolModel armorPool = new LootPoolModel(
            "starter_armor_pool",
            4,
            List.of(
                new LootContent("starter_helmet", 1, 1, 100.0),
                new LootContent("starter_chestplate", 1, 1, 100.0),
                new LootContent("starter_leggings", 1, 1, 100.0),
                new LootContent("starter_boots", 1, 1, 100.0)
            )
        );
        LootModel lootModel = new LootModel(
            1,
            "starter_armor_table",
            "Starter Armor",
            1,
            List.of(armorPool)
        );

        Map<String, Integer> rewards = BundleUseService.rollRewards(lootModel);

        assertEquals(Set.of(
            "starter_helmet",
            "starter_chestplate",
            "starter_leggings",
            "starter_boots"
        ), rewards.keySet());
        assertEquals(1, rewards.get("starter_helmet"));
        assertEquals(1, rewards.get("starter_chestplate"));
        assertEquals(1, rewards.get("starter_leggings"));
        assertEquals(1, rewards.get("starter_boots"));
    }

    @Test
    void rollRewardsResetsPickedContentsForEachRoll() {
        LootPoolModel pool = new LootPoolModel(
            "single_item_pool",
            1,
            List.of(new LootContent("iron_ingot", 1, 1, 100.0))
        );
        LootModel lootModel = new LootModel(
            1,
            "repeat_table",
            "Repeat Table",
            2,
            List.of(pool)
        );

        Map<String, Integer> rewards = BundleUseService.rollRewards(lootModel);

        assertEquals(Map.of("iron_ingot", 2), rewards);
    }
}
