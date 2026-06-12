package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
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
        MobTabCompleter completer = new MobTabCompleter(mobService, spawnerService);

        when(mobService.getLoadedMobIdsByCategory(List.of(MobCategory.ENEMY, MobCategory.BOSS)))
                .thenReturn(List.of("enemy_slime", "boss_dragon"));

        List<String> completions = completer.getPlayerCompletions(mock(AstPlayer.class), new String[]{"spawn", ""});

        assertEquals(List.of("enemy_slime", "boss_dragon"), completions);
    }

    @Test
    void npcPlaceCompletionOnlyReturnsNpcIds() {
        MobService mobService = mock(MobService.class);
        MobSpawnerService spawnerService = mock(MobSpawnerService.class);
        MobTabCompleter completer = new MobTabCompleter(mobService, spawnerService);

        when(mobService.getLoadedMobIdsByCategory(List.of(MobCategory.NPC)))
                .thenReturn(List.of("npc_shopkeeper"));

        List<String> completions = completer.getPlayerCompletions(mock(AstPlayer.class), new String[]{"npc", "place", ""});

        assertEquals(List.of("npc_shopkeeper"), completions);
    }
}
