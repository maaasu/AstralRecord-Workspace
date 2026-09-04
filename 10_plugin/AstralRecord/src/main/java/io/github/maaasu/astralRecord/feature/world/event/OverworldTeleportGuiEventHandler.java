package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.gui.OverworldTeleportGui;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * オーバーワールド転送 GUI の操作を処理します。
 */
public final class OverworldTeleportGuiEventHandler extends AbstractEventHandler {
    private final OverworldTeleportGui gui;
    private final OverworldTeleportService teleportService;

    public OverworldTeleportGuiEventHandler(
            @NotNull OverworldTeleportGui gui,
            @NotNull OverworldTeleportService teleportService
    ) {
        this.gui = gui;
        this.teleportService = teleportService;
    }

    /**
     * GUI を開きます。
     *
     * @param player 対象プレイヤー
     * @return GUI を開いた場合は {@code true}
     */
    public boolean open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return false;
        }

        List<WorldMasterData> destinations = teleportService.listDestinations();
        if (destinations.isEmpty()) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5769);
            return false;
        }

        gui.open(player, destinations);
        return true;
    }

    /**
     * この GUI を表示中かを返します。
     *
     * @param player 対象プレイヤー
     * @return 表示中なら {@code true}
     */
    public boolean isOpen(@NotNull Player player) {
        return gui.isInventory(player.getOpenInventory().getTopInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            Inventory topInventory = event.getView().getTopInventory();
            if (!gui.isInventory(topInventory)) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            handleTopClick(player, topInventory, event.getRawSlot());
        }, LogId.E_5755, event.getWhoClicked().getName(), "click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (!gui.isInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5755, event.getWhoClicked().getName(), "drag");
    }

    private void handleTopClick(@NotNull Player player, @NotNull Inventory inventory, int rawSlot) {
        OverworldTeleportGui.Holder holder = gui.holder(inventory);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (holder == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }

        String worldId = holder.worldIdsBySlot().get(rawSlot);
        if (worldId == null) {
            GuiSound.DENY.play(player);
            return;
        }

        GuiSound.SELECT.play(player);
        teleportService.teleportToDestination(player, astPlayer, worldId);
    }

}
