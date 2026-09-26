package io.github.maaasu.astralRecord.feature.vip.service;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryOperationSnapshotParser;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.vip.model.AccountBenefitsSnapshot;
import io.github.maaasu.astralRecord.feature.vip.repository.AccountBenefitsRepository;
import io.github.maaasu.astralRecord.feature.vip.repository.PendingPriorityJournal;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 有償特典の原子的操作とアカウント別表示キャッシュを管理します。 */
public final class AccountBenefitsService {
    private final AstralRecord plugin;
    private final InventoryService inventory;
    private final InventorySaveCoordinator saves;
    private final AccountBenefitsRepository repository = new AccountBenefitsRepository();
    private final Map<UUID, AccountBenefitsSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    private final Set<UUID> usingItems = ConcurrentHashMap.newKeySet();
    private BukkitTask refreshTask;
    private final PendingPriorityJournal priorityJournal;

    /** 特典サービスを構築します。保存laneは通常保存との順序を保証します。 */
    public AccountBenefitsService(AstralRecord plugin, InventoryService inventory, InventorySaveCoordinator saves) {
        this.plugin = plugin;
        this.inventory = inventory;
        this.saves = saves;
        this.priorityJournal = new PendingPriorityJournal(plugin.getDataFolder().toPath().resolve("pending-instance-priority.properties"));
    }

