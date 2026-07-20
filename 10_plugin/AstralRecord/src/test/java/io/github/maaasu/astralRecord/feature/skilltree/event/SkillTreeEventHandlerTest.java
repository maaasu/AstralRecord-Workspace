package io.github.maaasu.astralRecord.feature.skilltree.event;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTreeEventHandlerTest {
    private SkillTreeService service;
    private Player player;
    private PlayerInteractionSnapshot snapshot;
    private PlayerInputContext<PlayerInteractionSnapshot> context;

    @BeforeEach
    void setUp() {
        service = mock(SkillTreeService.class);
        player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack held = mock(ItemStack.class);
        snapshot = new PlayerInteractionSnapshot(
            player,
            mock(Event.class),
            EquipmentSlot.HAND,
            null,
            null,
            null,
            null,
            false,
            PlayerInteractionRayTrace.create(new Vector(), new Vector(0.0D, 0.0D, 1.0D), 8.0D),
            8.0D
        );

        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(
            UUID.fromString("00000000-0000-0000-0000-000000000581")
        );
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(service.readPositionItemId(held)).thenReturn(null);
        when(service.isConnectorItem(held)).thenReturn(false);
        when(service.isPlayerModeSkillTree(player)).thenReturn(true);

        context = new PlayerInputContext<>(
            UUID.fromString("00000000-0000-0000-0000-000000000581"),
            1L,
            InputFamily.RIGHT_CLICK,
            InputSource.PLAYER_INTERACT,
            snapshot
        );
    }

    @Test
    void returnsNoCandidateWhenPlayerDoesNotTargetSkillTreeNode() {
        when(service.findTargetedPositionHit(snapshot)).thenReturn(Optional.empty());

        Collection<PlayerInputCandidate> candidates = new SkillTreeEventHandler(service).resolve(context);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void returnsPlayerControlCandidateWhenPlayerTargetsSkillTreeNode() {
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 64, 0);
        when(service.findTargetedPositionHit(snapshot)).thenReturn(Optional.of(
            new SkillTreeService.SkillTreePositionHit(position, 2.5D)
        ));
        when(service.getNodeByPositionId("1000")).thenReturn(node("1000"));

        Collection<PlayerInputCandidate> candidates = new SkillTreeEventHandler(service).resolve(context);

        assertEquals(1, candidates.size());
        PlayerInputCandidate candidate = candidates.iterator().next();
        assertEquals("skill-tree-player-control", candidate.id());
        assertEquals("1000", candidate.targetKey());
        assertEquals(2.5D, candidate.hitDistance());
    }

    @Test
    void executesResolvedNodeWithoutTargetingItAgainAfterWinnerSelection() {
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 64, 0);
        SkillTreeService.SkillTreePositionHit hit =
            new SkillTreeService.SkillTreePositionHit(position, 2.5D);
        SkillTreeNodeDefinition node = node("1000");
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        allowSnapshotRefresh();
        when(service.findTargetedPositionHit(any(PlayerInteractionSnapshot.class)))
            .thenReturn(Optional.of(hit));
        when(service.getNodeByPositionId("1000")).thenReturn(node);
        when(service.isStateReady(astPlayer)).thenReturn(true);
        when(service.hasAvailableUnlockPoint(astPlayer)).thenReturn(true);
        when(service.canUnlockNode(astPlayer, node)).thenReturn(true);
        when(service.unlockNode(astPlayer, node)).thenReturn(true);

        PlayerInputContext<PlayerInteractionSnapshot> leftClickContext = new PlayerInputContext<>(
            UUID.fromString("00000000-0000-0000-0000-000000000581"),
            2L,
            InputFamily.LEFT_CLICK,
            InputSource.PRE_PLAYER_ATTACK_ENTITY,
            snapshot
        );
        PlayerInputCandidate candidate = new SkillTreeEventHandler(service)
            .resolve(leftClickContext)
            .iterator()
            .next();

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(candidate.executeIfValid());
        }

        verify(service).preloadState(astPlayer);
        verify(service).unlockNode(astPlayer, node);
        verify(service, never()).findTargetedNode(player);
    }

    @Test
    void rightClickExecutesRelockForTheResolvedNode() {
        SkillTreePosition position = new SkillTreePosition("1000", "skill_tree", 0, 64, 0);
        SkillTreeService.SkillTreePositionHit hit =
            new SkillTreeService.SkillTreePositionHit(position, 2.5D);
        SkillTreeNodeDefinition node = node("1000");
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        allowSnapshotRefresh();
        when(service.findTargetedPositionHit(any(PlayerInteractionSnapshot.class)))
            .thenReturn(Optional.of(hit));
        when(service.getNodeByPositionId("1000")).thenReturn(node);
        when(service.isStateReady(astPlayer)).thenReturn(true);
        when(service.isNodeUnlocked(astPlayer, node)).thenReturn(true);
        when(service.canAffordRelock(astPlayer)).thenReturn(true);
        when(service.relockNode(astPlayer, node)).thenReturn(true);

        PlayerInputCandidate candidate = new SkillTreeEventHandler(service)
            .resolve(context)
            .iterator()
            .next();

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(candidate.executeIfValid());
        }

        verify(service).relockNode(astPlayer, node);
        verify(service, never()).findTargetedNode(player);
    }

    private void allowSnapshotRefresh() {
        World world = mock(World.class);
        Location eye = new Location(world, 0.0D, 64.0D, 0.0D);
        eye.setDirection(new Vector(0.0D, 0.0D, 1.0D));
        when(player.getEyeLocation()).thenReturn(eye);
        when(player.getWorld()).thenReturn(world);
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            anyDouble(),
            any(FluidCollisionMode.class),
            anyBoolean()
        )).thenReturn(null);
    }

    private SkillTreeNodeDefinition node(String positionId) {
        return new SkillTreeNodeDefinition(
            "test-node",
            positionId,
            "Test Node",
            Material.STONE,
            List.of(),
            List.of(),
            SkillTreePointType.PASSIVE_POINT,
            1,
            List.of(),
            List.of()
        );
    }
}
