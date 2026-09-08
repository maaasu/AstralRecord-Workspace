package io.github.maaasu.astralRecord.feature.loginbonus.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.loginbonus.repository.LoginBonusClaimRepository;
import io.github.maaasu.astralRecord.feature.loginbonus.repository.LoginBonusClaimResult;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusHoliday;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final String FREYA_ORB_ITEM_ID = "40a00002";
    private static final String REWARD_SOURCE = "daily_login_bonus";

    private final Plugin plugin;
    private final LoginBonusGui gui;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final LoginBonusClaimRepository claimRepository;
    private final Set<UUID> claimInFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> openRequestIds = new ConcurrentHashMap<>();
    /** 受取済み・未保存・世代を同じ時点で読み書きするための状態ロックです。 */
    private final Object claimStateLock = new Object();
    private final Map<UUID, Set<LocalDate>> knownClaimDates = new ConcurrentHashMap<>();
    private final Map<UUID, LinkedHashMap<LocalDate, Long>> pendingClaimRevisions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> claimRevisions = new ConcurrentHashMap<>();
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
                mergeKnownClaimDates(accountId, claimDates);
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

    /** 受取日と報酬 inventory を同じ player-state transaction に含めます。 */
    public @Nullable PlayerStateSection snapshotPlayerState(@NotNull UUID accountId) {
        final LinkedHashMap<LocalDate, Long> captured;
        final long capturedRevision;
        synchronized (claimStateLock) {
            LinkedHashMap<LocalDate, Long> pending = pendingClaimRevisions.get(accountId);
            if (pending == null || pending.isEmpty()) {
                return null;
            }
            captured = new LinkedHashMap<>(pending);
            capturedRevision = captured.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("clientRevision", capturedRevision);
        JsonArray claimDates = new JsonArray();
        captured.keySet().stream().sorted().forEach(date -> claimDates.add(date.toString()));
        payload.add("claimDates", claimDates);
        return new PlayerStateSection("loginBonusClaims", payload,
            acknowledged -> acknowledgeClaims(accountId, capturedRevision, captured, acknowledged));
    }

    private void acknowledgeClaims(
        @NotNull UUID accountId,
        long capturedRevision,
        @NotNull LinkedHashMap<LocalDate, Long> captured,
        @NotNull JsonElement acknowledged
    ) {
        if (!acknowledged.isJsonObject()) {
            throw new IllegalStateException("Login bonus acknowledgement must be an object");
        }
        JsonObject ack = acknowledged.getAsJsonObject();
        if (!ack.has("clientRevision") || ack.get("clientRevision").getAsLong() != capturedRevision
            || !ack.has("claims") || !ack.get("claims").isJsonArray()) {
            throw new IllegalStateException("Login bonus acknowledgement is invalid");
        }
        Set<LocalDate> acknowledgedDates = new LinkedHashSet<>();
        for (JsonElement element : ack.getAsJsonArray("claims")) {
            JsonObject claim = element.getAsJsonObject();
            if (!claim.has("claimDate") || !claim.has("loginBonusClaimId") || !claim.has("claimedAt")) {
                throw new IllegalStateException("Login bonus acknowledgement claim is incomplete");
            }
            acknowledgedDates.add(LocalDate.parse(claim.get("claimDate").getAsString()));
        }
        if (!acknowledgedDates.equals(new LinkedHashSet<>(captured.keySet()))) {
            throw new IllegalStateException("Login bonus acknowledgement dates mismatch");
        }
        synchronized (claimStateLock) {
            pendingClaimRevisions.computeIfPresent(accountId, (ignored, current) -> {
                captured.forEach((date, revision) -> {
                    if (revision.equals(current.get(date))) current.remove(date);
                });
                return current.isEmpty() ? null : current;
            });
            mergeKnownClaimDatesLocked(accountId, captured.keySet());
        }
    }

    private @NotNull ClaimCheckpoint captureClaimCheckpoint(@NotNull UUID accountId) {
        synchronized (claimStateLock) {
            LinkedHashMap<LocalDate, Long> pending = pendingClaimRevisions.get(accountId);
            return new ClaimCheckpoint(
                claimRevisions.get(accountId),
                pending == null ? null : new LinkedHashMap<>(pending)
            );
        }
    }

    private void restoreClaimCheckpoint(@NotNull UUID accountId, @NotNull ClaimCheckpoint checkpoint) {
        synchronized (claimStateLock) {
            if (checkpoint.revision == null) claimRevisions.remove(accountId);
            else claimRevisions.put(accountId, checkpoint.revision);
            if (checkpoint.pending == null) pendingClaimRevisions.remove(accountId);
            else pendingClaimRevisions.put(accountId, new LinkedHashMap<>(checkpoint.pending));
        }
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
        if (isClaimKnownOrPending(accountId, date)) {
            finishClaim(playerId, LoginBonusClaimResult.ALREADY_CLAIMED, completion);
            return;
        }

        inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryBefore = inventoryService.snapshotState(accountId);
            if (inventoryBefore == null) {
                throw new IllegalStateException("Login bonus inventory state is unavailable");
            }
            Runnable equipmentRollback = itemService.captureEquipmentStateRollback(accountId);
            ClaimCheckpoint claimBefore = captureClaimCheckpoint(accountId);
            try {
                if (isClaimKnownOrPending(accountId, date)) {
                    throw new AlreadyClaimedException();
                }
                int grantedGold = inventoryService.addItemToNormalInventoryStateOnly(
                    astPlayer, goldModel, DAILY_LOGIN_BONUS_GOLD, REWARD_SOURCE);
                int grantedAstrald = holiday
                    ? inventoryService.addItemToNormalInventoryStateOnly(
                        astPlayer, astraldModel, HOLIDAY_LOGIN_BONUS_ASTRALD, REWARD_SOURCE)
                    : HOLIDAY_LOGIN_BONUS_ASTRALD;
                InventoryService.StorageFallbackGrantResult freyaOrbGrant = friday && freyaOrbModel != null
                    ? inventoryService.addItemToStorageIfPresentOtherwiseNormalInventoryStateOnly(
                        astPlayer, freyaOrbModel, freyaOrbAmount, REWARD_SOURCE)
                    : null;
                boolean freyaOrbComplete = !friday
                    || freyaOrbGrant != null && freyaOrbGrant.grantedAmount() == freyaOrbAmount;
                if (grantedGold != DAILY_LOGIN_BONUS_GOLD
                    || grantedAstrald != HOLIDAY_LOGIN_BONUS_ASTRALD || !freyaOrbComplete) {
                    throw new IllegalStateException("Login bonus reward capacity changed");
                }
                synchronized (claimStateLock) {
                    if (isClaimKnownOrPendingLocked(accountId, date)) {
                        throw new AlreadyClaimedException();
                    }
                    long revision = claimRevisions.merge(accountId, 1L, Math::addExact);
                    pendingClaimRevisions.computeIfAbsent(accountId, ignored -> new LinkedHashMap<>())
                        .put(date, revision);
                }
                ClaimCommitResult completedGrant = new ClaimCommitResult(
                    freyaOrbGrant != null && freyaOrbGrant.storedInStorage());
                return new io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator.CriticalMutation<>(
                    completedGrant,
                    () -> {
                        inventoryService.restoreState(inventoryBefore);
                        equipmentRollback.run();
                        restoreClaimCheckpoint(accountId, claimBefore);
                    }
                );
            } catch (RuntimeException | Error failure) {
                inventoryService.restoreState(inventoryBefore);
                equipmentRollback.run();
                restoreClaimCheckpoint(accountId, claimBefore);
                throw failure;
            }
        }).whenComplete((freyaOrbGrant, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            Throwable cause = failure == null ? null : failure.getCause() == null ? failure : failure.getCause();
            if (cause != null) {
                finishClaim(playerId, cause instanceof AlreadyClaimedException
                    ? LoginBonusClaimResult.ALREADY_CLAIMED : LoginBonusClaimResult.FAILED, completion);
                return;
            }
            Player currentPlayer = plugin.getServer().getPlayer(playerId);
            if (currentPlayer != null && currentPlayer.isOnline()) {
                inventoryService.refreshManagedInventoryUi(astPlayer);
                if (friday && freyaOrbGrant != null && freyaOrbGrant.storedInStorage()) {
                    PlayerMessageService.getInstance().send(
                        currentPlayer, PlayerMsgId.P_5078, freyaOrbModel.getName(), freyaOrbAmount);
                }
            }
            finishClaim(playerId, LoginBonusClaimResult.CREATED, completion);
        }));
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

    private void mergeKnownClaimDates(@NotNull UUID accountId, @NotNull Set<LocalDate> claimDates) {
        synchronized (claimStateLock) {
            mergeKnownClaimDatesLocked(accountId, claimDates);
        }
    }

    private void mergeKnownClaimDatesLocked(@NotNull UUID accountId, @NotNull Set<LocalDate> claimDates) {
        knownClaimDates.compute(accountId, (ignored, current) -> {
            Set<LocalDate> merged = current == null ? new LinkedHashSet<>() : new LinkedHashSet<>(current);
            merged.addAll(claimDates);
            return Set.copyOf(merged);
        });
    }

    private boolean isClaimKnownOrPending(@NotNull UUID accountId, @NotNull LocalDate date) {
        synchronized (claimStateLock) {
            return isClaimKnownOrPendingLocked(accountId, date);
        }
    }

    private boolean isClaimKnownOrPendingLocked(@NotNull UUID accountId, @NotNull LocalDate date) {
        return knownClaimDates.getOrDefault(accountId, Set.of()).contains(date)
            || pendingClaimRevisions.getOrDefault(accountId, new LinkedHashMap<>()).containsKey(date);
    }

    private ItemModel resolveGoldRewardModel() {
        ItemModel model = itemService.findLoadedById(ItemService.DEFAULT_CURRENCY_ITEM_ID);
        if (model == null) {
            model = itemService.loadItem(ItemService.DEFAULT_CURRENCY_ITEM_ID);
        }
        return model;
    }

    private record ClaimCheckpoint(
        Long revision,
        LinkedHashMap<LocalDate, Long> pending
    ) {
    }

    private record ClaimCommitResult(boolean storedInStorage) {
    }

    private static final class AlreadyClaimedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
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
