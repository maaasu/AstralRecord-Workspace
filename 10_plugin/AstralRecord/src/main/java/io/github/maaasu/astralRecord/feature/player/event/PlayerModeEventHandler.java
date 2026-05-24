package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

public class PlayerModeEventHandler extends AbstractEventHandler {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!isPlayerMode(player)) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5072, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!isPlayerMode(player)) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5072, event.getWhoClicked().getName());
    }

    /**
     * 通常プレイ中の表示用インベントリアイテムが、ドロップ操作で実体化しないように抑止します。
     *
     * @param event ドロップイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        runSafely(() -> {
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5072, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        runSafely(() -> {
            if (!isPlayerMode(event.getPlayer())) {
                return;
            }
            if (event.getNewGameMode() == GameMode.ADVENTURE) {
                return;
            }
            event.setCancelled(true);
            event.getPlayer().setGameMode(GameMode.ADVENTURE);
        }, LogId.E_5072, event.getPlayer().getName());
    }

    private boolean isPlayerMode(Player player) {
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }
}
