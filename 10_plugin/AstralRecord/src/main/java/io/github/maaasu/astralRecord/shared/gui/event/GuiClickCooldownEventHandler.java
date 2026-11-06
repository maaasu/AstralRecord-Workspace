package io.github.maaasu.astralRecord.shared.gui.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
import io.github.maaasu.astralRecord.feature.item.gui.OrbGuiHolder;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiClickSoundFeedback;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.view.AnvilView;
import org.jetbrains.annotations.NotNull;

/**
 * プラグイン GUI 全体のクリッククールタイムを入口で管理するイベントハンドラです。
 * 金床の結果スロットは各 GUI の確定処理へ委譲するため、共通抑止の対象外とします。
 */
public final class GuiClickCooldownEventHandler extends AbstractEventHandler {
    private static final long GUI_CLICK_COOLDOWN_MS = 250L;

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
            if (!GuiSessionTransitionService.isPluginManagedGui(event.getView().getTopInventory())) {
                return;
            }
            if (event.getView().getTopInventory().getHolder() instanceof OrbGuiHolder) {
                GuiClickSoundFeedback.begin(event, player);
                return;
            }
            if (isAnvilResultClick(event)) {
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
                GuiClickSoundFeedback.begin(event, player);
                return;
            }
            event.setCancelled(true);
        }, LogId.E_5600, event.getWhoClicked().getName());
    }

    /**
     * GUIのクリック処理が終了した時点で、装飾枠の再生状態を閉じます。
     *
     * @param event 処理済みのインベントリクリック
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClickComplete(@NotNull InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            runSafely(() -> GuiClickSoundFeedback.finish(event, player),
                    LogId.E_5600, player.getName());
        }
    }

    /**
     * 退出したプレイヤーのクリッククールタイム状態を破棄します。
     *
     * @param event Bukkit のプレイヤー退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    /** 復旧時にクリック cooldown だけを破棄します。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRuntimeDiscard(@NotNull PlayerRuntimeDiscardEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    private void clearPlayerRuntime(@NotNull Player player) {
        clickGuard.clear(player.getUniqueId());
    }

    /**
     * 金床の結果スロットへのクリックか判定します。結果の確定処理は各GUIへ委譲するため、共通抑止から除外します。
     *
     * @param event 判定対象のインベントリクリックイベント
     * @return 金床の結果スロットへのクリックなら {@code true}
     */
    private boolean isAnvilResultClick(@NotNull InventoryClickEvent event) {
        return event.getView() instanceof AnvilView
            && event.getRawSlot() == event.getView().getTopInventory().getSize() - 1;
    }
}
