package io.github.maaasu.astralRecord.shared.gui.session;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiCloseSoundHolder;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Bukkit の open / close / quit を共有 GUI セッション遷移へ接続します。
 */
public final class GuiSessionTransitionEventHandler extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final GuiSessionTransitionService transitionService;

    /**
     * GUI セッション遷移イベントハンドラを生成します。
     *
     * @param plugin 次 tick の終了確定と終了イベント発行に使うプラグイン本体
     * @param transitionService GUI セッション遷移サービス
     */
    public GuiSessionTransitionEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull GuiSessionTransitionService transitionService
    ) {
        this.plugin = plugin;
        this.transitionService = transitionService;
    }

    /**
     * 成功したプラグイン管理 GUI の open ごとに新しいセッショントークンを発行します。
     *
     * @param event Bukkit のインベントリ open イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(@NotNull InventoryOpenEvent event) {
        runSafely(() -> {
            if (event.getPlayer() instanceof Player player) {
                if (GuiSessionTransitionService.isPluginManagedGui(event.getInventory())) {
                    transitionService.registerOpened(player.getUniqueId(), event.getInventory());
                } else {
                    transitionService.cancelContinuation(player.getUniqueId());
                }
            }
        }, LogId.E_5601, event.getPlayer().getName(), "gui_session_open");
    }

    /**
     * close 後の再表示要求を共有セッション遷移として登録し、次 tick に target open を試行します。
     *
     * @param event feature が発行した GUI セッション継続要求
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onGuiSessionContinuationRequest(@NotNull GuiSessionContinuationRequestEvent event) {
        runSafely(() -> {
            Player player = event.getPlayer();
            GuiSessionTransitionService.ContinuationToken continuation = transitionService.beginContinuation(
                player.getUniqueId(), event.getSourceInventory()
            );
            if (continuation == null) {
                return;
            }
            plugin.getServer().getScheduler().runTask(
                plugin,
                () -> runSafely(
                    () -> completeContinuation(player, continuation, event),
                    LogId.E_5601,
                    player.getName(),
                    "gui_session_continuation"
                )
            );
        }, LogId.E_5601, event.getPlayer().getName(), "gui_session_continuation_request");
    }

    /**
     * close を終了候補として登録し、次 tick にセッション終了か別 GUI 遷移かを確定します。
     *
     * @param event Bukkit のインベントリ close イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        runSafely(() -> {
            if (!(event.getPlayer() instanceof Player player)) {
                return;
            }
            GuiSessionTransitionService.CloseToken closeToken = transitionService.beginClose(
                player.getUniqueId(), event.getInventory()
            );
            if (closeToken == null) {
                return;
            }
            plugin.getServer().getScheduler().runTask(
                plugin,
                () -> completeClose(player, closeToken)
            );
        }, LogId.E_5601, event.getPlayer().getName(), "gui_session_close");
    }

    /**
     * ログアウト時に現在の GUI セッションを音なしで終了し、共通 cleanup へ委譲します。
     *
     * @param event Bukkit のプレイヤー退出イベント
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> {
            Player player = event.getPlayer();
            completeSessionClose(player, transitionService.endSilently(player.getUniqueId()), GuiSessionEndReason.PLAYER_QUIT);
        }, LogId.E_5601, event.getPlayer().getName(), "gui_session_quit");
    }

    private void completeClose(
        @NotNull Player player,
        @NotNull GuiSessionTransitionService.CloseToken closeToken
    ) {
        Inventory closedInventory = transitionService.finishClose(player.getUniqueId(), closeToken);
        completeSessionClose(player, closedInventory, GuiSessionEndReason.MANUAL_CLOSE);
    }

    private void completeContinuation(
        @NotNull Player player,
        @NotNull GuiSessionTransitionService.ContinuationToken continuation,
        @NotNull GuiSessionContinuationRequestEvent event
    ) {
        if (!transitionService.isContinuationPending(player.getUniqueId(), continuation)) {
            return;
        }
        if (!player.isOnline()) {
            completeSessionClose(player, transitionService.endSilently(player.getUniqueId()), GuiSessionEndReason.PLAYER_QUIT);
            return;
        }
        try {
            Inventory targetInventory = event.getTargetInventorySupplier().get();
            if (targetInventory == null || !GuiSessionTransitionService.isPluginManagedGui(targetInventory)) {
                completeContinuationFailure(player, continuation);
                return;
            }
            player.openInventory(targetInventory);
            if (player.getOpenInventory().getTopInventory() != targetInventory) {
                completeContinuationFailure(player, continuation);
                return;
            }
            event.getTargetOpened().run();
        } catch (RuntimeException exception) {
            completeContinuationFailure(player, continuation);
            throw exception;
        }
    }

    private void completeContinuationFailure(
        @NotNull Player player,
        @NotNull GuiSessionTransitionService.ContinuationToken continuation
    ) {
        Inventory closedInventory = transitionService.failContinuation(player.getUniqueId(), continuation);
        completeSessionClose(player, closedInventory, GuiSessionEndReason.MANUAL_CLOSE);
    }

    private void completeSessionClose(
        @NotNull Player player,
        @Nullable Inventory closedInventory,
        @NotNull GuiSessionEndReason reason
    ) {
        if (closedInventory == null) {
            return;
        }
        if (reason.isCloseSoundEnabled()
            && player.isOnline()
            && closedInventory.getHolder() instanceof GuiCloseSoundHolder) {
            GuiSound.CLOSE.play(player);
        }
        plugin.getServer().getPluginManager().callEvent(new GuiSessionEndEvent(player, closedInventory, reason));
    }
}
