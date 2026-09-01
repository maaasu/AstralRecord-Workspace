package io.github.maaasu.astralRecord.feature.loginbonus.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.loginbonus.repository.LoginBonusClaimRepository;
import io.github.maaasu.astralRecord.feature.loginbonus.repository.LoginBonusClaimResult;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusHoliday;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * ログイン報酬の日次受け取り状態と GUI 表示を管理します。
 */
public final class LoginBonusService {
    private static final ZoneId DATE_ZONE = ZoneId.of("Asia/Tokyo");
    private static final int DAILY_LOGIN_BONUS_GOLD = 1000;
    private static final int HOLIDAY_LOGIN_BONUS_ASTRALD = 10;
    private static final String FREYA_ORB_ITEM_ID = "freya_orb";
    private static final String REWARD_SOURCE = "daily_login_bonus";

    private final Plugin plugin;
    private final LoginBonusGui gui;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final LoginBonusClaimRepository claimRepository;
    private final Set<UUID> claimInFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> openRequestIds = new ConcurrentHashMap<>();
    private Consumer<AstPlayer> claimSuccessListener = player -> { };

    /**
     * ログイン報酬サービスを構築します。
     *
     * @param plugin 非同期 API 通信とメインスレッド反映を管理するプラグイン
     * @param gui 表示に使用する GUI
     * @param inventoryService インベントリ操作サービス
     * @param itemService アイテム定義サービス
     * @param claimRepository ログインボーナス受取履歴 repository
     */
    public LoginBonusService(
        @NotNull Plugin plugin,
        @NotNull LoginBonusGui gui,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull LoginBonusClaimRepository claimRepository
    ) {
        this.plugin = plugin;
        this.gui = gui;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.claimRepository = claimRepository;
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
        UUID playerId = player.getUniqueId();
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID requestId = UUID.randomUUID();
        openRequestIds.put(playerId, requestId);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var claimDates = claimRepository.loadClaimDates(accountId, displayMonth);
                ItemModel goldModel = resolveGoldRewardModel();
                ItemModel astraldModel = resolveAstraldRewardModel();
                ItemModel freyaOrbModel = resolveFreyaOrbRewardModel();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!openRequestIds.remove(playerId, requestId)) {
                        return;
                    }
                    Player online = plugin.getServer().getPlayer(playerId);
                    AstPlayer current = online == null ? null : AstPlayerCache.get(online);
                    if (online == null || !online.isOnline() || current == null
                        || !current.getAccount().getUuid().equals(accountId)) {
                        return;
                    }
                    gui.open(
                        online,
                        displayMonth,
                        LocalDate.now(DATE_ZONE),
                        claimDates,
                        goldModel,
                        astraldModel,
                        freyaOrbModel,
                        Math.max(1, current.getAccount().getLevel())
                    );
                });
            } catch (RuntimeException e) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (openRequestIds.remove(playerId, requestId) && player.isOnline()) {
                        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5074);
                    }
                });
            }
        });
    }

    /**
     * 当日スロットの報酬受け取りを試行します。
     *
     * @param player 対象プレイヤー
     * @param targetDate クリックされた日付
     * @param completion メインスレッド上で呼ばれる完了通知
     */
    public void claim(
        @NotNull Player player,
        @NotNull LocalDate targetDate,
        @NotNull Consumer<Boolean> completion
    ) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !player.isOnline()) {
            completion.accept(false);
            return;
        }
        LocalDate today = LocalDate.now(DATE_ZONE);
        if (!targetDate.equals(today)) {
            completion.accept(false);
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!claimInFlight.add(playerId)) {
            completion.accept(false);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ItemModel goldModel;
            ItemModel astraldModel;
            ItemModel freyaOrbModel;
            try {
                goldModel = resolveGoldRewardModel();
                astraldModel = LoginBonusHoliday.isHolidayBonusDate(today) ? resolveAstraldRewardModel() : null;
                freyaOrbModel = LoginBonusHoliday.isFridayBonusDate(today)
                    ? resolveFreyaOrbRewardModel()
                    : null;
            } catch (RuntimeException e) {
                finishClaim(playerId, LoginBonusClaimResult.FAILED, completion);
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () ->
                prepareClaim(playerId, accountId, today, goldModel, astraldModel, freyaOrbModel, completion)
            );
        });
    }

    /**
     * ログイン報酬 GUI を返します。
     *
     * @return ログイン報酬 GUI
     */
    public @NotNull LoginBonusGui getGui() {
        return gui;
    }

    /**
     * ログインボーナス受取成功時の通知先を設定します。
     *
     * @param claimSuccessListener 受取プレイヤーを受け取る通知先
     */
    public void setClaimSuccessListener(@NotNull Consumer<AstPlayer> claimSuccessListener) {
        this.claimSuccessListener = claimSuccessListener;
    }

    /**
     * ログインボーナス GUI が利用するインベントリサービスを返します。
     *
     * @return インベントリサービス
     */
    public @NotNull InventoryService getInventoryService() {
        return inventoryService;
    }

    private void prepareClaim(
        @NotNull UUID playerId,
        @NotNull UUID accountId,
        @NotNull LocalDate date,
        ItemModel goldModel,
        ItemModel astraldModel,
        ItemModel freyaOrbModel,
        @NotNull Consumer<Boolean> completion
    ) {
        Player player = plugin.getServer().getPlayer(playerId);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        boolean holiday = LoginBonusHoliday.isHolidayBonusDate(date);
        boolean friday = LoginBonusHoliday.isFridayBonusDate(date);
        if (player == null || !player.isOnline() || astPlayer == null
            || !astPlayer.getAccount().getUuid().equals(accountId)
            || goldModel == null || holiday && astraldModel == null || friday && freyaOrbModel == null) {
            finishClaim(playerId, LoginBonusClaimResult.FAILED, completion);
            return;
        }
        int freyaOrbAmount = Math.max(1, astPlayer.getAccount().getLevel());
        if (!inventoryService.canAddItemToNormalInventory(astPlayer, goldModel, DAILY_LOGIN_BONUS_GOLD)
            || holiday && !inventoryService.canAddItemToNormalInventory(
                astPlayer, astraldModel, HOLIDAY_LOGIN_BONUS_ASTRALD
            )
            || friday && !inventoryService.canAddItemToStorageIfPresentOtherwiseNormalInventory(
                astPlayer, freyaOrbModel, freyaOrbAmount
            )) {
            finishClaim(playerId, LoginBonusClaimResult.FAILED, completion);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            LoginBonusClaimResult claimResult;
            try {
                claimResult = claimRepository.tryClaim(accountId, date);
            } catch (RuntimeException e) {
                claimResult = LoginBonusClaimResult.FAILED;
            }
            LoginBonusClaimResult result = claimResult;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result != LoginBonusClaimResult.CREATED) {
                    finishClaim(playerId, result, completion);
                    return;
                }
                grantClaimedReward(
                    playerId,
                    accountId,
                    date,
                    goldModel,
                    astraldModel,
                    freyaOrbModel,
                    freyaOrbAmount,
                    completion
                );
            });
        });
    }

    private void grantClaimedReward(
        @NotNull UUID playerId,
        @NotNull UUID accountId,
        @NotNull LocalDate date,
        @NotNull ItemModel goldModel,
        ItemModel astraldModel,
        ItemModel freyaOrbModel,
        int freyaOrbAmount,
        @NotNull Consumer<Boolean> completion
    ) {
        Player player = plugin.getServer().getPlayer(playerId);
        AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
        InventoryService.InventoryStateSnapshot snapshot = inventoryService.snapshotState(accountId);
        if (player == null || !player.isOnline() || astPlayer == null
            || !astPlayer.getAccount().getUuid().equals(accountId) || snapshot == null) {
            cancelFailedClaim(playerId, accountId, date, completion);
            return;
        }
        int grantedGold = inventoryService.addItemToNormalInventory(
            astPlayer,
            goldModel,
            DAILY_LOGIN_BONUS_GOLD,
            REWARD_SOURCE
        );
        int grantedAstrald = LoginBonusHoliday.isHolidayBonusDate(date)
            ? inventoryService.addItemToNormalInventory(
                astPlayer,
                astraldModel,
                HOLIDAY_LOGIN_BONUS_ASTRALD,
                REWARD_SOURCE
            )
            : HOLIDAY_LOGIN_BONUS_ASTRALD;
        boolean friday = LoginBonusHoliday.isFridayBonusDate(date);
        InventoryService.StorageFallbackGrantResult freyaOrbGrant = null;
        if (friday && freyaOrbModel != null) {
            freyaOrbGrant = inventoryService.addItemToStorageIfPresentOtherwiseNormalInventory(
                astPlayer,
                freyaOrbModel,
                freyaOrbAmount,
                REWARD_SOURCE
            );
        }
        boolean freyaOrbComplete = !friday
            || freyaOrbGrant != null && freyaOrbGrant.grantedAmount() == freyaOrbAmount;
        if (grantedGold != DAILY_LOGIN_BONUS_GOLD
            || grantedAstrald != HOLIDAY_LOGIN_BONUS_ASTRALD
            || !freyaOrbComplete) {
            if (inventoryService.restoreState(snapshot)) {
                cancelFailedClaim(playerId, accountId, date, completion);
            } else {
                Logger.log(LogId.W_5203, "login_bonus_grant", accountId);
                finishClaim(playerId, LoginBonusClaimResult.FAILED, completion);
            }
            return;
        }
        if (friday && freyaOrbGrant != null && freyaOrbGrant.storedInStorage()) {
            PlayerMessageService.getInstance().send(
                player,
                PlayerMsgId.P_5078,
                freyaOrbModel.getName(),
                freyaOrbAmount
            );
        }
        finishClaim(playerId, LoginBonusClaimResult.CREATED, completion);
    }

    private void cancelFailedClaim(
        @NotNull UUID playerId,
        @NotNull UUID accountId,
        @NotNull LocalDate date,
        @NotNull Consumer<Boolean> completion
    ) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                claimRepository.cancelClaim(accountId, date);
            } catch (RuntimeException ignored) {
                // repository 側で Throwable 付きログを記録する。
            }
            finishClaim(playerId, LoginBonusClaimResult.FAILED, completion);
        });
    }

    private void finishClaim(
        @NotNull UUID playerId,
        @NotNull LoginBonusClaimResult result,
        @NotNull Consumer<Boolean> completion
    ) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            claimInFlight.remove(playerId);
            Player player = plugin.getServer().getPlayer(playerId);
            if (result != LoginBonusClaimResult.CREATED && player != null && player.isOnline()) {
                PlayerMessageService.getInstance().send(
                    player,
                    result == LoginBonusClaimResult.ALREADY_CLAIMED
                        ? PlayerMsgId.P_5075
                        : PlayerMsgId.P_5074
                );
            }
            if (result == LoginBonusClaimResult.CREATED && player != null && player.isOnline()) {
                var astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null) {
                    claimSuccessListener.accept(astPlayer);
                }
            }
            completion.accept(result == LoginBonusClaimResult.CREATED);
        });
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

    private ItemModel resolveFreyaOrbRewardModel() {
        ItemModel model = itemService.findLoadedById(FREYA_ORB_ITEM_ID);
        if (model == null) {
            model = itemService.loadItem(FREYA_ORB_ITEM_ID);
        }
        return model;
    }
}
