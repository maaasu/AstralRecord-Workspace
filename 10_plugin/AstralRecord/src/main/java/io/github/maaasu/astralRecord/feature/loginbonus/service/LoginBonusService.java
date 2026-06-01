package io.github.maaasu.astralRecord.feature.loginbonus.service;

import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ログインボーナスの日次受け取り状態と GUI 表示を管理します。
 */
public final class LoginBonusService {
    private static final ZoneId DATE_ZONE = ZoneId.of("Asia/Tokyo");
    private static final int DAILY_LOGIN_BONUS_GOLD = 1000;

    private final LoginBonusGui gui;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final Map<UUID, LocalDate> receivedDates = new ConcurrentHashMap<>();

    /**
     * ログインボーナスサービスを構築します。
     *
     * @param gui 表示に使用する GUI
     */
    public LoginBonusService(
        @NotNull LoginBonusGui gui,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService
    ) {
        this.gui = gui;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
    }

    /**
     * データロード済みプレイヤーへログインボーナス画面を開きます。
     *
     * @param player 対象プレイヤー
     */
    public void openAfterDataLoaded(@NotNull Player player) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !player.isOnline()) {
            return;
        }

        UUID accountId = astPlayer.getAccount().getUuid();
        LocalDate today = LocalDate.now(DATE_ZONE);
        boolean alreadyReceivedToday = today.equals(receivedDates.get(accountId));
        if (!alreadyReceivedToday) {
            receivedDates.put(accountId, today);
            grantDailyLoginBonus(astPlayer);
        }
        gui.open(player, alreadyReceivedToday, today);
    }

    private void grantDailyLoginBonus(@NotNull AstPlayer astPlayer) {
        var goldModel = itemService.loadItem(ItemService.DEFAULT_CURRENCY_ITEM_ID);
        if (goldModel == null) {
            return;
        }
        inventoryService.addItemToNormalInventory(astPlayer, goldModel, DAILY_LOGIN_BONUS_GOLD, "daily_login_bonus");
    }

    /**
     * ログインボーナス GUI を返します。
     *
     * @return ログインボーナス GUI
     */
    public @NotNull LoginBonusGui getGui() {
        return gui;
    }
}
