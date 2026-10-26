package io.github.maaasu.astralRecord.feature.worldplacement;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.NpcPlacement;
import io.github.maaasu.astralRecord.feature.mob.repository.NpcPlacementRepository;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.repository.AccountWaystoneRepository;
import io.github.maaasu.astralRecord.feature.teleporter.repository.WaystoneDefinitionRepository;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldPlacementRuntimeReloadTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/43-world-placement/43_3-配置スナップショット.md
     * 章・見出し: # 配置スナップショット > ## 非同期取得と公開
     * 検証契約: 同じスポナー座標の再公開と定義再読込では既存の生存Mobを保持し、未解決定義の古い探索は破棄する。
     */
    @Test
    void retainsTrackedAliveMobAndDropsInvalidPendingSearchOnReload() throws Exception {
        Plugin plugin = mockPlugin();
        MobService mobs = mock(MobService.class);
        MobSpawnerService service = new MobSpawnerService(plugin, mobs, mock(PlayerRegionService.class),
            mock(MobSpawnerDefinitionRepository.class), mock(MobSpawnerLocationRepository.class));
        MobSpawnerLocation location = new MobSpawnerLocation("wolf", "world", 1, 64, 2);
        service.replaceMasterDataSnapshot(new MobSpawnerService.MasterDataSnapshot(List.of(), List.of(location)));
        UUID aliveId = UUID.randomUUID();
        when(mobs.getInstance(aliveId)).thenReturn(mock(MobInstance.class));
        tracked(service).get(location.locationKey()).add(aliveId);
        Object pending = newPending(service.getLocations().iterator().next());
        pending(service).put(location.locationKey(), pending);

        service.replacePlacementSnapshot(List.of(new MobSpawnerLocation("wolf", "world", 1, 64, 2)));

        assertEquals(1, aliveCount(service, location.locationKey()));
        assertFalse(pending(service).containsKey(location.locationKey()));

        service.replaceDefinitionsKeepingPlacements(
            new MobSpawnerService.MasterDataSnapshot(List.of(), List.of()));

        assertEquals(1, aliveCount(service, location.locationKey()));
        assertFalse(pending(service).containsKey(location.locationKey()));

        service.replacePlacementSnapshot(List.of());
        assertFalse(tracked(service).containsKey(location.locationKey()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/43-world-placement/43_3-配置スナップショット.md
     * 章・見出し: # 配置スナップショット > ## 非同期取得と公開
     * 検証契約: APIモードの非同期マスター準備は公開中配置を列挙せず、メインスレッドのロールバック取得は全配置を返す。
     */
    @Test
    void asyncMasterPreparationDoesNotReadLiveApiPlacementMaps() {
        Plugin plugin = mockPlugin();
        MobService mobs = mock(MobService.class);
        MobSpawnerLocationRepository spawnerRepository = mock(MobSpawnerLocationRepository.class);
        NpcPlacementRepository npcRepository = mock(NpcPlacementRepository.class);
        WaystoneDefinitionRepository waystoneRepository = mock(WaystoneDefinitionRepository.class);
        when(spawnerRepository.isApiMode()).thenReturn(true);
        when(npcRepository.isApiMode()).thenReturn(true);
        when(waystoneRepository.isApiMode()).thenReturn(true);
        MobSpawnerDefinitionRepository definitionRepository = mock(MobSpawnerDefinitionRepository.class);
        when(definitionRepository.findAll()).thenReturn(List.of());
        MobSpawnerService spawners = new MobSpawnerService(plugin, mobs, mock(PlayerRegionService.class),
            definitionRepository, spawnerRepository);
        NpcPlacementService npcs = new NpcPlacementService(plugin, mobs, npcRepository);
        TeleporterService waystones = new TeleporterService(plugin, waystoneRepository,
            mock(AccountWaystoneRepository.class));
        spawners.replacePlacementSnapshot(List.of(new MobSpawnerLocation("wolf", "world", 1, 64, 2)));
        npcs.replacePlacementSnapshot(List.of(new NpcPlacement("guide", "world", 3, 64, 4, 0, 0)));
        waystones.replaceDefinitionSnapshot(List.of(new WaystoneDefinition("ws-1", "Base", "world", 5, 64, 6,
            0, 0, false, 0, null, Instant.EPOCH, "system")));

        assertTrue(spawners.loadMasterDataSnapshot().locations().isEmpty());
        assertTrue(npcs.loadPlacementSnapshot().isEmpty());
        assertTrue(waystones.loadDefinitionSnapshot().isEmpty());
        assertEquals(1, npcs.snapshotPublishedPlacements().size());
        assertEquals(1, waystones.snapshotPublishedDefinitions().size());
    }

    private static Plugin mockPlugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.namespace()).thenReturn("astralrecord");
        return plugin;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<UUID>> tracked(MobSpawnerService service) throws Exception {
        Field field = MobSpawnerService.class.getDeclaredField("spawnedByLocation");
        field.setAccessible(true);
        return (Map<String, Set<UUID>>) field.get(service);
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> pending(MobSpawnerService service) throws Exception {
        Field field = MobSpawnerService.class.getDeclaredField("pendingSpawns");
        field.setAccessible(true);
        return (LinkedHashMap<String, Object>) field.get(service);
    }

    private static Object newPending(MobSpawnerLocation location) throws Exception {
        Class<?> type = Class.forName(MobSpawnerService.class.getName() + "$SpawnAttempt");
        Constructor<?> constructor = type.getDeclaredConstructor(MobSpawnerLocation.class);
        constructor.setAccessible(true);
        return constructor.newInstance(location);
    }

    private static int aliveCount(MobSpawnerService service, String key) throws Exception {
        Method method = MobSpawnerService.class.getDeclaredMethod("cleanupTracked", String.class);
        method.setAccessible(true);
        return (int) method.invoke(service, key);
    }
}
