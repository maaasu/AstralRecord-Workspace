package io.github.maaasu.astralRecord.feature.currency.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.currency.view.CurrencyExchangeGuiView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ゴールド両替GUIの操作を処理します。
 */
public final class CurrencyExchangeGuiEventHandler extends AbstractEventHandler {
    private final CurrencyService currencyService;
    private final CurrencyExchangeGuiView view;

    /**
     * 両替GUIイベントハンドラを生成します。
     *
     * @param currencyService 通貨サービス
     */
    public CurrencyExchangeGuiEventHandler(@NotNull CurrencyService currencyService) {
        this.currencyService = currencyService;
        this.view = new CurrencyExchangeGuiView();
    }

    /**
     * 指定プレイヤーへ両替GUIを開きます。
     *
     * @param player 対象プレイヤー
     */
    public void open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !AccountModeGuard.isGameplayPlayer(player)) {
            GuiSound.DENY.play(player);
            return;
        }
        view.open(player, astPlayer.getAccount().getUuid(), currencyService);
        GuiSound.OPEN.play(player);
    }

    /**
     * 両替GUI内のクリックを処理します。
     *
     * @param event インベントリクリックイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (!view.isExchangeInventory(event.getView().getTopInventory())) {
                return;
            }
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
                return;
            }
            handleClick(event, player);
        }, LogId.E_5601, event.getWhoClicked().getName(), "currency_exchange_click");
    }

    /**
     * 両替GUIへのドラッグ操作を禁止します。
     *
     * @param event インベントリドラッグイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (view.isExchangeInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
            }
        }, LogId.E_5601, event.getWhoClicked().getName(), "currency_exchange_drag");
    }

    private void handleClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (event.getRawSlot() == CurrencyExchangeGuiView.CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        GoldDenomination denomination = view.denominationAt(event.getRawSlot());
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (denomination == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        boolean all = event.isShiftClick();
        boolean exchanged;
        if (event.isLeftClick()) {
            exchanged = currencyService.exchangeUp(astPlayer.getAccount().getUuid(), denomination, all);
        } else if (event.isRightClick()) {
            exchanged = currencyService.exchangeDown(astPlayer.getAccount().getUuid(), denomination, all);
        } else {
            exchanged = false;
        }
        if (!exchanged) {
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.SUCCESS.play(player);
        view.open(player, astPlayer.getAccount().getUuid(), currencyService);
    }
}
