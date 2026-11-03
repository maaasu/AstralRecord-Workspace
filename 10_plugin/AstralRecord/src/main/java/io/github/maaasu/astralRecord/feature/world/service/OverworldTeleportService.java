package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拠点ワールドからオーバーワールドへ移動する GUI 向けのワールド選択と転送を扱います。
 */
public final class OverworldTeleportService {
    private final Plugin plugin;
    private final WorldService worldService;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final Map<UUID, CompletableFuture<Boolean>> pendingPayments = new ConcurrentHashMap<>();

    /**
     * サービスを初期化します。
     *
     * @param plugin プラグイン本体
     * @param worldService ワールドサービス
     * @param inventoryService 通貨所持数を確認するインベントリサービス
     * @param itemService 必要アイテムのマスタを解決するサービス
     */
    public OverworldTeleportService(
            @NotNull Plugin plugin,
            @NotNull WorldService worldService,
            @NotNull InventoryService inventoryService,
            @NotNull ItemService itemService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
    }

    /**
     * GUI と転送判定で共用する必要アイテムの表示・所持条件を返します。通信や消費は行いません。
     *
     * @param astPlayer 閲覧者。未ロード時は所持数を0として扱う
     * @param destination 転送先定義
     * @return 必要アイテム名、所持数、消費設定と転送可否。未解決マスタは転送不可
     */
    public @NotNull RequiredItemPreview previewRequiredItem(
            @Nullable AstPlayer astPlayer, @NotNull WorldMasterData destination
    ) {
        String itemId = destination.requiredItemId();
        if (itemId == null) {
            return new RequiredItemPreview(false, true, "", 0L, false);
        }
        ItemModel item = itemId.isBlank() ? null : itemService.findLoadedById(itemId);
        boolean valid = item != null && ItemCategory.fromApiValue(item.getCategory()) == ItemCategory.CURRENCY;
        long owned = valid && astPlayer != null
                ? inventoryService.getCurrencyAmount(astPlayer.getAccount().getUuid(), itemId) : 0L;
        return new RequiredItemPreview(true, valid,
                valid ? item.getName() : "未登録のアイテム", owned, destination.consumeRequiredItem());
    }

    /**
     * 必要アイテムの表示と条件判定に使う読み取り専用の結果です。
     *
     * @param required 必要アイテムの指定があるか
     * @param valid Currency マスタを解決できたか
     * @param displayName 必要アイテムの表示名。内部 ID は含めない
     * @param ownedAmount 通貨インベントリの所持数
     * @param consumed 転送成功時に1個消費するか
     */
    public record RequiredItemPreview(boolean required, boolean valid, @NotNull String displayName,
                                      long ownedAmount, boolean consumed) {
        /**
         * 必要な Currency を1個以上所持しているか、入場条件がない場合に転送を許可します。
         * @return 転送条件を満たす場合は {@code true}
         */
        public boolean canTeleport() {
            return !required || valid && ownedAmount >= 1L;
        }
    }

    /**
     * GUI に表示するオーバーワールド一覧を返します。
     *
     * @return 表示順に整列した `WorldMasterData`
     */
    public @NotNull List<WorldMasterData> listDestinations() {
        Map<Integer, WorldMasterData> destinationBySlot = new TreeMap<>();
        worldService.getAll().stream()
                .filter(world -> world.worldType() == WorldType.OVERWORLD)
                .filter(world -> world.overworldTeleportGui() != null
                        && world.overworldTeleportGui().hasValidSlot())
                .sorted(Comparator.comparing(WorldMasterData::id))
                .forEach(world -> destinationBySlot.putIfAbsent(world.overworldTeleportGui().slot(), world));
        return List.copyOf(destinationBySlot.values());
    }

    /**
     * 現在ワールドが拠点ワールドかを返します。
     *
     * @param world Bukkit ワールド
     * @return 拠点ワールドなら {@code true}
     */
    public boolean isBaseWorld(@Nullable World world) {
        if (world == null) {
            return false;
        }
        WorldMasterData data = worldService.findByBukkitWorld(world);
        return data != null && data.worldType() == WorldType.BASE;
    }

