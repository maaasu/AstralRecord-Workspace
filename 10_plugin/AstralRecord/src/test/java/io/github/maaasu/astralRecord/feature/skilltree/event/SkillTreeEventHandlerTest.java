package io.github.maaasu.astralRecord.feature.skilltree.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillTreeEventHandlerTest extends MockBukkitTestBase {

    @AfterEach
    void clearCache() {
        AstPlayerCache.clear();
    }

    @Test
    void rightClickRelockSuppressesImmediateLeftClickUnlock() {
        SkillTreeService service = mock(SkillTreeService.class);
        SkillTreeEventHandler handler = new SkillTreeEventHandler(service);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = new AccountModel(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test-account",
                0,
                true,
                AccountMode.PLAYER,
                "{}",
                LocalDateTime.now(),
                LocalDateTime.now(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                1,
                0L
        );
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getAccount()).thenReturn(account);
        AstPlayerCache.put(astPlayer);

        SkillTreeNodeDefinition node = new SkillTreeNodeDefinition(
                "node-1",
                "pos-1",
                "Node 1",
                Material.DIAMOND,
                List.of(),
                List.of("root"),
                SkillTreePointType.PASSIVE_POINT,
                0,
                List.of("skill.one"),
                List.of()
        );

        when(service.isPlayerModeSkillTree(player)).thenReturn(true);
        when(service.isStateReady(astPlayer)).thenReturn(true);
        when(service.findTargetedNode(player)).thenReturn(java.util.Optional.of(node));
        when(service.isNodeUnlocked(astPlayer, node)).thenReturn(true, false);
        when(service.canAffordRelock(astPlayer)).thenReturn(true);
        when(service.relockNode(astPlayer, node)).thenReturn(true);

        handler.onPlayerInteract(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, null, EquipmentSlot.HAND));
        handler.onPlayerInteract(new PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, null, null, null, EquipmentSlot.HAND));

        verify(service).relockNode(astPlayer, node);
        verify(service, never()).unlockNode(any(), any());
    }

    @Test
    void adminMoveRefreshesSkillTreeVisuals() {
        SkillTreeService service = mock(SkillTreeService.class);
        SkillTreeEventHandler handler = new SkillTreeEventHandler(service);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstPlayerCache.put(astPlayer);
        when(service.isAdminMode(astPlayer)).thenReturn(true);

        Location from = new Location(player.getWorld(), 0.0D, 64.0D, 0.0D);
        Location to = new Location(player.getWorld(), 1.0D, 64.0D, 0.0D);
        handler.onPlayerMove(new PlayerMoveEvent(player, from, to));

        verify(service).markViewerContextDirty(player);
    }

    @Test
    void adminRotationOnlyDoesNotRefreshSkillTreeVisuals() {
        SkillTreeService service = mock(SkillTreeService.class);
        SkillTreeEventHandler handler = new SkillTreeEventHandler(service);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstPlayerCache.put(astPlayer);
        when(service.isAdminMode(astPlayer)).thenReturn(true);

        Location from = new Location(player.getWorld(), 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
        Location to = new Location(player.getWorld(), 0.0D, 64.0D, 0.0D, 90.0F, 20.0F);
        handler.onPlayerMove(new PlayerMoveEvent(player, from, to));

        verify(service, never()).markViewerContextDirty(player);
    }
}
