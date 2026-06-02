package io.github.maaasu.astralRecord.feature.loginbonus.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusHoliday;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ログイン報酬の日次受け取り状態と GUI 表示を管理します。
 */
public final class LoginBonusService {
    private static final ZoneId DATE_ZONE = ZoneId.of("Asia/Tokyo");
    private static final int DAILY_LOGIN_BONUS_GOLD = 1000;
    private static final int HOLIDAY_LOGIN_BONUS_ASTRALD = 10;
    private static final String REWARD_SOURCE = "daily_login_bonus";

    private final LoginBonusGui gui;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final Map<UUID, Set<LocalDate>> receivedDates = new ConcurrentHashMap<>();

    /**
     * ログイン報酬サービスを構築します。
     *
     * @param gui 表示に使用する GUI
     * @param inventoryService インベントリ操作サービス
     * @param itemService アイテム定義サービス
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
     * データロード済みプレイヤーへ当月のログイン報酬画面を開きます。
     *
     * @param player 対象プレイヤー
     */
    public void openAfterDataLoaded(@NotNull Player player) {
        open(player, YearMonth.now(DATE_ZONE));
    }

    /**
     * 指定年月のログイン報酬画面を開きます。
     *
     * @param player 対象プレイヤー
     * @param displayMonth 表示する年月
     */
    public void open(@NotNull Player player, @NotNull YearMonth displayMonth) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !player.isOnline()) {
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        gui.open(
            player,
            displayMonth,
            LocalDate.now(DATE_ZONE),
            receivedDates.getOrDefault(accountId, Collections.emptySet()),
            resolveGoldRewardModel(),
            resolveAstraldRewardModel()
        );
    }

    /**
     * 当日スロットの報酬受け取りを試行します。
     *
     * @param player 対象プレイヤー
     * @param targetDate クリックされた日付
     * @return 受け取りに成功した場合は true
     */
    public boolean claim(@NotNull Player player, @NotNull LocalDate targetDate) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !player.isOnline()) {
            return false;
        }
        LocalDate today = LocalDate.now(DATE_ZONE);
        if (!targetDate.equals(today)) {
            return false;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        Set<LocalDate> dates = receivedDates.computeIfAbsent(accountId, ignored -> ConcurrentHashMap.newKeySet());
        if (!dates.add(today)) {
            return false;
        }
        if (!grantDailyLoginBonus(astPlayer, today)) {
            dates.remove(today);
            return false;
        }
        return true;
    }

    /**
     * ログイン報酬 GUI を返します。
     *
     * @return ログイン報酬 GUI
     */
    public @NotNull LoginBonusGui getGui() {
        return gui;
    }

    private boolean grantDailyLoginBonus(@NotNull AstPlayer astPlayer, @NotNull LocalDate date) {
        ItemModel goldModel = resolveGoldRewardModel();
        if (goldModel == null) {
            return false;
        }
        int grantedGold = inventoryService.addItemToNormalInventory(
            astPlayer,
            goldModel,
            DAILY_LOGIN_BONUS_GOLD,
            REWARD_SOURCE
        );
        if (grantedGold <= 0) {
            return false;
        }
        if (!LoginBonusHoliday.isJapaneseHoliday(date)) {
            return true;
        }
        ItemModel astraldModel = resolveAstraldRewardModel();
        if (astraldModel == null) {
            return false;
        }
        int grantedAstrald = inventoryService.addItemToNormalInventory(
            astPlayer,
            astraldModel,
            HOLIDAY_LOGIN_BONUS_ASTRALD,
            REWARD_SOURCE
        );
        return grantedAstrald > 0;
    }

    private ItemModel resolveGoldRewardModel() {
        ItemModel model = itemService.findLoadedById(ItemService.DEFAULT_CURRENCY_ITEM_ID);
        if (model == null) {
            model = itemService.loadItem(ItemService.DEFAULT_CURRENCY_ITEM_ID);
        }
        return model;
    }

    private ItemModel resolveAstraldRewardModel() {
        ItemModel model = itemService.findLoadedById(ItemService.ASTRALD_CURRENCY_ITEM_ID);
        if (model == null) {
            model = itemService.loadItem(ItemService.ASTRALD_CURRENCY_ITEM_ID);
        }
        return model;
    }
}
