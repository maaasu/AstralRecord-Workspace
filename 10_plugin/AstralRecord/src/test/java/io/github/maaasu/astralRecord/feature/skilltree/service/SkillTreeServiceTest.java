package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeEdge;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeStatusDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTreeServiceTest extends MockBukkitTestBase {

    @BeforeEach
    void setPluginInstance() throws Exception {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AstralRecordTest"));
        Field instanceField = AstralRecord.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, plugin);
    }

    @AfterEach
    void clearPluginInstance() throws Exception {
        Field instanceField = AstralRecord.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    @Test
    void unlockNodeRefreshesDerivedStateByDelta() {
        SkillTreeNodeRepository nodeRepository = mock(SkillTreeNodeRepository.class);
        SkillTreeStructureRepository structureRepository = mock(SkillTreeStructureRepository.class);
        SkillTreePlayerStateRepository playerStateRepository = mock(SkillTreePlayerStateRepository.class);
        SkillTreeService service = newService(nodeRepository, structureRepository, playerStateRepository);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        service.setPassiveSkillService(passiveSkillService);

        SkillTreeNodeDefinition root = new SkillTreeNodeDefinition(
                "root-node",
                "root-pos",
                "Root",
                Material.STONE,
                List.of(),
                List.of("root"),
                List.of("skill.root"),
                List.of(new SkillTreeNodeStatusDefinition(StatusType.MAX_HEALTH, StatusModifierType.FLAT, 10.0D))
        );
        SkillTreeNodeDefinition child = new SkillTreeNodeDefinition(
                "child-node",
                "child-pos",
                "Child",
                Material.DIAMOND,
                List.of(),
                List.of(),
                List.of("skill.child"),
                List.of(new SkillTreeNodeStatusDefinition(StatusType.ATTACK, StatusModifierType.SCALAR, 0.10D))
        );

        when(nodeRepository.findAll()).thenReturn(List.of(root, child));
        when(structureRepository.load()).thenReturn(new SkillTreeStructureRepository.StructureSnapshot(
                List.of(
                        new SkillTreePosition("root-pos", "world", 0, 64, 0),
                        new SkillTreePosition("child-pos", "world", 1, 64, 0)
                ),
                List.of(new SkillTreeEdge("root-pos", "child-pos"))
        ));

        assertEquals(2, service.loadAll());

        AstPlayer astPlayer = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        AccountModel account = accountModel(AccountMode.PLAYER);
        when(astPlayer.getAccount()).thenReturn(account);
        when(astPlayer.getBukkit()).thenReturn(bukkitPlayer);

        SkillTreePlayerState state = new SkillTreePlayerState(account.getUuid(), 1, Set.of("root-node"));
        stateField(service).put(account.getUuid(), state);

        assertTrue(service.unlockNode(astPlayer, child));
        assertEquals(Set.of("skill.root", "skill.child"), service.getUnlockedSkillIds(astPlayer));
        assertEquals(10.0D, service.getStatusBonus(astPlayer, StatusType.MAX_HEALTH, 100.0D));
        assertEquals(10.0D, service.getStatusBonus(astPlayer, StatusType.ATTACK, 100.0D));
        verify(passiveSkillService).reconcileSkillOwnershipDelta(astPlayer, Set.of("skill.child"), Set.of(), true);
    }

    @Test
    void saveDirtyAsyncDebouncesRepositorySaveUntilDueTime() throws Exception {
        SkillTreeNodeRepository nodeRepository = mock(SkillTreeNodeRepository.class);
        SkillTreeStructureRepository structureRepository = mock(SkillTreeStructureRepository.class);
        SkillTreePlayerStateRepository playerStateRepository = mock(SkillTreePlayerStateRepository.class);
        SkillTreeService service = newService(nodeRepository, structureRepository, playerStateRepository);

        UUID accountId = UUID.randomUUID();
        SkillTreePlayerState state = new SkillTreePlayerState(accountId, 2, Set.of("node-a"));
        stateField(service).put(accountId, state);

        service.markDirty(state);
        service.saveDirtyAsync();
        verify(playerStateRepository, never()).save(any());

        @SuppressWarnings("unchecked")
        Map<UUID, Long> dueAtMap = (Map<UUID, Long>) field(service, "dirtyPlayerStateDueAtMillis").get(service);
        dueAtMap.put(accountId, System.currentTimeMillis() - 1L);

        service.saveDirtyAsync();
        server().getScheduler().performTicks(1);
        server().getScheduler().waitAsyncTasksFinished();
        verify(playerStateRepository, times(1)).save(argThat(saved ->
                saved.accountId().equals(accountId)
                        && saved.skillPoints() == 2
                        && saved.unlockedNodeIds().equals(Set.of("node-a"))
        ));
    }

    @Test
    void viewOptionsCanBeUpdatedAndResetPerPlayer() {
        SkillTreeService service = newService(
                mock(SkillTreeNodeRepository.class),
                mock(SkillTreeStructureRepository.class),
                mock(SkillTreePlayerStateRepository.class)
        );
        Player player = server().addPlayer();

        assertEquals(48, service.viewOptions(player).viewDistance());
        assertEquals(SkillTreeService.SkillTreeEdgeDisplayMode.CONNECTED, service.viewOptions(player).edgeDisplayMode());

        service.updateViewDistance(player, 32);
        service.updateEdgeDisplayMode(player, SkillTreeService.SkillTreeEdgeDisplayMode.ALL);

        assertEquals(32, service.viewOptions(player).viewDistance());
        assertEquals(SkillTreeService.SkillTreeEdgeDisplayMode.ALL, service.viewOptions(player).edgeDisplayMode());

        service.resetViewOptions(player);
        assertEquals(48, service.viewOptions(player).viewDistance());
        assertEquals(SkillTreeService.SkillTreeEdgeDisplayMode.CONNECTED, service.viewOptions(player).edgeDisplayMode());
    }

    @Test
    void updateViewDistanceRejectsOutOfRangeValue() {
        SkillTreeService service = newService(
                mock(SkillTreeNodeRepository.class),
                mock(SkillTreeStructureRepository.class),
                mock(SkillTreePlayerStateRepository.class)
        );
        Player player = server().addPlayer();

        assertThrows(IllegalArgumentException.class, () -> service.updateViewDistance(player, 8));
        assertThrows(IllegalArgumentException.class, () -> service.updateViewDistance(player, 128));
    }

    @Test
    void nodeLabelDetailChangesByDistance() {
        SkillTreeService service = newService(
                mock(SkillTreeNodeRepository.class),
                mock(SkillTreeStructureRepository.class),
                mock(SkillTreePlayerStateRepository.class)
        );
        Player player = server().addPlayer();
        player.teleport(new Location(player.getWorld(), 0.0D, 64.0D, 0.0D));

        assertEquals(
                SkillTreeService.NodeLabelDetail.DETAILED,
                service.nodeLabelDetail(player, new Location(player.getWorld(), 10.0D, 64.0D, 0.0D))
        );
        assertEquals(
                SkillTreeService.NodeLabelDetail.COMPACT,
                service.nodeLabelDetail(player, new Location(player.getWorld(), 20.0D, 64.0D, 0.0D))
        );
        assertEquals(
                SkillTreeService.NodeLabelDetail.HIDDEN,
                service.nodeLabelDetail(player, new Location(player.getWorld(), 40.0D, 64.0D, 0.0D))
        );

        service.updateViewDistance(player, 24);
        assertEquals(
                SkillTreeService.NodeLabelDetail.HIDDEN,
                service.nodeLabelDetail(player, new Location(player.getWorld(), 30.0D, 64.0D, 0.0D))
        );
    }

    @Test
    void playerModeSkillTreeUsesAccountModeInsteadOfPermission() {
        WorldService worldService = mock(WorldService.class);
        SkillTreeService service = new SkillTreeService(
                PluginMock.builder()
                        .withPluginName("AstralRecordTest")
                        .withPluginVersion("1.0.0")
                        .build(),
                worldService,
                mock(InventoryService.class),
                mock(SkillTreeNodeRepository.class),
                mock(SkillTreeStructureRepository.class),
                mock(SkillTreePlayerStateRepository.class)
        );
        Player player = server().addPlayer();
        AstPlayer builder = mock(AstPlayer.class);
        when(builder.getAccount()).thenReturn(accountModel(AccountMode.BUILDER));
        when(builder.getBukkit()).thenReturn(player);
        AstPlayerCache.put(builder);

        when(worldService.findByBukkitWorld(player.getWorld())).thenReturn(new WorldMasterData(
                1,
                SkillTreeService.SKILL_TREE_WORLD_ID,
                "skill_tree",
                WorldType.BASE,
                "base",
                "instance",
                false,
                false,
                0,
                false,
                false,
                false,
                WorldSpawnLocation.defaultLocation(),
                "test"
        ));

        assertTrue(service.isPlayerModeSkillTree(player));
        assertFalse(service.isAdminMode(builder));

        AstPlayer admin = mock(AstPlayer.class);
        when(admin.getAccount()).thenReturn(accountModel(AccountMode.ADMIN));
        when(admin.getBukkit()).thenReturn(player);
        AstPlayerCache.put(admin);

        assertFalse(service.isPlayerModeSkillTree(player));
        assertTrue(service.isAdminMode(admin));

        AstPlayer privilegedPlayerMode = mock(AstPlayer.class);
        when(privilegedPlayerMode.getAccount()).thenReturn(accountModel(AccountMode.PLAYER));
        when(privilegedPlayerMode.hasAdminPermission()).thenReturn(true);
        when(privilegedPlayerMode.getBukkit()).thenReturn(player);
        AstPlayerCache.put(privilegedPlayerMode);

        assertTrue(service.isPlayerModeSkillTree(player));
        assertFalse(service.isAdminMode(privilegedPlayerMode));
    }

    private SkillTreeService newService(
            SkillTreeNodeRepository nodeRepository,
            SkillTreeStructureRepository structureRepository,
            SkillTreePlayerStateRepository playerStateRepository
    ) {
        Plugin plugin = mock(Plugin.class);
        plugin = PluginMock.builder()
                .withPluginName("AstralRecordTest")
                .withPluginVersion("1.0.0")
                .build();
        return new SkillTreeService(
                plugin,
                mock(WorldService.class),
                mock(InventoryService.class),
                nodeRepository,
                structureRepository,
                playerStateRepository
        );
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, SkillTreePlayerState> stateField(SkillTreeService service) {
        try {
            return (Map<UUID, SkillTreePlayerState>) field(service, "playerStates").get(service);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private Field field(SkillTreeService service, String name) {
        try {
            Field field = SkillTreeService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    private AccountModel accountModel(AccountMode mode) {
        return new AccountModel(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test-account",
                0,
                true,
                mode,
                "{}",
                LocalDateTime.now(),
                LocalDateTime.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                1,
                0L
        );
    }
}
