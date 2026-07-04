package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobTabCompleterTest {

    @Test
    void spawnCompletionExcludesNpcIds() {
        MobService mobService = mock(MobService.class);
        MobSpawnerService spawnerService = mock(MobSpawnerService.class);
        NpcPlacementService npcPlacementService = mock(NpcPlacementService.class);
        MobTabCompleter completer = new MobTabCompleter(mobService, spawnerService, npcPlacementService);

        when(mobService.getLoadedMobIdsByCategory(List.of(MobCategory.ENEMY, MobCategory.BOSS)))
                .thenReturn(List.of("enemy_slime", "boss_dragon"));

        List<String> completions = completer.getPlayerCompletions(mock(AstPlayer.class), new String[]{"spawn", ""});

        assertEquals(List.of("enemy_slime", "boss_dragon"), completions);
    }

    @Test
    void npcPlaceCompletionOnlyReturnsNpcIds() {
        MobService mobService = mock(MobService.class);
        MobSpawnerService spawnerService = mock(MobSpawnerService.class);
        NpcPlacementService npcPlacementService = mock(NpcPlacementService.class);
        MobTabCompleter completer = new MobTabCompleter(mobService, spawnerService, npcPlacementService);

        when(mobService.getLoadedMobSelectorsByCategory(List.of(MobCategory.NPC)))
                .thenReturn(List.of("starter_shopkeeper", "始まりの商人", "starter_shopkeeper（始まりの商人）"));

        List<String> completions = completer.getPlayerCompletions(mock(AstPlayer.class), new String[]{"npc", "place", ""});

        assertEquals(
                List.of("starter_shopkeeper", "始まりの商人", "starter_shopkeeper（始まりの商人）"),
                completions
        );
    }

    @Test
    void npcRemoveCompletionOnlyReturnsPlacedNpcSelectors() {
        MobService mobService = mock(MobService.class);
        MobSpawnerService spawnerService = mock(MobSpawnerService.class);
        NpcPlacementService npcPlacementService = mock(NpcPlacementService.class);
        MobTabCompleter completer = new MobTabCompleter(mobService, spawnerService, npcPlacementService);

        when(npcPlacementService.getPlacedNpcIds()).thenReturn(List.of("starter_shopkeeper"));
        when(mobService.getLoadedMobSelectors(List.of("starter_shopkeeper")))
                .thenReturn(List.of("starter_shopkeeper", "始まりの商人", "starter_shopkeeper（始まりの商人）"));

        List<String> completions = completer.getPlayerCompletions(mock(AstPlayer.class), new String[]{"npc", "remove", ""});

        assertEquals(
                List.of("starter_shopkeeper", "始まりの商人", "starter_shopkeeper（始まりの商人）"),
                completions
        );
    }
}
