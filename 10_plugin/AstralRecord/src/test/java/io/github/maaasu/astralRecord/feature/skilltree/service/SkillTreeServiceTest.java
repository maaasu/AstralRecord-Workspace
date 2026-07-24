package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeSkillEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeStatusEffect;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTreeServiceTest {

    @Test
    void replaceMasterDataSnapshotSkipsOnlineCacheWhenNoPlayerStateIsLoaded() {
        SkillTreeService service = newService(null);

        try (MockedStatic<AstPlayerCache> cache = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            service.replaceMasterDataSnapshot(emptyMasterDataSnapshot());

            cache.verifyNoInteractions();
        }
    }

    @Test
    void replaceMasterDataSnapshotReconcilesLoadedOnlinePlayerBeforeStatusRefresh() {
        UUID accountId = UUID.randomUUID();
        AstPlayer player = astPlayer(accountId);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        StatusService statusService = mock(StatusService.class);
        SkillTreeService service = newService(null);
        service.setPassiveSkillService(passiveSkillService);
        service.setStatusService(statusService);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of("1000")));

        try (MockedStatic<AstPlayerCache> cache = org.mockito.Mockito.mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(player));

            service.replaceMasterDataSnapshot(emptyMasterDataSnapshot());
        }

        InOrder order = inOrder(passiveSkillService, statusService);
        order.verify(passiveSkillService).reconcileNow(player, false);
        order.verify(statusService).refreshStatus(player);
    }

    @Test
    void canUnlockRootIgnoresPersistedUnknownNodeIds() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition root = node("1000");
        SkillTreeService service = newService(root);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of("9999")));

        assertTrue(service.canUnlockNode(player, root));
    }

    @Test
    void canRelockNodeIgnoresPersistedUnknownNodeIds() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition root = node("1000");
        SkillTreeService service = newService(root);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of("1000", "9999")));

        assertTrue(service.canRelockNode(player, root));
    }

    @Test
    void unlockNodeReconcilesPassiveDeltaBeforeStatusRefresh() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition passiveNode = new SkillTreeNodeDefinition(
                "1000",
                "Passive Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of(new SkillTreeSkillEffect("passive-test"))
        );
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillTreeService service = newService(passiveNode);
        service.setPassiveSkillService(passiveSkillService);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of()));

        service.unlockNode(player, passiveNode);

        verify(passiveSkillService).reconcileSkillOwnershipDelta(
                eq(player),
                eq(Set.of("passive-test")),
                eq(Set.of()),
                eq(false)
        );
    }

    @Test
    void unlockNodeRefreshesStatusForDirectNodeModifier() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition statusNode = new SkillTreeNodeDefinition(
                "1000",
                "Status Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of(new SkillTreeStatusEffect(
                        StatusType.ATTACK,
                        StatusModifierType.FLAT,
                        5.0D
                ))
        );
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        StatusService statusService = mock(StatusService.class);
        SkillTreeService service = newService(statusNode);
        service.setPassiveSkillService(passiveSkillService);
        service.setStatusService(statusService);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of()));

        service.unlockNode(player, statusNode);

        verify(passiveSkillService).reconcileNow(player, false);
        verify(statusService).refreshStatus(player);
    }

    @Test
    void directNodeInteractionResolvesItsBoundPositionWithoutRayRetargeting() {
        SkillTreeService service = newService(null);
        Player player = mock(Player.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("skill_tree");
        putPosition(service, new SkillTreePosition("1000", "skill_tree", 0, 64, 3));
        Interaction interaction = mock(Interaction.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(interaction.getScoreboardTags()).thenReturn(Set.of(SkillTreeService.NODE_INTERACTION_TAG));
        when(interaction.getPersistentDataContainer()).thenReturn(data);
        when(interaction.isValid()).thenReturn(true);
        when(interaction.getBoundingBox()).thenReturn(new BoundingBox(-0.9D, 64.0D, 2.1D, 0.9D, 65.8D, 3.9D));
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn("1000");
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
                player,
                mock(Event.class),
                EquipmentSlot.HAND,
                null,
                interaction,
                null,
                null,
                false,
                PlayerInteractionRayTrace.create(
                        new Vector(0.0D, 65.62D, 0.0D),
                        new Vector(0.0D, 0.0D, 1.0D),
                        8.0D
                ),
                8.0D
        );

        SkillTreeService.SkillTreePositionHit hit = service
                .findTargetedPositionHit(snapshot)
                .orElseThrow();

        assertEquals("1000", hit.position().nodeId());
        assertTrue(hit.hitDistance() >= 0.0D);
    }

    @Test
    void skillTreePositionRemainsTargetableThroughBlockingBarrier() {
        SkillTreeService service = newService(null);
        Player player = mock(Player.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(player.getWorld()).thenReturn(world);
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 65, 3);
        putPosition(service, position);
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
                player,
                mock(Event.class),
                EquipmentSlot.HAND,
                null,
                null,
                null,
                null,
                false,
                PlayerInteractionRayTrace.create(
                        new Vector(0.0D, 65.62D, 0.0D),
                        new Vector(0.0D, 0.0D, 1.0D),
                        8.0D
                ),
                0.5D
        );

        SkillTreeService.SkillTreePositionHit hit;
        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("skill_tree")).thenReturn(world);
            hit = service.findTargetedPositionHit(snapshot).orElseThrow();
        }

        assertEquals("1000", hit.position().nodeId());
        assertTrue(hit.hitDistance() > snapshot.blockingDistance());
    }

    @Test
    void discardingOldJoinStateDoesNotRemoveNewerSessionState() {
        UUID accountId = UUID.randomUUID();
        SkillTreeService service = newService(null);
        SkillTreePlayerState oldState = new SkillTreePlayerState(accountId, Set.of("old"));
        SkillTreePlayerState currentState = new SkillTreePlayerState(accountId, Set.of("current"));
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);

        service.applyInitialPlayerState(oldState);
        service.applyInitialPlayerState(currentState);
        service.discardInitialPlayerState(oldState);

        assertTrue(service.isStateReady(player));

        service.discardInitialPlayerState(currentState);
        assertFalse(service.isStateReady(player));
    }

    private SkillTreeService newService(SkillTreeNodeDefinition node) {
        SkillTreeNodeRepository nodeRepository = mock(SkillTreeNodeRepository.class);
        SkillTreeStructureRepository structureRepository = mock(SkillTreeStructureRepository.class);
        SkillTreePlayerStateRepository stateRepository = mock(SkillTreePlayerStateRepository.class);

        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("AstralRecord");
        when(plugin.namespace()).thenReturn("astralrecord");

        SkillTreeService service = new SkillTreeService(
                plugin,
                mock(WorldService.class),
                null,
                nodeRepository,
                structureRepository,
                stateRepository
        );
        if (node != null) {
            putNode(service, node);
            putRootNodeId(service, node.nodeId());
        }
        return service;
    }

    private SkillTreeService.SkillTreeMasterDataSnapshot emptyMasterDataSnapshot() {
        return new SkillTreeService.SkillTreeMasterDataSnapshot(
                "1000",
                List.of(),
                List.of(),
                List.of()
        );
    }

    private SkillTreeNodeDefinition node(String nodeId) {
        return new SkillTreeNodeDefinition(
                nodeId,
                "Test Node",
                Material.NETHER_STAR,
                List.of(),
                List.of(),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of()
        );
    }

    @SuppressWarnings("unchecked")
    private void putNode(SkillTreeService service, SkillTreeNodeDefinition node) {
        try {
            Field nodesById = SkillTreeService.class.getDeclaredField("nodesById");
            nodesById.setAccessible(true);
            ((Map<String, SkillTreeNodeDefinition>) nodesById.get(service)).put(node.nodeId(), node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void putRootNodeId(SkillTreeService service, String nodeId) {
        try {
            Field rootNodeId = SkillTreeService.class.getDeclaredField("rootNodeId");
            rootNodeId.setAccessible(true);
            rootNodeId.set(service, nodeId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void putPosition(SkillTreeService service, SkillTreePosition position) {
        try {
            Field positionsByNodeId = SkillTreeService.class.getDeclaredField("positionsByNodeId");
            positionsByNodeId.setAccessible(true);
            ((Map<String, SkillTreePosition>) positionsByNodeId.get(service)).put(
                    position.nodeId(),
                    position
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private AstPlayer astPlayer(UUID accountId) {
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(mock(Player.class));
        when(player.getClassLevel()).thenReturn(1);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getLevel()).thenReturn(2);
        return player;
    }
}
