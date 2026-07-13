package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobDropServiceTest {

    @Test
    void rollPreservesConfiguredRateForResultPresentation() {
        MobDropConfig drops = new MobDropConfig(
            10,
            null,
            List.of(new MobDropItem("rare_item", 100.0D, "2", false, false)),
            null
        );

        MobDropResult result = new MobDropService().roll(drops, null);

        assertEquals(1, result.items().size());
        assertEquals("rare_item", result.items().getFirst().itemId());
        assertEquals(2, result.items().getFirst().amount());
        assertEquals(100.0D, result.items().getFirst().dropRate());
    }

    @Test
    void rollAcceptsDocumentedTildeAmountRange() {
        MobDropConfig drops = new MobDropConfig(
            0,
            null,
            List.of(new MobDropItem("boss_material", 100.0D, "2~4", false, false)),
            null
        );

        MobDropResult result = new MobDropService().roll(drops, null);

        assertEquals(1, result.items().size());
        assertTrue(result.items().getFirst().amount() >= 2);
        assertTrue(result.items().getFirst().amount() <= 4);
    }

    @Test
    void rollAddsLoadedLootTableRewards() {
        LootService lootService = mock(LootService.class);
        when(lootService.getLoaded("field_table")).thenReturn(new LootModel(
            1,
            "field_table",
            "field_table",
            1,
            List.of(new LootPoolModel(
                "field_pool",
                1,
                List.of(new LootContent("table_reward", 2, 2, 100.0D))
            ))
        ));
        MobDropConfig drops = new MobDropConfig(0, null, List.of(), "field_table");

        MobDropResult result = new MobDropService(lootService).roll(drops, null);

        assertEquals(1, result.items().size());
        assertEquals("table_reward", result.items().getFirst().itemId());
        assertEquals(2, result.items().getFirst().amount());
        assertEquals(100.0D, result.items().getFirst().dropRate());
    }

    @Test
    void rollAppliesLuckOnlyToAffectedDirectDrops() {
        AstPlayer killer = mock(AstPlayer.class);
        when(killer.getStatusSnapshot()).thenReturn(new StatusSnapshot(
            Map.of(StatusType.LUCK, new StatusValue(0.0D, 2000.0D)),
            0.0D,
            0.0D,
            0.0D,
            0.0D,
            0L,
            LocalDateTime.now()
        ));
        MobDropConfig drops = new MobDropConfig(
            0,
            null,
            List.of(
                new MobDropItem("affected", 0.0D, "1", true, false),
                new MobDropItem("unaffected", 0.0D, "1", false, false)
            ),
            null
        );

        MobDropResult result = new MobDropService().roll(drops, killer);

        assertEquals(1, result.items().size());
        assertEquals("affected", result.items().getFirst().itemId());
    }
}