    /**
     * 指定ワールド ID のオーバーワールドへ転送します。
     * 必要な Currency を確認し、消費設定が有効なら転送成功時に1個だけ消費・保存します。
     *
     * @param player 対象プレイヤー
     * @param astPlayer AstralRecord プレイヤー
     * @param worldId 転送先ワールド ID
     */
    public void teleportToDestination(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull String worldId
    ) {
        WorldMasterData destination = worldService.getById(worldId);
        if (destination == null || destination.worldType() != WorldType.OVERWORLD) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5766, worldId, worldId);
            return;
        }

        if (!hasRequiredItem(astPlayer, destination)) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5777);
            return;
        }

        if (pendingPayments.containsKey(player.getUniqueId())) {
            return;
        }

        String displayName = ColorCodeUtil.toPlainText(destination.displayName(), destination.worldType().getRegionDisplayName());
        player.closeInventory();
        CompletableFuture<Boolean> teleport = destination.requiredItemId() != null && destination.consumeRequiredItem()
                ? teleportWithPayment(player, astPlayer, destination)
                : worldService.teleportToSpawnAsync(player, destination, null,
                        () -> hasRequiredItem(astPlayer, destination));
        teleport.whenComplete((success, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null || !Boolean.TRUE.equals(success)) {
                        if (!hasRequiredItem(astPlayer, destination)) {
                            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5777);
                        } else {
                            PlayerMessageService.getInstance().send(
                                    astPlayer,
                                    PlayerMsgId.P_5766,
                                    displayName,
                                    destination.id()
                            );
                        }
                    }
                })
        );
    }

    /**
     * 消費ありの転送中に別のインベントリ画面を開かせないための状態を返します。
     * @param player 対象プレイヤー
     * @return 支払いと転送が処理中なら {@code true}
     */
    public boolean isPaymentPending(@NotNull Player player) {
        return pendingPayments.containsKey(player.getUniqueId());
    }

    /**
     * Plugin 停止時に待機を終了し、保存 lane が転送結果を待ち続けないようにします。
     */
    public void stop() {
        pendingPayments.values().forEach(result -> result.complete(false));
    }

    /**
     * 既存のアカウント保存境界で state を保持し、転送成功時だけ必要な Currency を消費します。
     * 保存 lane の待機はバックグラウンドに限定し、Bukkit 操作はメインスレッドへ戻します。
     * @param player 対象プレイヤー
     * @param astPlayer 支払い元アカウント
     * @param destination 消費ありの転送先
     * @return 転送と支払い・保存を完了した結果
     */
    private @NotNull CompletableFuture<Boolean> teleportWithPayment(
            @NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull WorldMasterData destination
    ) {
        UUID playerId = player.getUniqueId();
        UUID accountId = astPlayer.getAccount().getUuid();
        CompletableFuture<Boolean> transfer = new CompletableFuture<>();
        if (pendingPayments.putIfAbsent(playerId, transfer) != null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> payment;
        try {
            payment = inventoryService.executeExternalMutationAfterSave(accountId, () -> {
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> startPaidTransfer(player, astPlayer, destination, transfer));
                } catch (RuntimeException failure) {
                    transfer.completeExceptionally(failure);
                }
                return transfer.join();
            });
        } catch (RuntimeException failure) {
            pendingPayments.remove(playerId, transfer);
            return CompletableFuture.failedFuture(failure);
        }
        return payment.whenComplete((success, failure) -> {
            pendingPayments.remove(playerId, transfer);
            if (failure != null) {
                Logger.log(LogId.E_5755, failure, player.getName(), "paid_teleport");
            }
        });
    }

    /**
     * 保存境界の準備完了後に転送し、同じメインスレッドの完了処理で1個消費します。
     * @param player 対象プレイヤー
     * @param astPlayer 支払い元アカウント
     * @param destination 消費ありの転送先
     * @param transfer 保存 lane へ返す転送結果
     */
    private void startPaidTransfer(
            @NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull WorldMasterData destination,
            @NotNull CompletableFuture<Boolean> transfer
    ) {
        if (transfer.isDone()) {
            return;
        }
        try {
            worldService.teleportToSpawnAsync(player, destination, null,
                    () -> !transfer.isDone() && player.isOnline() && AstPlayerCache.get(player) == astPlayer
                            && hasRequiredItem(astPlayer, destination))
                    .whenComplete((success, failure) -> {
                        if (failure != null) {
                            transfer.completeExceptionally(failure);
                        } else if (!Boolean.TRUE.equals(success)) {
                            transfer.complete(false);
                        } else {
                            try {
                                if (!inventoryService.consumeCurrency(astPlayer.getAccount().getUuid(),
                                        destination.requiredItemId(), 1L)) {
                                    throw new IllegalStateException("Teleport currency payment could not be committed");
                                }
                                transfer.complete(true);
                            } catch (RuntimeException paymentFailure) {
                                transfer.completeExceptionally(paymentFailure);
                            }
                        }
                    });
        } catch (RuntimeException failure) {
            transfer.completeExceptionally(failure);
        }
    }

    /**
     * GUI と共通の条件で、必要 Currency の参照と所持数を検証します。
     * @param astPlayer 所持数を確認するアカウント
     * @param destination 転送先
     * @return 条件がないか、有効な必要 Currency を1個以上所持している場合は {@code true}
     */
    private boolean hasRequiredItem(@NotNull AstPlayer astPlayer, @NotNull WorldMasterData destination) {
        return previewRequiredItem(astPlayer, destination).canTeleport();
    }
}
