package io.github.maaasu.astralRecord.shared.gui.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * プラグイン GUI 全体のクリッククールタイムを入口で管理するイベントハンドラです。
 */
public final class GuiClickCooldownEventHandler extends AbstractEventHandler {
    private static final long GUI_CLICK_COOLDOWN_MS = 250L;
    private static final String PLUGIN_PACKAGE_PREFIX = "io.github.maaasu.astralRecord.";

    private final InventoryClickGuard clickGuard = new InventoryClickGuard();
    private final InventoryService inventoryService;

    /**
     * GUI 共通クリック処理を構築します。
     *
     * @param inventoryService インベントリサービス
     */
    public GuiClickCooldownEventHandler(@NotNull InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * プラグイン GUI への連続クリックを 5tick 相当だけ抑止します。
     *
     * @param event Bukkit のインベントリクリックイベント
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!isPluginGui(event.getView().getTopInventory())) {
                return;
            }
            if (HotbarShortcutClickSupport.handleInventoryControlClick(event, player, inventoryService)) {
                return;
            }
            if (clickGuard.tryAcquire(
                player.getUniqueId(),
                InventoryClickGuard.ClickAction.GUI_CLICK,
                GUI_CLICK_COOLDOWN_MS
            )) {
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    /**
     * 退出したプレイヤーのクリッククールタイム状態を破棄します。
     *
     * @param event Bukkit のプレイヤー退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        clickGuard.clear(event.getPlayer().getUniqueId());
    }

    private boolean isPluginGui(@NotNull Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder != null && holder.getClass().getName().startsWith(PLUGIN_PACKAGE_PREFIX);
    }
}