    /** 特典表示を1分ごとに更新し、オンライン中の期限切れもTABへ反映します。 */
    public void start() {
        priorityJournal.pending().forEach((operationId, accountId) ->
            request(accountId, "/refund-priority", operation(operationId), true)
                .thenAccept(result -> priorityJournal.complete(operationId)));
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (AstPlayer player : AstPlayerCache.getAll()) {
                request(player.getAccount().getUuid(), "", null, false).thenAccept(result -> refreshDisplay(player));
            }
        }, 1200L, 1200L);
    }

    /** 新規の定期読み取りを停止します。 */
    public void stop() { if (refreshTask != null) refreshTask.cancel(); }

    /** アカウントの確定済み特典を返します。未取得時には特権を付与しません。 */
    public AccountBenefitsSnapshot current(UUID accountId) {
        return snapshots.getOrDefault(accountId, AccountBenefitsSnapshot.EMPTY);
    }

    /** 待機列の事前表示用に確定済み回数を返します。 */
    public long getInstancePriorityUses(UUID accountId) { return current(accountId).instancePriorityUses(); }

    /** ログイン日の特典を冪等に付与し、通常/VIPと残日数を通知します。 */
    public void onLogin(AstPlayer player) {
        request(player.getAccount().getUuid(), "/login", operation(UUID.randomUUID()), true).thenAccept(result -> {
            if (!isCurrent(player)) return;
            AccountBenefitsSnapshot state = current(player.getAccount().getUuid());
            if (state.tier().equals("NONE")) send(player, PlayerMsgId.P_7600);
            else send(player, PlayerMsgId.P_7601, state.tier(), state.remainingDays());
            if (result.has("awardedPriorityUses") && result.get("awardedPriorityUses").getAsInt() > 0)
                send(player, PlayerMsgId.P_7602, result.get("awardedPriorityUses").getAsInt(), state.instancePriorityUses());
            refreshDisplay(player);
        });
    }

    /** 待機操作IDごとに1回だけ消費します。通信不明時は同じIDで再試行し、main threadで完了します。 */
    public CompletableFuture<Boolean> consumeInstancePriority(AstPlayer player, UUID operationId) {
        priorityJournal.add(operationId, player.getAccount().getUuid());
        return request(player.getAccount().getUuid(), "/consume-priority", operation(operationId), true)
            .thenApply(result -> {
                boolean completed = completed(result);
                if (!completed) priorityJournal.complete(operationId);
                if (completed && isCurrent(player)) send(player, PlayerMsgId.P_7603,
                    current(player.getAccount().getUuid()).instancePriorityUses());
                return completed;
            });
    }

    /** 未確定の待機が取り消された場合に、消費操作IDを指定して1回だけ返却します。 */
    public CompletableFuture<Void> refundInstancePriority(AstPlayer player, UUID operationId) {
        return request(player.getAccount().getUuid(), "/refund-priority", operation(operationId), true)
            .thenAccept(result -> {
                priorityJournal.complete(operationId);
                if (completed(result) && isCurrent(player)) send(player, PlayerMsgId.P_7604,
                    current(player.getAccount().getUuid()).instancePriorityUses());
            });
    }

    /** インスタンス開始成功時に、次回起動での未使用回数返却対象から除外します。 */
    public void confirmInstancePriority(UUID operationId) { priorityJournal.complete(operationId); }

    /** 特典専用の消耗品かを判定します。未知の通常消耗品は扱いません。 */
    public boolean supports(ItemModel model) {
        return model.getConsumable() != null && model.getConsumable().getEffects().stream().anyMatch(effect ->
            effect.getType() == ItemConsumableEffectType.INSTANCE_PRIORITY
                || effect.getType() == ItemConsumableEffectType.VIP_DONER
                || effect.getType() == ItemConsumableEffectType.VIP_ASTRALDER);
    }

    /** 右クリックされた特典券を保存後にAPIで消費し、同じtransactionで特典を付与します。 */
    public void use(AstPlayer player, EquipmentSlot hand, ItemModel model) {
        UUID accountId = player.getAccount().getUuid();
        var entry = inventory.getHotbarEntryInHand(player, hand);
        if (entry == null || !model.getId().equals(entry.getItemId()) || !usingItems.add(accountId)) return;
        UUID operationId = UUID.randomUUID();
        saves.prepareExternalOperationAfterSave(accountId).whenComplete((prepared, error) -> onMain(() -> {
            if (error != null) {
                usingItems.remove(accountId);
                if (isCurrent(player)) send(player, PlayerMsgId.P_7607);
                return;
            }
            var baselineEntry = prepared.baseline().findEntry(entry.getInventoryEntryId());
            if (baselineEntry == null || !model.getId().equals(baselineEntry.getItemId())) {
                saves.abandonPreparedExternalOperation(prepared);
                usingItems.remove(accountId);
                return;
            }
            JsonObject body = operation(operationId);
            body.addProperty("inventoryEntryId", entry.getInventoryEntryId().toString());
            body.addProperty("expectedUpdatedAt", baselineEntry.getUpdatedAt().toString());
            completeItem(player, model, prepared, entry.getInventoryEntryId(), body, false);
        }));
    }

    /** 未確定の外部操作は保存境界と同じ要求を保持し、正本照合まで再試行します。 */
    private void completeItem(AstPlayer player, ItemModel model,
                              InventorySaveCoordinator.PreparedExternalOperation prepared,
                              UUID entryId, JsonObject body, boolean retry) {
        UUID accountId = prepared.accountId();
        saves.completePreparedExternalOperation(prepared, baseline -> {
            JsonObject result = perform(accountId, "/consume-item", body);
            inventory.reconcileExternalInventoryEntries(accountId, List.of(entryId), baseline,
                InventoryOperationSnapshotParser.parse(result.get("inventorySnapshot")));
            return result;
        }).whenComplete((result, error) -> onMain(() -> {
            if (error != null) {
                if (!retry) {
                    Logger.error(LogId.E_7600, error, accountId);
                    if (isCurrent(player)) send(player, PlayerMsgId.P_7608);
                }
                if (plugin.isEnabled()) Bukkit.getScheduler().runTaskLater(plugin,
                    () -> completeItem(player, model, prepared, entryId, body, true), 100L);
                return;
            }
            usingItems.remove(accountId);
            if (!isCurrent(player)) return;
            inventory.applyHotbarInventoryToGui(player);
            if (!completed(result)) { send(player, PlayerMsgId.P_7607); return; }
            var effect = model.getConsumable().getEffects().getFirst();
            var state = current(accountId);
            if (effect.getType() == ItemConsumableEffectType.INSTANCE_PRIORITY)
                send(player, PlayerMsgId.P_7605, effect.getValue().longValue(), state.instancePriorityUses());
            else send(player, PlayerMsgId.P_7606,
                effect.getType() == ItemConsumableEffectType.VIP_ASTRALDER ? "ASTRALDER" : "DONER",
                effect.getValue().longValue());
            refreshDisplay(player);
        }));
    }

    /** アカウント別にAPI操作とキャッシュ公開を直列化し、遅いGETによる残高の巻き戻りを防ぎます。 */
    private JsonObject perform(UUID accountId, String action, JsonObject body) {
        synchronized (locks.computeIfAbsent(accountId, ignored -> new Object())) {
            try {
                JsonObject result = repository.request(accountId, action, body);
                snapshots.put(accountId, AccountBenefitsRepository.snapshot(result));
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CompletionException(e);
            } catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        }
    }

    /** 非同期HTTPの結果をメインスレッドへ返します。書込は応答不明時に同一要求で再試行します。 */
    private CompletableFuture<JsonObject> request(UUID accountId, String action, JsonObject body, boolean retry) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        attempt(accountId, action, body, retry, result, false);
        return result;
    }

    /** 冪等要求を再実行します。読取障害は既存キャッシュを維持して終了します。 */
    private void attempt(UUID accountId, String action, JsonObject body, boolean retry,
                         CompletableFuture<JsonObject> future, boolean reported) {
        if (!plugin.isEnabled()) { future.completeExceptionally(new IllegalStateException("Plugin stopped")); return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JsonObject value = perform(accountId, action, body);
                onMain(() -> future.complete(value));
            } catch (Exception error) {
                onMain(() -> {
                    if (!reported) Logger.error(LogId.E_7600, error, accountId);
                    if (retry && plugin.isEnabled()) Bukkit.getScheduler().runTaskLater(plugin,
                        () -> attempt(accountId, action, body, true, future, true), 100L);
                    else future.completeExceptionally(error);
                });
            }
        });
    }

    /** 同じ接続と選択アカウントが有効な場合にだけ表示を更新します。 */
    private void refreshDisplay(AstPlayer player) {
        if (!isCurrent(player)) return;
        plugin.getPlayerClassService().updatePlayerListName(player);
        plugin.getNetworkBridgeService().refreshPlayerMetadata(player);
    }

    /** 冪等キーだけを持つ要求を作ります。 */
    private static JsonObject operation(UUID id) {
        JsonObject result = new JsonObject(); result.addProperty("operationId", id.toString()); return result;
    }

    /** APIが確定成功を返したかを判定します。 */
    private static boolean completed(JsonObject result) {
        return result.has("status") && "COMPLETED".equals(result.get("status").getAsString());
    }

    /** 選択変更や再接続前の古いruntimeへ通知しないための同一性確認です。 */
    private static boolean isCurrent(AstPlayer player) {
        return player.getBukkit().isOnline() && AstPlayerCache.get(player.getBukkit()) == player;
    }

    /** 全てのプレイヤー通知を共通メッセージサービスに渡します。 */
    private static void send(AstPlayer player, PlayerMsgId id, Object... args) {
        PlayerMessageService.getInstance().send(player, id, args);
    }

    /** Bukkit状態を操作する処理をmain threadへ戻します。 */
    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }
}
