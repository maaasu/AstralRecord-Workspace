package io.github.maaasu.astralRecord.feature.skilltree.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeStatusDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePlayerState;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTreeServiceTest extends MockBukkitTestBase {

    @Test
    void unlockNodeReconcilesPassiveDeltaBeforeStatusRefresh() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition passiveNode = new SkillTreeNodeDefinition(
                "1000",
                "root",
                "Passive Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of("sw_passive_wall"),
                List.of()
        );
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        SkillTreeService service = newService(passiveNode);
        service.setPassiveSkillService(passiveSkillService);
        AstPlayer player = astPlayer(accountId);
        service.applyInitialPlayerState(new SkillTreePlayerState(accountId, Set.of()));

        service.unlockNode(player, passiveNode);

        verify(passiveSkillService).reconcileSkillOwnershipDelta(
                eq(player),
                eq(Set.of("sw_passive_wall")),
                eq(Set.of()),
                eq(false)
        );
    }

    @Test
    void unlockNodeRefreshesStatusForDirectNodeModifier() {
        UUID accountId = UUID.randomUUID();
        SkillTreeNodeDefinition statusNode = new SkillTreeNodeDefinition(
                "1000",
                "root",
                "Status Root",
                Material.NETHER_STAR,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of(),
                List.of(new SkillTreeNodeStatusDefinition(
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
    void setupItemInUnselectedHotbarDoesNotSuppressControls() {
        SkillTreeService service = newService(null);
        Player player = server().addPlayer();
        player.getInventory().setItem(0, service.createPositionItem("position-test", 1));
        player.getInventory().setItem(1, new ItemStack(Material.STONE));
        player.getInventory().setHeldItemSlot(1);

        assertFalse(service.shouldSuppressSkillTreeSetupControls(player));
    }

    @Test
    void setupItemInActiveHandSuppressesControls() {
        SkillTreeService service = newService(null);
        Player player = server().addPlayer();
        player.getInventory().setItemInMainHand(service.createPositionItem("position-test", 1));

        assertTrue(service.shouldSuppressSkillTreeSetupControls(player));
    }

    @Test
    void directNodeInteractionResolvesItsBoundPositionWithoutRayRetargeting() {
        SkillTreeService service = newService(null);
        Player player = server().addPlayer();
        service.registerPosition("1000", new Location(player.getWorld(), 0.0D, 64.0D, 3.0D));
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

        assertEquals("1000", hit.position().positionId());
        assertTrue(hit.hitDistance() >= 0.0D);
    }

    @Test
    void skillTreePositionRemainsTargetableThroughBlockingBarrier() {
        SkillTreeService service = newService(null);
        Player player = server().addPlayer();
        service.registerPosition("1000", new Location(player.getWorld(), 0.0D, 65.0D, 3.0D));
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

        SkillTreeService.SkillTreePositionHit hit = service
                .findTargetedPositionHit(snapshot)
                .orElseThrow();

        assertEquals("1000", hit.position().positionId());
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

        Plugin plugin = PluginMock.builder()
                .withPluginName("AstralRecord")
                .build();

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
        }
        return service;
    }

    @SuppressWarnings("unchecked")
    private void putNode(SkillTreeService service, SkillTreeNodeDefinition node) {
        try {
            Field nodesById = SkillTreeService.class.getDeclaredField("nodesById");
            nodesById.setAccessible(true);
            ((Map<String, SkillTreeNodeDefinition>) nodesById.get(service)).put(node.id(), node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private AstPlayer astPlayer(UUID accountId) {
        Player bukkitPlayer = server().addPlayer();
        UUID userId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UserModel user = new UserModel(
                userId,
                bukkitPlayer.getName(),
                now,
                now,
                "127.0.0.1",
                accountId,
                false,
                null,
                false,
                0,
                now,
                now,
                userId,
                userId,
                false
        );
        AccountModel account = new AccountModel(
                accountId,
                userId,
                "test-account",
                0,
                true,
                AccountMode.PLAYER,
                "[]",
                now,
                now,
                userId,
                userId,
                false,
                2,
                0L,
                "adventurer",
                1,
                0L
        );
        return new AstPlayer(bukkitPlayer, user, account);
    }
}
