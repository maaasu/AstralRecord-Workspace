package io.github.maaasu.astralRecord.feature.rebirth.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.rebirth.gui.RebirthGui;
import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthInventoryHolder;
import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthOperationResult;
import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthRejectionReason;
import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthScreen;
import io.github.maaasu.astralRecord.feature.rebirth.service.RebirthRejectedException;
import io.github.maaasu.astralRecord.feature.rebirth.service.RebirthService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndEvent;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** NPCから開く転生GUIの操作と非同期保存完了通知を処理します。 */
public final class RebirthGuiEventHandler extends AbstractEventHandler {
    private final Plugin plugin;
    private final RebirthGui gui;
    private final RebirthService service;
    private final SkillTreeService skillTreeService;
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    /**
     * 転生GUIハンドラを生成します。
     *
     * @param plugin スケジューラを提供するPlugin
     * @param gui 転生GUI
     * @param service 転生サービス
     * @param skillTreeService PP由来状態の再計算先
     */
    public RebirthGuiEventHandler(
        @NotNull Plugin plugin,
        @NotNull RebirthGui gui,
        @NotNull RebirthService service,
        @NotNull SkillTreeService skillTreeService
    ) {
        this.plugin = plugin;
        this.gui = gui;
        this.service = service;
        this.skillTreeService = skillTreeService;
    }

    /**
     * NPCから転生画面を開きます。
     *
     * @param player 表示対象プレイヤー
     * @return アカウントを取得して開けた場合はtrue
     */
    public boolean open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return false;
        }
        gui.open(player, astPlayer.getAccount());
        GuiSound.OPEN.play(player);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            RebirthInventoryHolder holder = gui.holder(event.getView().getTopInventory());
            if (holder == null) return;
            event.setCancelled(true);
            if (processing.contains(player.getUniqueId())) {
                GuiSound.DENY.play(player);
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7188);
                return;
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) return;
            if (holder.screen() == RebirthScreen.MAIN) {
                if (event.getRawSlot() != RebirthGui.ACTION_SLOT) {
                    GuiSound.DENY.play(player);
                    return;
                }
                if (service.isActive(astPlayer.getAccount())) {
                    gui.openEndConfirm(player, astPlayer.getAccount());
                } else {
                    gui.openStartConfirm(player, astPlayer.getAccount());
                }
                GuiSound.CONFIRM.play(player);
                return;
            }
            if (event.getRawSlot() == ConfirmDialogView.CANCEL_SLOT) {
                gui.open(player, astPlayer.getAccount());
                GuiSound.SELECT.play(player);
                return;
            }
            if (event.getRawSlot() != ConfirmDialogView.CONFIRM_SLOT) {
                GuiSound.DENY.play(player);
                return;
            }
            processing.add(player.getUniqueId());
            GuiSound.CONFIRM.play(player);
            var future = holder.screen() == RebirthScreen.CONFIRM_START
                ? service.start(astPlayer)
                : service.endEarly(astPlayer);
            future.whenComplete((result, failure) -> Bukkit.getScheduler().runTask(plugin, () ->
                complete(player, holder.screen(), result, failure)));
        }, LogId.E_5601, event.getWhoClicked().getName(), "rebirth_gui_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (gui.holder(event.getView().getTopInventory()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuiSessionEnd(GuiSessionEndEvent event) {
        if (gui.holder(event.getInventory()) != null && event.getReason().isCloseSoundEnabled()
            && !processing.contains(event.getPlayer().getUniqueId())) {
            GuiSound.CLOSE.play(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRuntimeDiscard(PlayerRuntimeDiscardEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    private void clearPlayerRuntime(Player player) {
        processing.remove(player.getUniqueId());
    }

    private void complete(
        @NotNull Player player,
        @NotNull RebirthScreen screen,
        RebirthOperationResult result,
        Throwable failure
    ) {
        processing.remove(player.getUniqueId());
        if (!player.isOnline()) return;
        AstPlayer current = AstPlayerCache.get(player);
        if (failure != null || current == null || result == null) {
            GuiSound.DENY.play(player);
            sendFailure(player, current, screen, failure);
            if (current != null) gui.open(player, current.getAccount());
            return;
        }
        current.setAccount(result.account());
        skillTreeService.refreshProgressDerivedState(current);
        gui.open(player, current.getAccount());
        GuiSound.SUCCESS.play(player);
        PlayerMessageService.getInstance().send(
            player,
            screen == RebirthScreen.CONFIRM_START ? PlayerMsgId.P_7180 : PlayerMsgId.P_7185,
            result.consumedAmount(),
            result.originalLevel()
        );
    }

    private @NotNull PlayerMsgId rejectionMessage(Throwable failure, RebirthScreen screen) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RebirthRejectedException rejected) {
            return switch (rejected.reason()) {
                case LEVEL_TOO_LOW -> PlayerMsgId.P_7181;
                case ALREADY_ACTIVE -> PlayerMsgId.P_7182;
                case INSUFFICIENT_GOLD -> PlayerMsgId.P_7183;
                case NOT_ACTIVE -> PlayerMsgId.P_7186;
                case INSUFFICIENT_ASTRALD -> PlayerMsgId.P_7187;
                case PLAYER_STATE_UNAVAILABLE -> PlayerMsgId.P_7184;
            };
        }
        return screen == RebirthScreen.CONFIRM_END ? PlayerMsgId.P_7189 : PlayerMsgId.P_7184;
    }

    private void sendFailure(
        @NotNull Player player,
        AstPlayer current,
        @NotNull RebirthScreen screen,
        Throwable failure
    ) {
        PlayerMsgId messageId = rejectionMessage(failure, screen);
        if (messageId == PlayerMsgId.P_7183 && current != null) {
            PlayerMessageService.getInstance().send(
                player, messageId, service.startGoldCost(current.getAccount().getLevel()));
        } else if (messageId == PlayerMsgId.P_7187) {
            PlayerMessageService.getInstance().send(player, messageId, RebirthService.EARLY_END_ASTRALD_COST);
        } else {
            PlayerMessageService.getInstance().send(player, messageId);
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }
}
