package io.github.maaasu.astralRecord.feature.condition.event;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ConditionPlayerEventHandlerTest {

    @Test
    void logsDedicatedIdAndOperationForEveryHandledEvent() {
        ConditionService conditionService = mock(ConditionService.class);
        ConditionPlayerEventHandler handler = new ConditionPlayerEventHandler(conditionService);
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(player.getName()).thenReturn("condition-player");
        when(astPlayer.getBukkit()).thenReturn(player);

        doThrow(new IllegalStateException("move"))
            .when(conditionService).canMove(any(AstEntity.class));
        doThrow(new IllegalStateException("interact"))
            .when(conditionService).canInteract(any(AstEntity.class));
        doThrow(new IllegalStateException("clear"))
            .when(conditionService).clearAll(any(AstEntity.class));

        PlayerMoveEvent move = mock(PlayerMoveEvent.class);
        PlayerInteractEvent interact = mock(PlayerInteractEvent.class);
        PlayerInteractEntityEvent interactEntity = mock(PlayerInteractEntityEvent.class);
        InventoryClickEvent inventoryClick = mock(InventoryClickEvent.class);
        InventoryDragEvent inventoryDrag = mock(InventoryDragEvent.class);
        PlayerDropItemEvent dropItem = mock(PlayerDropItemEvent.class);
        PlayerSwapHandItemsEvent swapHand = mock(PlayerSwapHandItemsEvent.class);
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(move.getPlayer()).thenReturn(player);
        when(interact.getPlayer()).thenReturn(player);
        when(interactEntity.getPlayer()).thenReturn(player);
        when(inventoryClick.getWhoClicked()).thenReturn(player);
        when(inventoryDrag.getWhoClicked()).thenReturn(player);
        when(dropItem.getPlayer()).thenReturn(player);
        when(swapHand.getPlayer()).thenReturn(player);
        when(quit.getPlayer()).thenReturn(player);
        when(death.getEntity()).thenReturn(player);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Logger> logger = mockStatic(Logger.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            handler.onPlayerMove(move);
            handler.onPlayerInteract(interact);
            handler.onPlayerInteractEntity(interactEntity);
            handler.onInventoryClick(inventoryClick);
            handler.onInventoryDrag(inventoryDrag);
            handler.onPlayerDropItem(dropItem);
            handler.onPlayerSwapHandItems(swapHand);
            handler.onPlayerQuit(quit);
            handler.onPlayerDeath(death);

            verifyLog(logger, "player_move");
            verifyLog(logger, "player_interact");
            verifyLog(logger, "player_interact_entity");
            verifyLog(logger, "inventory_click");
            verifyLog(logger, "inventory_drag");
            verifyLog(logger, "player_drop_item");
            verifyLog(logger, "player_swap_hand");
            verifyLog(logger, "player_quit");
            verifyLog(logger, "player_death");
        }
    }

    private void verifyLog(MockedStatic<Logger> logger, String operation) {
        logger.verify(() -> Logger.log(
            Mockito.eq(LogId.E_5902),
            Mockito.any(IllegalStateException.class),
            Mockito.eq("condition-player"),
            Mockito.eq(operation)
        ));
    }
}
