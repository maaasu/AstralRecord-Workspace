package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.File;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorldServiceDesignTest extends MockBukkitTestBase {

    @Test
    void loadAllSortsMasterDataResolvesBukkitWorldAndAppliesRpgGameRules() {
        WorldRepository repository = mock(WorldRepository.class);
        WorldService service = new WorldService(repository, () -> new File("target/test-world-container"));
        World loadedWorld = server().addSimpleWorld("amber_field");
        WorldMasterData amber = world(
            "z_amber",
            "Amber Field",
            WorldType.OVERWORLD,
            "amber_field",
            new WorldSpawnLocation(12.5D, 70.0D, -8.25D, 90.0F, 15.0F)
        );
        WorldMasterData base = world(
            "a_base",
            "Base",
            WorldType.BASE,
            "not_loaded_base",
            WorldSpawnLocation.defaultLocation()
        );
        when(repository.findAll()).thenReturn(List.of(amber, base));

        int loaded = withPluginLogger(service::loadAll);

        assertEquals(2, loaded);
        assertEquals(List.of("a_base", "z_amber"), service.getAll().stream().map(WorldMasterData::id).toList());
        assertSame(loadedWorld, service.resolveLoadedWorld(amber));
        assertSame(amber, service.findByBukkitWorld(loadedWorld));

        Location spawnLocation = service.resolveSpawnLocation(amber);
        assertSame(loadedWorld, spawnLocation.getWorld());
        assertEquals(12.5D, spawnLocation.getX(), 0.0001D);
        assertEquals(70.0D, spawnLocation.getY(), 0.0001D);
        assertEquals(-8.25D, spawnLocation.getZ(), 0.0001D);
        assertEquals(90.0F, spawnLocation.getYaw(), 0.0001F);
        assertEquals(15.0F, spawnLocation.getPitch(), 0.0001F);

        assertFalse(loadedWorld.getGameRuleValue(GameRules.SPAWN_MOBS));
        assertFalse(loadedWorld.getGameRuleValue(GameRules.MOB_GRIEFING));
        assertTrue(loadedWorld.getGameRuleValue(GameRules.KEEP_INVENTORY));
        assertTrue(loadedWorld.getGameRuleValue(GameRules.IMMEDIATE_RESPAWN));
        assertFalse(loadedWorld.getGameRuleValue(GameRules.NATURAL_HEALTH_REGENERATION));
    }

    @Test
    void resolveSpawnLocationReturnsNullWhenWorldIsNotLoaded() {
        WorldRepository repository = mock(WorldRepository.class);
        WorldService service = new WorldService(repository, () -> new File("target/test-world-container"));
        WorldMasterData missing = world(
            "missing_world",
            "Missing World",
            WorldType.DUNGEON,
            "missing_world",
            WorldSpawnLocation.defaultLocation()
        );
        when(repository.findAll()).thenReturn(List.of(missing));

        withPluginLogger(service::loadAll);

        assertEquals(List.of(missing), service.getAll());
        assertNull(service.resolveLoadedWorld(missing));
        assertNull(service.resolveSpawnLocation(missing));
    }

    @Test
    void loadAllDoesNotApplyGameRulesToAutoLoadDisabledWorld() {
        WorldRepository repository = mock(WorldRepository.class);
        WorldService service = new WorldService(repository, () -> new File("target/test-world-container"));
        World loadedWorld = server().addSimpleWorld("manual_field");
        WorldMasterData manual = world(
            "manual_field",
            "Manual Field",
            WorldType.BOSS_FIELD,
            "manual_field",
            WorldSpawnLocation.defaultLocation(),
            false
        );
        when(repository.findAll()).thenReturn(List.of(manual));

        withPluginLogger(service::loadAll);

        assertEquals(List.of(manual), service.getAll());
        assertSame(manual, service.findByBukkitWorld(loadedWorld));
        assertTrue(loadedWorld.getGameRuleValue(GameRules.SPAWN_MOBS));
        assertTrue(loadedWorld.getGameRuleValue(GameRules.MOB_GRIEFING));
    }

    @Test
    void pendingRuntimeWorldIsManagedBeforeUuidRegistrationWithoutFileLookup() {
        WorldRepository repository = mock(WorldRepository.class);
        WorldService service = new WorldService(repository, () -> new File("target/test-world-container"));
        WorldMasterData fieldData = world(
                "twilight_colossus_field",
                "黄昏の巨像フィールド",
                WorldType.BOSS_FIELD,
                "boss_template",
                WorldSpawnLocation.defaultLocation(),
                false
        );
        World runtimeWorld = server().addSimpleWorld("runtime_pending");
        when(repository.findAll()).thenReturn(List.of(fieldData));
        withPluginLogger(service::loadAll);

        service.prepareWorldLoad(runtimeWorld.getName(), fieldData);

        assertSame(fieldData, service.findByBukkitWorld(runtimeWorld));
        assertEquals("黄昏の巨像フィールド", service.resolveDisplayName(runtimeWorld));
        assertThrows(IllegalStateException.class, () -> service.prepareWorldLoad(runtimeWorld.getName(), fieldData));

        service.cancelWorldLoad(runtimeWorld.getName(), fieldData);

        assertNull(service.findByBukkitWorld(runtimeWorld));
    }

    @Test
    void runtimeWorldRegistrationsSupportParallelInstancesAndSurviveMasterReload() {
        WorldRepository repository = mock(WorldRepository.class);
        WorldService service = new WorldService(repository, () -> new File("target/test-world-container"));
        WorldMasterData fieldData = world(
                "twilight_colossus_field",
                "黄昏の巨像フィールド",
                WorldType.BOSS_FIELD,
                "boss_template",
                WorldSpawnLocation.defaultLocation(),
                false
        );
        WorldMasterData reloadedFieldData = world(
                "twilight_colossus_field",
                "黄昏の巨像フィールド・再読込",
                WorldType.BOSS_FIELD,
                "boss_template",
                WorldSpawnLocation.defaultLocation(),
                false
        );
        World firstRuntimeWorld = server().addSimpleWorld("runtime_first");
        World secondRuntimeWorld = server().addSimpleWorld("runtime_second");
        var repositoryResults = when(repository.findAll()).thenReturn(List.of(fieldData));
        repositoryResults.thenReturn(List.of());
        repositoryResults.thenReturn(List.of(reloadedFieldData));
        withPluginLogger(service::loadAll);

        service.registerRuntimeWorld(firstRuntimeWorld, fieldData);
        service.registerRuntimeWorld(secondRuntimeWorld, fieldData);

        assertSame(fieldData, service.findByBukkitWorld(firstRuntimeWorld));
        assertSame(fieldData, service.findByBukkitWorld(secondRuntimeWorld));
        assertEquals("黄昏の巨像フィールド", service.resolveDisplayName(firstRuntimeWorld));

        withPluginLogger(service::loadAll);

        assertNull(service.findByBukkitWorld(firstRuntimeWorld));
        assertNull(service.findByBukkitWorld(secondRuntimeWorld));
        assertEquals("黄昏の巨像フィールド", service.resolveDisplayName(firstRuntimeWorld));

        withPluginLogger(service::loadAll);

        assertSame(reloadedFieldData, service.findByBukkitWorld(firstRuntimeWorld));
        assertSame(reloadedFieldData, service.findByBukkitWorld(secondRuntimeWorld));
        assertEquals("黄昏の巨像フィールド・再読込", service.resolveDisplayName(firstRuntimeWorld));

        service.unregisterRuntimeWorld(firstRuntimeWorld);

        assertNull(service.findByBukkitWorld(firstRuntimeWorld));
        assertSame(reloadedFieldData, service.findByBukkitWorld(secondRuntimeWorld));
    }

    @Test
    void runtimeBossWorldWithBlankMasterNameUsesJapaneseFallbackInsteadOfInternalName() {
        WorldRepository repository = mock(WorldRepository.class);
        WorldService service = new WorldService(repository, () -> new File("target/test-world-container"));
        WorldMasterData fieldData = world(
                "blank_boss_field",
                "",
                WorldType.BOSS_FIELD,
                "boss_template",
                WorldSpawnLocation.defaultLocation(),
                false
        );
        World runtimeWorld = server().addSimpleWorld(
                "plugins/AstralRecord/_world_instances/boss_field/internal-path"
        );
        when(repository.findAll()).thenReturn(List.of(fieldData));
        withPluginLogger(service::loadAll);

        service.registerRuntimeWorld(runtimeWorld, fieldData);

        assertEquals("ボスフィールド", service.resolveDisplayName(runtimeWorld));
    }

    private int withPluginLogger(IntSupplier action) {
        try (MockedStatic<AstralRecord> astralRecord = Mockito.mockStatic(AstralRecord.class)) {
            AstralRecord plugin = mock(AstralRecord.class);
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            return action.getAsInt();
        }
    }

    private WorldMasterData world(
        String id,
        String displayName,
        WorldType worldType,
        String baseWorldPath,
        WorldSpawnLocation spawnLocation
    ) {
        return world(id, displayName, worldType, baseWorldPath, spawnLocation, true);
    }

    private WorldMasterData world(
        String id,
        String displayName,
        WorldType worldType,
        String baseWorldPath,
        WorldSpawnLocation spawnLocation,
        boolean autoLoad
    ) {
        return new WorldMasterData(
            1,
            id,
            displayName,
            worldType,
            baseWorldPath,
            "world_instances",
            autoLoad,
            false,
            0,
            false,
            false,
            false,
            true,
            spawnLocation,
            id,
            null,
            null
        );
    }
}
