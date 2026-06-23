package io.github.maaasu.astralRecord.feature.waystone.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.waystone.service.WaystoneGuiHolder;
import io.github.maaasu.astralRecord.feature.waystone.service.WaystoneService;
import io.github.maaasu.astralRecord.feature.waystone.service.WaystoneVisualizer;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ウェイストーンの右クリックとGUIクリックを処理します。
 */
public final class WaystoneEventHandler extends AbstractEventHandler {
    private final WaystoneService service;
    private final WaystoneVisualizer visualizer;

    /**
     * event handler を初期化します。
     *
     * @param service ウェイストーンサービス
     * @param visualizer ウェイストーン表示サービス
     */
    public WaystoneEventHandler(@NotNull WaystoneService service, @NotNull WaystoneVisualizer visualizer) {
        this.service = service;
        this.visualizer = visualizer;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        handleEntityInteract(event.getPlayer(), event.getRightClicked(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractDisplay(@NotNull PlayerInteractAtEntityEvent event) {
        handleEntityInteract(event.getPlayer(), event.getRightClicked(), event);
    }

    private void handleEntityInteract(@NotNull Player player, @NotNull Entity entity, @NotNull Cancellable event) {
        String waystoneId = visualizer.readWaystoneId(entity);
        if (waystoneId == null) {
            return;
        }
        event.setCancelled(true);
        service.handleInteract(player, waystoneId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof WaystoneGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            GuiSound.DENY.play(player);
            return;
        }
        String waystoneId = holder.waystoneIdAt(event.getRawSlot());
        if (waystoneId == null) {
            GuiSound.DENY.play(player);
            return;
        }
        service.teleportFromGui(player, waystoneId);
    }
}
