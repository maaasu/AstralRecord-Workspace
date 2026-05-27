package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutSlotModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryDraft;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class InventoryService {
    private static final InventoryProfile DEFAULT_PROFILE = InventoryProfile.GAME;
    private static final String DEFAULT_LOADOUT_NAME = "Default";
    private static final String SLOT_TYPE_HEAD = "HEAD";
    private static final String SLOT_TYPE_CHEST = "CHEST";
    private static final String SLOT_TYPE_LEGS = "LEGS";
    private static final String SLOT_TYPE_FEET = "FEET";
    private static final String SLOT_TYPE_ACCESSORY = "ACCESSORY";

    private final InventoryRepository inventoryRepository;
    private final EquipmentLoadoutRepository equipmentLoadoutRepository;
    private final ItemService itemService;
    private final InventoryItemStackResolver itemStackResolver;
    private final InventorySnapshotCodec snapshotCodec;
    private final InventoryEntryWriteBuffer entryWriteBuffer;
    private final HotbarRenderer hotbarRenderer;
    private final Map<UUID, List<InventoryModel>> inventoryCache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<InventoryModel>> pendingInventoryCreates = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> persistedInventoryIds = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pendingWriteTasks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refreshingInventories = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refreshingLoadouts = ConcurrentHashMap.newKeySet();
    private final Map<UUID, InventoryType> displayedInventoryTypes = new ConcurrentHashMap<>();
    private final Map<UUID, List<EquipmentLoadoutModel>> equipmentLoadoutCache = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, InventoryEntryModel>> hotbarEntryCache = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedHotbarSlots = new ConcurrentHashMap<>();
    /** GUI（プレイヤーインベントリ）を開いている間、ホットバーをショートカット表示モードへ切り替える */
    private final Set<UUID> hotbarShortcutMode = ConcurrentHashMap.newKeySet();
    /** クリック連打抑止用のクールタイム管理。 */
    private final InventoryClickGuard clickGuard = new InventoryClickGuard();
    /** 装備ロードアウト書き込み直列化用のチェーン末尾。 */
    private final Map<UUID, CompletableFuture<Void>> loadoutWriteChains = new ConcurrentHashMap<>();
    /** 装備ロードアウト書き込み件数。0 より大きい間は async refresh を抑止する。 */
    private final Map<UUID, AtomicInteger> loadoutWriteCounts = new ConcurrentHashMap<>();
    /** entry 書き込みを inventoryId 単位で 1 秒窓に集約する coalescer。 */
    private final InventoryWriteCoalescer entryCoalescer = new InventoryWriteCoalescer("InventoryEntryCoalesce");
    /** metadata 書き込みを inventoryId 単位で 1 秒窓に集約する coalescer。 */
    private final InventoryWriteCoalescer metadataCoalescer = new InventoryWriteCoalescer("InventoryMetadataCoalesce");
    /** 装備ロードアウト書き込みを accountId 単位で 1 秒窓に集約する coalescer。 */
    private final InventoryWriteCoalescer loadoutCoalescer = new InventoryWriteCoalescer("InventoryLoadoutCoalesce");

    public InventoryService(
        InventoryRepository inventoryRepository,
        EquipmentLoadoutRepository equipmentLoadoutRepository,
        ItemService itemService,
        ItemStackFactory itemStackFactory
    ) {
        this.inventoryRepository = inventoryRepository;
        this.equipmentLoadoutRepository = equipmentLoadoutRepository;
        this.itemService = itemService;
        this.itemStackResolver = new InventoryItemStackResolver(itemService, itemStackFactory);
        this.snapshotCodec = new InventorySnapshotCodec();
        this.entryWriteBuffer = new InventoryEntryWriteBuffer(
            inventoryRepository,
            pendingInventoryCreates,
            persistedInventoryIds,
            pendingWriteTasks,
            entryCoalescer
        );
        this.hotbarRenderer = new HotbarRenderer(itemStackResolver);
    }

    /**
     * クリック連打抑止用のクールタイム管理を返します。
     *
     * @return InventoryClickGuard インスタンス
     */
    public @NotNull InventoryClickGuard getClickGuard() {
        return clickGuard;
    }

    /**
     * プレイヤー退出時などにクリッククールタイム情報を破棄します。
     *
     * @param accountId 対象アカウントID
     */
    public void clearClickGuard(@NotNull UUID accountId) {
        clickGuard.clear(accountId);
    }

    public List<InventoryModel> getInventories(UUID accountId) {
        refreshInventoriesAsync(accountId);
        return getCachedInventories(accountId);
    }

    public InventoryModel getInventory(UUID inventoryId) {
        return findCachedInventory(inventoryId);
    }

    public List<InventoryEntryModel> getEntries(UUID inventoryId) {
        entryWriteBuffer.refreshEntriesAsync(inventoryId);
        return getCachedEntries(inventoryId);
    }

    public InventoryModel ensureInventory(
        UUID accountId,
        InventoryType inventoryType,
        Integer slotCapacity,
        UUID createdBy
    ) {
        InventoryModel cached = getCachedInventories(accountId).stream()
            .filter(inventory -> inventory.getInventoryType() == inventoryType)
            .filter(this::isDefaultProfile)
            .findFirst()
            .orElse(null);
        if (cached != null) {
            return cached;
        }

        return createInventoryOptimistically(accountId, inventoryType, slotCapacity, createdBy, DEFAULT_PROFILE, null);
    }

    /**
     * 表示用キャッシュからアカウントのインベントリ一覧を取得します。
     *
     * @param accountId 対象アカウントID
     * @return キャッシュ済みインベントリ一覧
     */
    private @NotNull List<InventoryModel> getCachedInventories(@NotNull UUID accountId) {
        return inventoryCache.getOrDefault(accountId, List.of());
    }

    /**
     * 表示用キャッシュからインベントリ内の entry 一覧を取得します。
     *
     * @param inventoryId 対象インベントリID
     * @return キャッシュ済み entry 一覧
     */
    private @NotNull List<InventoryEntryModel> getCachedEntries(@NotNull UUID inventoryId) {
        return entryWriteBuffer.getCachedEntries(inventoryId);
    }

    private @Nullable InventoryModel findCachedInventory(@NotNull UUID inventoryId) {
        return inventoryCache.values().stream()
            .flatMap(List::stream)
            .filter(inventory -> inventory.getInventoryId().equals(inventoryId))
            .findFirst()
            .orElse(null);
    }

    private void refreshInventoriesAsync(@NotNull UUID accountId) {
        if (!refreshingInventories.add(accountId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                List<InventoryModel> inventories = inventoryRepository.findByAccountId(accountId);
                inventoryCache.put(accountId, List.copyOf(inventories));
                for (InventoryModel inventory : inventories) {
                    entryWriteBuffer.refreshEntries(inventory.getInventoryId());
                }
            } catch (RuntimeException e) {
                Logger.warn(LogId.W_5252, accountId, e.getMessage());
            } finally {
                refreshingInventories.remove(accountId);
            }
        });
    }

    private void refreshEquipmentLoadoutsAsync(@NotNull UUID accountId) {
        if (hasPendingLoadoutWrites(accountId)) {
            // 楽観反映済みのキャッシュを古い DB 状態で上書きしないよう、書き込み完了まで refresh をスキップする。
            return;
        }
        if (!refreshingLoadouts.add(accountId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                if (hasPendingLoadoutWrites(accountId)) {
                    return;
                }
                List<EquipmentLoadoutModel> loadouts = equipmentLoadoutRepository.findByAccountId(accountId, DEFAULT_PROFILE);
                equipmentLoadoutCache.put(accountId, List.copyOf(loadouts));
            } catch (RuntimeException e) {
                Logger.warn(LogId.W_5253, accountId, e.getMessage());
            } finally {
                refreshingLoadouts.remove(accountId);
            }
        });
    }

    private boolean hasPendingLoadoutWrites(@NotNull UUID accountId) {
        if (loadoutCoalescer.hasPending(accountId)) {
            return true;
        }
        AtomicInteger count = loadoutWriteCounts.get(accountId);
        return count != null && count.get() > 0;
    }

    private void refreshDisplayedInventoryForGuiAsync(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryType inventoryType
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        CompletableFuture.supplyAsync(() -> {
            List<InventoryModel> inventories = inventoryRepository.findByAccountId(accountId);
            inventoryCache.put(accountId, List.copyOf(inventories));
            InventoryModel selected = inventories.stream()
                .filter(this::isDefaultProfile)
                .filter(inventory -> inventory.getInventoryType() == inventoryType)
                .findFirst()
                .orElse(null);
            if (selected != null) {
                entryWriteBuffer.refreshEntries(selected.getInventoryId());
            }
            return selected;
        }).thenAccept(selected -> {
            AstralRecord plugin = AstralRecord.getInstance();
            if (plugin == null || selected == null || !astPlayer.getBukkit().isOnline()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (astPlayer.getAccount().getMode().shouldReflectInventoryToGui()
                    && getDisplayedInventoryType(accountId) == inventoryType) {
                    applyInventoryToGui(astPlayer.getBukkit(), selected);
                    astPlayer.getBukkit().updateInventory();
                }
            });
        }).exceptionally(error -> {
            Logger.warn(LogId.W_5252, accountId, error.getMessage());
            return null;
        });
    }

    private @NotNull InventoryModel createInventoryOptimistically(
        @NotNull UUID accountId,
        @NotNull InventoryType inventoryType,
        @Nullable Integer slotCapacity,
        @NotNull UUID createdBy,
        @NotNull InventoryProfile profile,
        @Nullable String metadataJson
    ) {
        UUID temporaryId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        InventoryModel optimistic = new InventoryModel(
            temporaryId,
            accountId,
            inventoryType,
            profile.getCode(),
            slotCapacity,
            true,
            metadataJson,
            now,
            now,
            createdBy,
            createdBy,
            false
        );
        putInventoryInCache(optimistic);

        CompletableFuture<InventoryModel> future = CompletableFuture.supplyAsync(() ->
            inventoryRepository.create(accountId, inventoryType, slotCapacity, createdBy, profile, metadataJson)
        ).whenComplete((saved, error) -> {
            pendingInventoryCreates.remove(temporaryId);
            if (error != null) {
                Logger.warn(LogId.W_5252, accountId, error.getMessage());
                return;
            }
            replaceInventoryInCache(temporaryId, saved);
        });
        pendingInventoryCreates.put(temporaryId, future);
        return optimistic;
    }

    private @NotNull List<InventoryEntryModel> replaceEntriesOptimistically(
        @NotNull UUID inventoryId,
        @NotNull List<InventoryEntryDraft> drafts,
        @NotNull UUID updatedBy
    ) {
        return entryWriteBuffer.replaceEntriesOptimistically(inventoryId, drafts, updatedBy);
    }

    private void updateMetadataAsync(
        @NotNull UUID inventoryId,
        @Nullable String metadataJson,
        @NotNull UUID updatedBy
    ) {
        // キャッシュは即時更新し、API 書き込みは coalescer で 1 秒窓に集約する。
        updateInventoryMetadataInCache(inventoryId, metadataJson, updatedBy);
        MetadataPayload payload = new MetadataPayload(metadataJson, updatedBy);
        metadataCoalescer.submit(
            inventoryId,
            payload,
            latest -> executeMetadataUpdate(inventoryId, latest)
        );
    }

    private void executeMetadataUpdate(@NotNull UUID inventoryId, @NotNull MetadataPayload payload) {
        String metadataJson = payload.metadataJson();
        UUID updatedBy = payload.updatedBy();
        CompletableFuture<InventoryModel> pendingInventory = pendingInventoryCreates.get(inventoryId);
        CompletableFuture<?> updateFuture = pendingInventory == null
            ? CompletableFuture.runAsync(() -> inventoryRepository.updateMetadata(inventoryId, metadataJson, updatedBy))
            : pendingInventory.thenAccept(savedInventory ->
                inventoryRepository.updateMetadata(savedInventory.getInventoryId(), metadataJson, updatedBy)
            );
        trackWriteTask(updateFuture.exceptionally(error -> {
            Logger.warn(LogId.W_5252, inventoryId, error.getMessage());
            return null;
        }));
    }

    /** metadata 書き込みを coalescer に詰めるためのペイロード。 */
    private record MetadataPayload(@Nullable String metadataJson, @NotNull UUID updatedBy) {
    }

    private void trackWriteTask(@NotNull CompletableFuture<?> future) {
        pendingWriteTasks.add(future);
        future.whenComplete((ignored, error) -> pendingWriteTasks.remove(future));
    }

    /**
     * 指定アカウントに紐づく coalescer 保留分を即時 flush します。
     * <p>
     * プレイヤー退出時など、debounce 窓内で保持していた最新状態を確実に API へ送りたい場面で呼び出してください。
     *
     * @param accountId 対象アカウントID
     */
    public void flushPendingCoalescedWrites(@NotNull UUID accountId) {
        loadoutCoalescer.flushNow(accountId);
        for (InventoryModel inventory : getCachedInventories(accountId)) {
            entryCoalescer.flushNow(inventory.getInventoryId());
            metadataCoalescer.flushNow(inventory.getInventoryId());
        }
    }

    /**
     * 未完了の非同期保存処理を指定時間だけ待機します。
     * プラグイン停止時、DB 接続を閉じる前に呼び出してください。
     *
     * @param timeoutMillis 最大待機時間（ミリ秒）
     */
    public void awaitPendingWrites(long timeoutMillis) {
        // 保留中の debounce を即時 flush し、その先の async write を待機対象へ流す。
        entryCoalescer.flushAll();
        metadataCoalescer.flushAll();
        loadoutCoalescer.flushAll();

        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        for (CompletableFuture<?> future : List.copyOf(pendingWriteTasks)) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                break;
            }
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Logger.warn(LogId.W_5252, "shutdown", e.getMessage());
            }
        }

        entryCoalescer.shutdown();
        metadataCoalescer.shutdown();
        loadoutCoalescer.shutdown();
    }

    private void putInventoryInCache(@NotNull InventoryModel inventory) {
        inventoryCache.compute(inventory.getAccountId(), (accountId, current) -> {
            List<InventoryModel> next = new ArrayList<>(current == null ? List.of() : current);
            next.removeIf(cached -> cached.getInventoryId().equals(inventory.getInventoryId()));
            next.add(inventory);
            return Collections.unmodifiableList(next);
        });
    }

    private void replaceInventoryInCache(@NotNull UUID temporaryId, @NotNull InventoryModel saved) {
        persistedInventoryIds.put(temporaryId, saved.getInventoryId());
        inventoryCache.compute(saved.getAccountId(), (accountId, current) -> {
            List<InventoryModel> next = new ArrayList<>(current == null ? List.of() : current);
            next.removeIf(cached -> cached.getInventoryId().equals(temporaryId) || cached.getInventoryId().equals(saved.getInventoryId()));
            next.add(saved);
            return Collections.unmodifiableList(next);
        });

        List<InventoryEntryModel> temporaryEntries = entryWriteBuffer.removeEntriesFromCache(temporaryId);
        if (!temporaryEntries.isEmpty()) {
            List<InventoryEntryModel> remapped = temporaryEntries.stream()
                .map(entry -> new InventoryEntryModel(
                    entry.getInventoryEntryId(),
                    saved.getInventoryId(),
                    entry.getSlotIndex(),
                    entry.getItemCategory(),
                    entry.getItemId(),
                    entry.getInstanceType(),
                    entry.getInstanceId(),
                    entry.getQuantity(),
                    entry.getMetadataJson(),
                    entry.getCreatedAt(),
                    entry.getUpdatedAt(),
                    entry.getCreatedBy(),
                    entry.getUpdatedBy(),
                    entry.isDeleted()
                ))
                .toList();
            entryWriteBuffer.putEntriesInCache(saved.getInventoryId(), remapped);
        }
    }

    private void updateInventoryMetadataInCache(
        @NotNull UUID inventoryId,
        @Nullable String metadataJson,
        @NotNull UUID updatedBy
    ) {
        InventoryModel cached = findCachedInventory(inventoryId);
        if (cached == null) {
            return;
        }
        InventoryModel updated = new InventoryModel(
            cached.getInventoryId(),
            cached.getAccountId(),
            cached.getInventoryType(),
            cached.getInventoryProfile(),
            cached.getSlotCapacity(),
            cached.isEnabled(),
            metadataJson,
            cached.getCreatedAt(),
            LocalDateTime.now(),
            cached.getCreatedBy(),
            updatedBy,
            cached.isDeleted()
        );
        putInventoryInCache(updated);
    }

    /**
     * 通常インベントリへアイテムをデータとして追加します。
     * <p>
     * 追加対象スロットは 9〜35（上段 3x9 の 27 マス）のみです。
     * 0〜8（ツールバー）は追加判定から除外します。
     *
     * @param accountId 追加対象アカウントID
     * @param model 追加するアイテム定義
     * @param amount 追加希望個数（1未満は1として扱う）
     * @return 実際に追加できた個数
     */
    public int addItemToNormalInventory(
        @NotNull UUID accountId,
        @NotNull ItemModel model,
        int amount
    ) {
        var safeAmount = Math.max(1, amount);
        InventoryType inventoryType = resolveTargetInventoryType(model);
        var targetInventory = ensureInventory(accountId, inventoryType, resolveSlotCapacity(inventoryType), accountId);
        Set<Integer> usedSlots = collectUsedSlots(targetInventory);

        return switch (ItemCategory.fromApiValue(model.getCategory())) {
            case EQUIPMENT -> addInstanceItems(targetInventory, model, safeAmount, InventoryInstanceType.EQUIPMENT, usedSlots, accountId);
            case RUNE -> addInstanceItems(targetInventory, model, safeAmount, InventoryInstanceType.RUNE, usedSlots, accountId);
            default -> addStackedItems(targetInventory, model, safeAmount, usedSlots, accountId);
        };
    }

    /**
     * 通常インベントリへアイテムを追加し、現在の Bukkit インベントリ状態も考慮して空きスロットを決定します。
     * <p>
     * オンラインプレイヤーへコマンドなどで即時付与する場合はこちらを使用します。
     *
     * @param astPlayer 追加対象プレイヤー
     * @param model 追加するアイテム定義
     * @param amount 追加希望個数（1未満は1として扱う）
     * @return 実際に追加できた個数
     */
    public int addItemToNormalInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemModel model,
        int amount
    ) {
        var accountId = astPlayer.getAccount().getUuid();
        int safeAmount = Math.max(1, amount);
        InventoryType inventoryType = resolveTargetInventoryType(model);
        var targetInventory = ensureInventory(accountId, inventoryType, resolveSlotCapacity(inventoryType), accountId);

        Set<Integer> usedSlots = collectUsedSlots(targetInventory);

        int granted = switch (ItemCategory.fromApiValue(model.getCategory())) {
            case EQUIPMENT -> addInstanceItems(targetInventory, model, safeAmount, InventoryInstanceType.EQUIPMENT, usedSlots, accountId);
            case RUNE -> addInstanceItems(targetInventory, model, safeAmount, InventoryInstanceType.RUNE, usedSlots, accountId);
            default -> addStackedItems(targetInventory, model, safeAmount, usedSlots, accountId);
        };
        if (granted > 0) {
            autoSwitchDisplayedInventory(astPlayer, inventoryType);
        }
        return granted;
    }

    /**
     * BUILDER モードのインベントリ全体（storage + armor + offhand）を
     * BUILDER プロファイルの NORMAL インベントリ metadataJson へスナップショット保存します。
     *
     * @param astPlayer 保存対象プレイヤー
     */
    public void saveBuilderInventorySnapshot(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryModel inventory = ensureBuilderInventory(accountId);
        ItemStack[] contents = astPlayer.getBukkit().getInventory().getContents();
        String metadataJson = snapshotCodec.encodeBuilder(contents, astPlayer.getBukkit().getGameMode());
        updateMetadataAsync(inventory.getInventoryId(), metadataJson, accountId);
    }

    /**
     * BUILDER プロファイルに保存されたスナップショットをプレイヤーのインベントリへ復元します。
     * <p>
     * キャッシュにある場合はメインスレッドで即時反映し、最新内容を非同期で取り直して再描画します。
     *
     * @param astPlayer 復元対象プレイヤー
     */
    public void applyBuilderInventoryToGui(@NotNull AstPlayer astPlayer) {
        clearGuiInventory(astPlayer.getBukkit());
        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryModel cached = getCachedInventories(accountId).stream()
            .filter(this::isBuilderProfile)
            .filter(inv -> inv.getInventoryType() == InventoryType.NORMAL)
            .findFirst()
            .orElse(null);
        if (cached != null) {
            applyBuilderSnapshot(astPlayer, cached.getMetadataJson());
        }
        refreshBuilderInventoryAsync(astPlayer, accountId);
    }

    private void refreshBuilderInventoryAsync(@NotNull AstPlayer astPlayer, @NotNull UUID accountId) {
        CompletableFuture.supplyAsync(() -> {
            List<InventoryModel> inventories = inventoryRepository.findByAccountId(accountId);
            inventoryCache.put(accountId, List.copyOf(inventories));
            return inventories.stream()
                .filter(this::isBuilderProfile)
                .filter(inv -> inv.getInventoryType() == InventoryType.NORMAL)
                .findFirst()
                .orElse(null);
        }).thenAccept(inventory -> {
            AstralRecord plugin = AstralRecord.getInstance();
            if (plugin == null || inventory == null || !astPlayer.getBukkit().isOnline()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (astPlayer.getAccount().getMode() != AccountMode.BUILDER) {
                    return;
                }
                applyBuilderSnapshot(astPlayer, inventory.getMetadataJson());
            });
        }).exceptionally(error -> {
            Logger.warn(LogId.W_5252, accountId, error.getMessage());
            return null;
        });
    }

    private void applyBuilderSnapshot(@NotNull AstPlayer astPlayer, @Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        InventorySnapshotCodec.BuilderSnapshot snapshot = snapshotCodec.decodeBuilder(metadataJson);
        if (snapshot == null) {
            return;
        }
        astPlayer.getBukkit().getInventory().setContents(snapshot.contents());
        if (snapshot.gameMode() != null) {
            astPlayer.getBukkit().setGameMode(snapshot.gameMode());
        }
        astPlayer.getBukkit().updateInventory();
    }

    private @NotNull InventoryModel ensureBuilderInventory(@NotNull UUID accountId) {
        InventoryModel cached = getCachedInventories(accountId).stream()
            .filter(this::isBuilderProfile)
            .filter(inv -> inv.getInventoryType() == InventoryType.NORMAL)
            .findFirst()
            .orElse(null);
        if (cached != null) {
            return cached;
        }
        return createInventoryOptimistically(
            accountId,
            InventoryType.NORMAL,
            null,
            accountId,
            InventoryProfile.BUILDER,
            null
        );
    }

    private boolean isBuilderProfile(@NotNull InventoryModel inventory) {
        return InventoryProfile.BUILDER.getCode().equalsIgnoreCase(inventory.getInventoryProfile());
    }

    public void applyInventoriesToGui(AstPlayer astPlayer) {
        applyInventoryToGui(astPlayer, InventoryType.NORMAL);
        if (!applyActiveEquipmentLoadoutToGui(astPlayer)) {
            applyEquipSlotInventoryToGui(astPlayer);
            applyAccessorySlotInventoryToGui(astPlayer);
        }
        applyHotbarInventoryToGui(astPlayer);
        refreshDisplayedInventoryForGuiAsync(astPlayer, InventoryType.NORMAL);
    }

    public List<EquipmentLoadoutModel> getEquipmentLoadouts(UUID accountId) {
        refreshEquipmentLoadoutsAsync(accountId);
        return equipmentLoadoutCache.getOrDefault(accountId, List.of());
    }

    public @Nullable EquipmentLoadoutModel getActiveEquipmentLoadout(UUID accountId) {
        refreshEquipmentLoadoutsAsync(accountId);
        return equipmentLoadoutCache.getOrDefault(accountId, List.of()).stream()
            .filter(loadout -> loadout.isActive() && !loadout.isDeleted())
            .findFirst()
            .orElse(null);
    }

    public @Nullable EquipmentLoadoutModel ensureActiveEquipmentLoadout(UUID accountId) {
        EquipmentLoadoutModel active = getActiveEquipmentLoadout(accountId);
        if (active != null) {
            return active;
        }

        try {
            List<EquipmentLoadoutModel> loadouts = equipmentLoadoutRepository.findByAccountId(accountId, DEFAULT_PROFILE);
            equipmentLoadoutCache.put(accountId, List.copyOf(loadouts));
            if (!loadouts.isEmpty()) {
                EquipmentLoadoutModel activated = equipmentLoadoutRepository.activate(loadouts.get(0).getEquipmentLoadoutId(), accountId);
                if (activated != null) {
                    equipmentLoadoutCache.put(accountId, List.of(activated));
                    return activated;
                }
            }

            EquipmentLoadoutModel created = equipmentLoadoutRepository.create(
                accountId,
                DEFAULT_LOADOUT_NAME,
                accountId,
                DEFAULT_PROFILE,
                0,
                true,
                null
            );
            equipmentLoadoutCache.put(accountId, List.of(created));
            return created;
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5253, accountId, e.getMessage());
            return null;
        }
    }

    public InventoryType resolveInventoryType(@NotNull ItemModel model) {
        return resolveTargetInventoryType(model);
    }

    /**
     * 指定種別のデフォルトインベントリ内アイテムを GUI 表示用 ItemStack に変換します。
     *
     * @param accountId 対象アカウントID
     * @param inventoryType 取得するインベントリ種別
     * @return 表示可能な ItemStack 一覧
     */
    public @NotNull List<ItemStack> getInventoryItemStacks(
        @NotNull UUID accountId,
        @NotNull InventoryType inventoryType
    ) {
        InventoryModel inventory = getInventories(accountId).stream()
            .filter(this::isDefaultProfile)
            .filter(inv -> inv.getInventoryType() == inventoryType)
            .findFirst()
            .orElse(null);
        if (inventory == null || !inventory.isEnabled()) {
            return List.of();
        }

        return getEntries(inventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .sorted(Comparator.<InventoryEntryModel, Integer>comparing(
                entry -> entry.getSlotIndex() == null ? Integer.MAX_VALUE : entry.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .map(itemStackResolver::resolve)
            .filter(itemStack -> itemStack != null && itemStack.getType() != Material.AIR)
            .toList();
    }

    public void applyInventoryToGui(AstPlayer astPlayer, InventoryType inventoryType) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }

        if (inventoryType == InventoryType.CURRENCY) {
            clearManagedStorageSlots(astPlayer.getBukkit());
            astPlayer.getBukkit().updateInventory();
            return;
        }

        saveEquipSlotSnapshot(astPlayer);
        saveAccessorySlotSnapshot(astPlayer);
        syncCurrentEquipmentState(astPlayer);

        displayedInventoryTypes.put(astPlayer.getAccount().getUuid(), inventoryType);

        var bukkitPlayer = astPlayer.getBukkit();

        var inventories = getInventories(astPlayer.getAccount().getUuid());
        var selectedInventory = inventories.stream()
            .filter(this::isDefaultProfile)
            .filter(inventory -> inventory.getInventoryType() == inventoryType)
            .findFirst()
            .orElse(null);
        applyInventoryToGui(bukkitPlayer, selectedInventory);

        if (!applyActiveEquipmentLoadoutToGui(astPlayer)) {
            applyEquipSlotInventoryToGui(astPlayer);
            applyAccessorySlotInventoryToGui(astPlayer);
        }
        applyHotbarInventoryToGui(astPlayer);
        refreshDisplayedInventoryForGuiAsync(astPlayer, inventoryType);
    }

    public @NotNull InventoryType getDisplayedInventoryType(@NotNull UUID accountId) {
        return displayedInventoryTypes.getOrDefault(accountId, InventoryType.NORMAL);
    }

    public void clearGuiInventory(@NotNull AstPlayer astPlayer) {
        clearGuiInventory(astPlayer.getBukkit());
        astPlayer.getBukkit().updateInventory();
    }

    private void applyInventoryToGui(Player bukkitPlayer, @Nullable InventoryModel inventory) {
        if (inventory == null || !inventory.isEnabled()) {
            clearManagedStorageSlots(bukkitPlayer);
            return;
        }

        if (inventory.getInventoryType() == InventoryType.NORMAL) {
            applySlottedInventory(bukkitPlayer, inventory);
            return;
        }

        if (inventory.getInventoryType() == InventoryType.CURRENCY) {
            clearManagedStorageSlots(bukkitPlayer);
            return;
        }

        if (inventory.getInventoryType().isSlotted()) {
            applySlottedInventory(bukkitPlayer, inventory);
        }
    }

    private void applySlottedInventory(Player bukkitPlayer, InventoryModel inventory) {
        var entries = getEntries(inventory.getInventoryId());
        var playerInventory = bukkitPlayer.getInventory();
        Map<Integer, ItemStack> itemByGuiSlot = new HashMap<>();

        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex == null || !NormalInventoryLayout.isManagedSlot(slotIndex)) {
                continue;
            }
            int guiSlotIndex = NormalInventoryLayout.toGuiSlotIndex(slotIndex);
            if (guiSlotIndex < 0 || guiSlotIndex >= playerInventory.getStorageContents().length) {
                continue;
            }

            ItemStack itemStack = itemStackResolver.resolve(entry);
            if (itemStack == null) {
                continue;
            }

            itemByGuiSlot.put(guiSlotIndex, itemStack);
        }

        for (int dbSlot = NormalInventoryLayout.DB_SLOT_START; dbSlot <= NormalInventoryLayout.DB_SLOT_END; dbSlot++) {
            int guiSlot = NormalInventoryLayout.toGuiSlotIndex(dbSlot);
            setStorageItemIfChanged(playerInventory, guiSlot, itemByGuiSlot.get(guiSlot));
        }
    }

    // ---------------------------------------------------------------
    // EQUIP_SLOT
    // ---------------------------------------------------------------

    /**
     * EQUIP_SLOT インベントリをプレイヤーの装備スロット（防具・メインハンド）へ反映します。
     * <p>
     * DB にエントリが存在する場合はエントリを優先して装備します。
     * エントリがない場合でも、スナップショットが保存されていれば復元します。
     */
    public void applyEquipSlotInventoryToGui(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }

        var accountId = astPlayer.getAccount().getUuid();
        var bukkitPlayer = astPlayer.getBukkit();
        var inventories = getInventories(accountId);

        inventories.stream()
            .filter(this::isDefaultProfile)
            .filter(inv -> inv.getInventoryType() == InventoryType.EQUIP_SLOT)
            .findFirst()
            .ifPresent(inventory -> {
                var entries = getEntries(inventory.getInventoryId());
                if (inventory.getMetadataJson() != null && !inventory.getMetadataJson().isBlank()) {
                    ItemStack[] snapshot = snapshotCodec.decode(inventory.getMetadataJson());
                    if (snapshot != null) {
                        EquipSlotLayout.applySnapshot(bukkitPlayer, snapshot);
                    }
                } else if (!entries.isEmpty()) {
                    EquipSlotLayout.applyEntriesToPlayer(bukkitPlayer, entries, itemStackResolver::resolve);
                }
            });

        bukkitPlayer.updateInventory();
    }

    /**
     * プレイヤーの現在の装備スロット（防具・メインハンド）をスナップショットとして保存します。
     *
     * @param astPlayer 保存対象プレイヤー
     */
    public void saveEquipSlotSnapshot(@NotNull AstPlayer astPlayer) {
        var accountId = astPlayer.getAccount().getUuid();
        var inventory = ensureInventory(
            accountId,
            InventoryType.EQUIP_SLOT,
            EquipSlotLayout.SLOT_MAX,
            accountId
        );
        var snapshot = EquipSlotLayout.createSnapshot(astPlayer.getBukkit());
        var metadataJson = snapshotCodec.encode(snapshot);
        updateMetadataAsync(inventory.getInventoryId(), metadataJson, accountId);
    }

    /**
     * 指定エントリの装備アイテムを EQUIP_SLOT の装備部位へ移動します。
     * <p>
     * エントリを EQUIP_SLOT インベントリの entry として登録し、Bukkit インベントリへも反映します。
     * 対象スロットに既存エントリがある場合は上書きします。
     *
     * @param astPlayer  対象プレイヤー
     * @param entry      移動元エントリ（EQUIPMENT インベントリのエントリ）
     * @param slotIndex  EQUIP_SLOT のスロット番号（1=メインハンド, 2=頭, 3=胴, 4=脚, 5=足）
     */
    public void equipToSlot(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel entry,
        int slotIndex
    ) {
        if (!EquipSlotLayout.isManagedSlot(slotIndex)) {
            return;
        }

        var accountId = astPlayer.getAccount().getUuid();
        var equipInventory = ensureInventory(
            accountId,
            InventoryType.EQUIP_SLOT,
            EquipSlotLayout.SLOT_MAX,
            accountId
        );

        List<InventoryEntryDraft> drafts = getEntries(equipInventory.getInventoryId()).stream()
            .filter(e -> e.getSlotIndex() == null || e.getSlotIndex() != slotIndex || e.isDeleted())
            .filter(e -> !e.isDeleted())
            .map(e -> copyEntryDraft(e, e.getSlotIndex()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        drafts.add(copyEntryDraft(entry, slotIndex));
        replaceEntriesOptimistically(equipInventory.getInventoryId(), drafts, accountId);

        EquipmentType.fromEquipSlotIndex(slotIndex)
            .applyTo(astPlayer.getBukkit().getInventory(), itemStackResolver.resolve(entry));
        astPlayer.getBukkit().updateInventory();
    }

    // ---------------------------------------------------------------
    // HOTBAR
    // ---------------------------------------------------------------

    /**
     * HOTBAR インベントリのスナップショットをプレイヤーのホットバー（スロット 0〜8）へ反映します。
     * <p>
     * 同期キャッシュで一度描画した後、API から最新の HOTBAR インベントリ／entries を取得し、
     * メインスレッドで再描画します。ログイン直後など、キャッシュが空のままダミーアイテムだけ
     * 表示されてしまうケースを防止します。
     */
    public void applyHotbarInventoryToGui(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }

        var accountId = astPlayer.getAccount().getUuid();
        var hotbarInventory = ensureInventory(accountId, InventoryType.HOTBAR, HotbarLayout.CAPACITY, accountId);
        cacheHotbarEntries(accountId, getEntries(hotbarInventory.getInventoryId()));
        renderHotbarInventory(astPlayer);
        refreshHotbarInventoryForGuiAsync(astPlayer);
    }

    /**
     * HOTBAR インベントリ／entries を非同期で API から再取得し、
     * メインスレッドで {@code hotbarEntryCache} を再構築してからホットバーを描画し直します。
     *
     * @param astPlayer 対象プレイヤー
     */
    private void refreshHotbarInventoryForGuiAsync(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        CompletableFuture.supplyAsync(() -> {
            List<InventoryModel> inventories = inventoryRepository.findByAccountId(accountId);
            inventoryCache.put(accountId, List.copyOf(inventories));
            InventoryModel hotbar = inventories.stream()
                .filter(this::isDefaultProfile)
                .filter(inv -> inv.getInventoryType() == InventoryType.HOTBAR)
                .findFirst()
                .orElse(null);
            if (hotbar == null) {
                return null;
            }
            entryWriteBuffer.refreshEntries(hotbar.getInventoryId());
            return hotbar.getInventoryId();
        }).thenAccept(hotbarInventoryId -> {
            AstralRecord plugin = AstralRecord.getInstance();
            if (plugin == null || hotbarInventoryId == null || !astPlayer.getBukkit().isOnline()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
                    return;
                }
                cacheHotbarEntries(accountId, getCachedEntries(hotbarInventoryId));
                renderHotbarInventory(astPlayer);
            });
        }).exceptionally(error -> {
            Logger.warn(LogId.W_5252, accountId, error.getMessage());
            return null;
        });
    }

    /**
     * inventoryCache / entryCache の最新状態を元に hotbarEntryCache を再構築します。
     * <p>
     * 主に「entries は最新だが hotbarEntryCache が古い」ケース（ログイン直後の非同期ロード後など）で、
     * findNextHotbarSlot / upsertHotbarEntry / renderHotbarInventory が古いキャッシュを見て
     * 既存スロットを上書きしたり描画が空になったりする問題を防ぐために呼び出します。
     *
     * @param accountId 対象アカウントID
     */
    private void rebuildHotbarEntryCache(@NotNull UUID accountId) {
        InventoryModel hotbar = getCachedInventories(accountId).stream()
            .filter(this::isDefaultProfile)
            .filter(inv -> inv.getInventoryType() == InventoryType.HOTBAR)
            .findFirst()
            .orElse(null);
        if (hotbar == null) {
            return;
        }
        cacheHotbarEntries(accountId, getCachedEntries(hotbar.getInventoryId()));
    }

    /**
     * プレイヤーの現在のホットバー（スロット 0〜8）をスナップショットとして保存します。
     *
     * @param astPlayer 保存対象プレイヤー
     */
    public void saveHotbarSnapshot(@NotNull AstPlayer astPlayer) {
        var accountId = astPlayer.getAccount().getUuid();
        var inventory = ensureInventory(
            accountId,
            InventoryType.HOTBAR,
            HotbarLayout.CAPACITY,
            accountId
        );
        cacheHotbarEntries(accountId, getEntries(inventory.getInventoryId()));
    }

    /**
     * ホットバースロットのクリックを処理します。
     * <p>
     * アイテム設定済みのスロットは HOTBAR から外して元のインベントリへ戻し、空スロットは選択状態にします。
     *
     * @param astPlayer 対象プレイヤー
     * @param hotbarSlotIndex HOTBAR の DB slot_index（1〜9）
     * @return 操作を処理できた場合 true
     */
    public boolean handleHotbarSlotClick(@NotNull AstPlayer astPlayer, int hotbarSlotIndex) {
        if (!HotbarLayout.isManagedSlot(hotbarSlotIndex)) {
            return false;
        }
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return false;
        }

        var accountId = astPlayer.getAccount().getUuid();
        InventoryEntryModel entry = getCachedHotbarEntry(accountId, hotbarSlotIndex);
        if (entry != null) {
            selectedHotbarSlots.remove(accountId);
            boolean returned = returnHotbarEntryToInventory(astPlayer, entry);
            renderHotbarInventory(astPlayer);
            return returned;
        }

        Integer currentSelected = selectedHotbarSlots.get(accountId);
        if (Integer.valueOf(hotbarSlotIndex).equals(currentSelected)) {
            selectedHotbarSlots.remove(accountId);
            renderHotbarInventory(astPlayer);
            return true;
        }

        selectedHotbarSlots.put(accountId, hotbarSlotIndex);
        if (HotbarLayout.isMainHotbarSlot(hotbarSlotIndex)) {
            astPlayer.getBukkit().getInventory().setHeldItemSlot(HotbarLayout.toBukkitSlot(hotbarSlotIndex));
        }
        renderHotbarInventory(astPlayer);
        return true;
    }

    /**
     * 指定エントリのアイテムをホットバーの指定スロットへ移動し、Bukkit インベントリへ反映します。
     *
     * @param astPlayer      対象プレイヤー
     * @param entry          移動元エントリ
     * @param hotbarSlotIndex ホットバーの DB slot_index（1〜9）
     */
    public void moveToHotbar(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel entry,
        int hotbarSlotIndex
    ) {
        if (!HotbarLayout.isManagedSlot(hotbarSlotIndex)) {
            return;
        }

        ItemStack itemStack = itemStackResolver.resolve(entry);
        if (itemStack == null) {
            return;
        }

        upsertHotbarEntry(astPlayer, entry, hotbarSlotIndex);
        renderHotbarInventory(astPlayer);
    }

    public boolean equipOrAssignClickedItem(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemStack clickedItem,
        int sourceBukkitSlot
    ) {
        if (clickedItem.getType() == Material.AIR) {
            return false;
        }

        InventoryEntryModel sourceEntry = findDisplayedEntryAtBukkitSlot(astPlayer, sourceBukkitSlot);
        if (sourceEntry == null) {
            return false;
        }

        ItemStack sourceItem = itemStackResolver.resolve(sourceEntry);
        if (sourceItem == null || sourceItem.getType() == Material.AIR) {
            return false;
        }

        ItemCategory category = ItemCategory.fromApiValue(sourceEntry.getItemCategory());
        ItemModel model = resolveItemModel(sourceEntry);
        if (model == null) {
            return false;
        }

        if (category == ItemCategory.EQUIPMENT) {
            if (model.getEquipment() == null) {
                return false;
            }

            ItemEquipmentSlot itemSlot = model.getEquipment().getSlot();
            if (itemSlot == ItemEquipmentSlot.HEAD
                || itemSlot == ItemEquipmentSlot.CHEST
                || itemSlot == ItemEquipmentSlot.LEGS
                || itemSlot == ItemEquipmentSlot.FEET) {
                return equipArmorItem(astPlayer, sourceEntry, sourceItem, sourceBukkitSlot, EquipmentType.fromItemEquipmentSlot(itemSlot));
            }
            if (itemSlot == ItemEquipmentSlot.ACCESSORY) {
                return equipAccessoryItem(astPlayer, sourceEntry, sourceItem, sourceBukkitSlot);
            }
            if (itemSlot == ItemEquipmentSlot.WEAPON || itemSlot == ItemEquipmentSlot.TOOL) {
                return assignHotbarItem(astPlayer, sourceEntry, sourceBukkitSlot);
            }
            return false;
        }

        if (category == ItemCategory.BUNDLE || category == ItemCategory.CONSUMABLE) {
            return assignHotbarItem(astPlayer, sourceEntry, sourceBukkitSlot);
        }

        return false;
    }

    private boolean equipArmorItem(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel sourceEntry,
        @NotNull ItemStack clickedItem,
        int sourceBukkitSlot,
        @NotNull EquipmentType equipmentType
    ) {
        if (equipmentType == EquipmentType.UNSUPPORTED) {
            return false;
        }

        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        ItemStack previous = getEquipmentItem(inventory, equipmentType);
        equipmentType.applyTo(inventory, clickedItem.clone());
        inventory.setItem(sourceBukkitSlot, emptyToAir(previous));

        removeDisplayedEntryAfterMove(astPlayer, sourceEntry);
        returnReplacedItemToOwnedInventory(astPlayer, previous);
        saveEquipSlotSnapshot(astPlayer);
        syncCurrentEquipmentState(astPlayer);
        requestManagedInventoryUiRefresh(astPlayer, false);
        return true;
    }

    private boolean equipAccessoryItem(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel sourceEntry,
        @NotNull ItemStack clickedItem,
        int sourceBukkitSlot
    ) {
        int accessorySlot = findAccessoryTargetSlot(astPlayer);
        if (!AccessorySlotLayout.isManagedSlot(accessorySlot)) {
            return false;
        }

        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        if (accessorySlot == AccessorySlotLayout.SLOT_OFF_HAND) {
            ItemStack previous = inventory.getItemInOffHand();
            inventory.setItemInOffHand(clickedItem.clone());
            inventory.setItem(sourceBukkitSlot, emptyToAir(previous));
            saveAccessorySlotSnapshot(astPlayer);
        } else {
            if (getAccessorySnapshotItem(astPlayer, accessorySlot) != null) {
                return false;
            }
            inventory.setItem(sourceBukkitSlot, new ItemStack(Material.AIR));
            updateAccessorySnapshotSlot(astPlayer, accessorySlot, clickedItem.clone());
        }

        removeDisplayedEntryAfterMove(astPlayer, sourceEntry);
        if (accessorySlot == AccessorySlotLayout.SLOT_OFF_HAND) {
            returnReplacedItemToOwnedInventory(astPlayer, inventory.getItem(sourceBukkitSlot));
        }
        syncCurrentEquipmentState(astPlayer);
        requestManagedInventoryUiRefresh(astPlayer, false);
        return true;
    }

    private boolean assignHotbarItem(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel sourceEntry,
        int sourceBukkitSlot
    ) {
        var accountId = astPlayer.getAccount().getUuid();
        Integer selectedHotbarSlotIndex = selectedHotbarSlots.remove(accountId);
        int targetDbSlot = selectedHotbarSlotIndex != null && HotbarLayout.isManagedSlot(selectedHotbarSlotIndex)
            ? selectedHotbarSlotIndex
            : findNextHotbarSlot(accountId);
        if (!HotbarLayout.isManagedSlot(targetDbSlot)) {
            return false;
        }

        upsertHotbarEntry(astPlayer, sourceEntry, targetDbSlot);
        removeDisplayedEntryAfterMove(astPlayer, sourceEntry);
        requestManagedInventoryUiRefresh(astPlayer, true);
        return true;
    }

    /**
     * 装備 GUI 内へ移動した表示中インベントリの entry を削除し、必要に応じて入れ替え元アイテムを戻します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot 移動元 Bukkit storage slot
     * @param replacedItem 装備 GUI 側で入れ替えられたアイテム。ない場合は null
     * @return 元 entry の削除と返却処理が成功した場合 true
     */
    public boolean moveDisplayedItemToEquipmentGui(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot,
        @Nullable ItemStack replacedItem
    ) {
        boolean hasReplacedItem = replacedItem != null && replacedItem.getType() != Material.AIR;
        if (hasReplacedItem
            && (ItemStackFactory.getAstralItemId(replacedItem) == null || ItemStackFactory.getCategory(replacedItem) == null)) {
            return false;
        }

        InventoryEntryModel sourceEntry = findDisplayedEntryAtBukkitSlot(astPlayer, sourceBukkitSlot);
        if (sourceEntry == null) {
            return false;
        }

        removeDisplayedEntryAfterMove(astPlayer, sourceEntry);
        if (hasReplacedItem && returnItemToOwnedInventory(astPlayer, replacedItem.clone()) == null) {
            return false;
        }
        requestManagedInventoryUiRefresh(astPlayer, false);
        return true;
    }

    /**
     * 表示中インベントリから他の管理領域へ移動した entry を削除し、詰め直します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceEntry 移動元 entry
     */
    private void removeDisplayedEntryAfterMove(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel sourceEntry
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        List<InventoryEntryModel> remaining = getCachedEntries(sourceEntry.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .filter(entry -> !entry.getInventoryEntryId().equals(sourceEntry.getInventoryEntryId()))
            .sorted(java.util.Comparator.<InventoryEntryModel, Integer>comparing(
                entry -> entry.getSlotIndex() == null ? Integer.MAX_VALUE : entry.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .toList();
        List<InventoryEntryDraft> drafts = new ArrayList<>();
        int next = NormalInventoryLayout.DB_SLOT_START;
        for (InventoryEntryModel entry : remaining) {
            drafts.add(copyEntryDraft(entry, next));
            next++;
        }
        replaceEntriesOptimistically(sourceEntry.getInventoryId(), drafts, accountId);
        saveDisplayedStorageSnapshotIfNormal(astPlayer);
    }

    private void returnReplacedItemToOwnedInventory(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack replacedItem
    ) {
        if (replacedItem == null || replacedItem.getType() == Material.AIR) {
            return;
        }
        returnItemToOwnedInventory(astPlayer, replacedItem.clone());
    }

    /**
     * 管理インベントリの表示を即時と次 tick の両方で再描画します。
     *
     * @param astPlayer 対象プレイヤー
     * @param includeHotbar ホットバーも再描画する場合 true
     */
    private void requestManagedInventoryUiRefresh(@NotNull AstPlayer astPlayer, boolean includeHotbar) {
        applyDisplayedInventoryToGui(astPlayer);
        if (includeHotbar) {
            renderHotbarInventory(astPlayer);
        }
        astPlayer.getBukkit().updateInventory();

        AstralRecord plugin = AstralRecord.getInstance();
        if (plugin == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!astPlayer.getBukkit().isOnline()) {
                return;
            }
            applyDisplayedInventoryToGui(astPlayer);
            if (includeHotbar) {
                renderHotbarInventory(astPlayer);
            }
            astPlayer.getBukkit().updateInventory();
        });
    }

    private int findNextHotbarSlot(@NotNull UUID accountId) {
        rebuildHotbarEntryCache(accountId);
        Map<Integer, InventoryEntryModel> entries = hotbarEntryCache.computeIfAbsent(accountId, key -> new ConcurrentHashMap<>());
        for (int slot = HotbarLayout.DB_SLOT_START; slot <= HotbarLayout.DB_SLOT_END; slot++) {
            if (!entries.containsKey(slot)) {
                return slot;
            }
        }
        // 未選択時、ホットバー本体（1〜9）が満杯ならオフハンド（slot 10）へフォールバック
        if (!entries.containsKey(HotbarLayout.DB_SLOT_OFFHAND)) {
            return HotbarLayout.DB_SLOT_OFFHAND;
        }
        return -1;
    }

    /**
     * HOTBAR entry を指定スロットへ保存し、サービス内キャッシュも同時に更新します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceEntry 登録する entry
     * @param targetDbSlot HOTBAR の DB slot_index（1〜9）
     */
    private void upsertHotbarEntry(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel sourceEntry,
        int targetDbSlot
    ) {
        var accountId = astPlayer.getAccount().getUuid();
        var hotbarInventory = ensureInventory(accountId, InventoryType.HOTBAR, HotbarLayout.CAPACITY, accountId);
        rebuildHotbarEntryCache(accountId);
        List<InventoryEntryDraft> drafts = getCachedEntries(hotbarInventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .filter(entry -> entry.getSlotIndex() == null || entry.getSlotIndex() != targetDbSlot)
            .map(entry -> copyEntryDraft(entry, entry.getSlotIndex()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        drafts.add(copyEntryDraft(sourceEntry, targetDbSlot));
        List<InventoryEntryModel> saved = replaceEntriesOptimistically(hotbarInventory.getInventoryId(), drafts, accountId);
        cacheHotbarEntries(accountId, saved);
    }

    /**
     * HOTBAR の entry を元の種別のインベントリへ戻します。
     *
     * @param astPlayer 対象プレイヤー
     * @param hotbarEntry HOTBAR から戻す entry
     * @return 戻し先スロットを確保できた場合 true
     */
    private boolean returnHotbarEntryToInventory(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel hotbarEntry
    ) {
        var accountId = astPlayer.getAccount().getUuid();
        InventoryType targetType = resolveTargetInventoryType(hotbarEntry);
        InventoryModel targetInventory = ensureInventory(accountId, targetType, resolveSlotCapacity(targetType), accountId);
        List<InventoryEntryModel> targetEntries = getEntries(targetInventory.getInventoryId());
        Set<Integer> usedSlots = NormalInventoryLayout.collectUsedSlots(targetEntries);
        Integer targetSlot = NormalInventoryLayout.findNextFreeSlot(usedSlots);
        if (targetSlot == null) {
            return false;
        }

        List<InventoryEntryDraft> targetDrafts = targetEntries.stream()
            .filter(entry -> !entry.isDeleted())
            .map(entry -> copyEntryDraft(entry, entry.getSlotIndex()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        targetDrafts.add(copyEntryDraft(hotbarEntry, targetSlot));
        replaceEntriesOptimistically(targetInventory.getInventoryId(), targetDrafts, accountId);

        InventoryModel hotbarInventory = ensureInventory(accountId, InventoryType.HOTBAR, HotbarLayout.CAPACITY, accountId);
        List<InventoryEntryDraft> hotbarDrafts = getCachedEntries(hotbarInventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .filter(entry -> !entry.getInventoryEntryId().equals(hotbarEntry.getInventoryEntryId()))
            .map(entry -> copyEntryDraft(entry, entry.getSlotIndex()))
            .toList();
        List<InventoryEntryModel> hotbarEntries = replaceEntriesOptimistically(hotbarInventory.getInventoryId(), hotbarDrafts, accountId);
        cacheHotbarEntries(accountId, hotbarEntries);
        if (getDisplayedInventoryType(accountId) == targetType) {
            applyDisplayedInventoryToGui(astPlayer);
        }
        return true;
    }

    /**
     * HOTBAR キャッシュから指定スロットの entry を取得します。
     *
     * @param accountId アカウントID
     * @param hotbarSlotIndex HOTBAR の DB slot_index（1〜9）
     * @return キャッシュ済み entry。未設定の場合は null
     */
    private @Nullable InventoryEntryModel getCachedHotbarEntry(@NotNull UUID accountId, int hotbarSlotIndex) {
        rebuildHotbarEntryCache(accountId);
        Map<Integer, InventoryEntryModel> entries = hotbarEntryCache.computeIfAbsent(accountId, key -> new ConcurrentHashMap<>());
        return entries.get(hotbarSlotIndex);
    }

    /**
     * HOTBAR entries をスロット番号で引ける形にキャッシュします。
     *
     * @param accountId アカウントID
     * @param entries API から取得した HOTBAR entries
     */
    private void cacheHotbarEntries(@NotNull UUID accountId, @NotNull List<InventoryEntryModel> entries) {
        Map<Integer, InventoryEntryModel> slottedEntries = new ConcurrentHashMap<>();
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex != null && HotbarLayout.isManagedSlot(slotIndex) && !entry.isDeleted()) {
                slottedEntries.put(slotIndex, entry);
            }
        }
        hotbarEntryCache.put(accountId, slottedEntries);
    }

    /**
     * HOTBAR キャッシュをプレイヤーのホットバーへ描画します。
     *
     * @param astPlayer 対象プレイヤー
     */
    /**
     * GUI（プレイヤーインベントリ）が開いている間、ホットバーを
     * インベントリ選択ショートカット＋閉じるボタン表示へ切り替えます。
     *
     * @param astPlayer 対象プレイヤー
     * @param on true で切替 ON、false で OFF
     */
    public void setHotbarShortcutMode(@NotNull AstPlayer astPlayer, boolean on) {
        UUID accountId = astPlayer.getAccount().getUuid();
        boolean changed = on
            ? hotbarShortcutMode.add(accountId)
            : hotbarShortcutMode.remove(accountId);
        if (!changed) {
            return;
        }
        renderHotbarInventory(astPlayer);
    }

    /**
     * プレイヤーが現在ホットバーショートカットモードかどうかを返します。
     *
     * @param astPlayer 対象プレイヤー
     * @return ショートカットモード中なら true
     */
    public boolean isHotbarShortcutMode(@NotNull AstPlayer astPlayer) {
        return hotbarShortcutMode.contains(astPlayer.getAccount().getUuid());
    }

    /**
     * ホットバーショートカットモード中のホットバースロットクリックを処理します。
     * <p>
     * 各スロットの割当: 0=NORMAL, 1=EQUIPMENT, 2=RUNE, 8=CLOSE。
     *
     * @param astPlayer 対象プレイヤー
     * @param bukkitSlot Bukkit storage 側のスロット番号（0〜8）
     * @return クリックを処理した場合 true
     */
    public boolean handleHotbarShortcutClick(@NotNull AstPlayer astPlayer, int bukkitSlot) {
        if (!isHotbarShortcutMode(astPlayer)) {
            return false;
        }
        InventoryType target = switch (bukkitSlot) {
            case 0 -> InventoryType.NORMAL;
            case 1 -> InventoryType.EQUIPMENT;
            case 2 -> InventoryType.RUNE;
            default -> null;
        };
        if (target != null) {
            applyInventoryToGui(astPlayer, target);
            return true;
        }
        if (bukkitSlot == 8) {
            astPlayer.getBukkit().closeInventory();
            return true;
        }
        return false;
    }

    private void renderHotbarInventory(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        if (isHotbarShortcutMode(astPlayer)) {
            hotbarRenderer.renderShortcutIcons(astPlayer, getDisplayedInventoryType(accountId));
            return;
        }
        rebuildHotbarEntryCache(accountId);
        Map<Integer, InventoryEntryModel> entries = hotbarEntryCache.computeIfAbsent(accountId, key -> new ConcurrentHashMap<>());
        Integer selectedSlot = selectedHotbarSlots.get(accountId);
        hotbarRenderer.renderHotbarInventory(astPlayer, entries, selectedSlot);
    }

    private int findAccessoryTargetSlot(@NotNull AstPlayer astPlayer) {
        ItemStack offhand = astPlayer.getBukkit().getInventory().getItemInOffHand();
        if (offhand.getType() == Material.AIR) {
            return AccessorySlotLayout.SLOT_OFF_HAND;
        }
        for (int slot = AccessorySlotLayout.SLOT_NECKLACE; slot <= AccessorySlotLayout.SLOT_RING; slot++) {
            if (getAccessorySnapshotItem(astPlayer, slot) == null) {
                return slot;
            }
        }
        return -1;
    }

    private @Nullable ItemStack getEquipmentItem(
        @NotNull PlayerInventory inventory,
        @NotNull EquipmentType equipmentType
    ) {
        return switch (equipmentType) {
            case MAIN_HAND -> inventory.getItemInMainHand();
            case HEAD -> inventory.getHelmet();
            case CHEST -> inventory.getChestplate();
            case LEGS -> inventory.getLeggings();
            case FEET -> inventory.getBoots();
            case OFF_HAND -> inventory.getItemInOffHand();
            case UNSUPPORTED -> null;
        };
    }

    /**
     * 現在表示中のインベントリから、指定 Bukkit スロットに対応する entry を取得します。
     *
     * @param astPlayer 対象プレイヤー
     * @param sourceBukkitSlot Bukkit storage slot
     * @return 表示中の entry。未設定の場合は null
     */
    private @Nullable InventoryEntryModel findDisplayedEntryAtBukkitSlot(
        @NotNull AstPlayer astPlayer,
        int sourceBukkitSlot
    ) {
        if (!NormalInventoryLayout.isManagedGuiSlot(sourceBukkitSlot)) {
            return null;
        }

        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryType displayedType = getDisplayedInventoryType(accountId);
        int dbSlot = NormalInventoryLayout.toDbSlotIndex(sourceBukkitSlot);

        return getInventories(accountId).stream()
            .filter(this::isDefaultProfile)
            .filter(inventory -> inventory.getInventoryType() == displayedType)
            .findFirst()
            .flatMap(inventory -> getEntries(inventory.getInventoryId()).stream()
                .filter(entry -> entry.getSlotIndex() != null && entry.getSlotIndex() == dbSlot && !entry.isDeleted())
                .findFirst())
            .orElse(null);
    }

    /**
     * 現在選択されている表示インベントリを Bukkit storage へ再描画します。
     *
     * @param astPlayer 対象プレイヤー
     */
    private void applyDisplayedInventoryToGui(@NotNull AstPlayer astPlayer) {
        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryType displayedType = getDisplayedInventoryType(accountId);
        if (displayedType == InventoryType.CURRENCY) {
            clearManagedStorageSlots(astPlayer.getBukkit());
            return;
        }
        InventoryModel inventory = getInventories(accountId).stream()
            .filter(this::isDefaultProfile)
            .filter(inv -> inv.getInventoryType() == displayedType)
            .findFirst()
            .orElse(null);
        applyInventoryToGui(astPlayer.getBukkit(), inventory);
    }

    private void saveDisplayedStorageSnapshotIfNormal(@NotNull AstPlayer astPlayer) {
        // NORMAL inventory は API entry を表示するだけのため、Bukkit ItemStack の JSON 保存は行いません。
    }

    private void updateAccessorySnapshotSlot(
        @NotNull AstPlayer astPlayer,
        int accessorySlot,
        @Nullable ItemStack itemStack
    ) {
        if (!AccessorySlotLayout.isManagedSlot(accessorySlot)) {
            return;
        }
        int loadoutSlotIndex = toAccessoryLoadoutSlotIndex(accessorySlot);
        if (loadoutSlotIndex < 0) {
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        EquipmentLoadoutModel loadout = ensureActiveEquipmentLoadout(accountId);
        if (loadout == null) {
            return;
        }
        syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, loadoutSlotIndex, itemStack, accountId);
    }

    public void syncCurrentEquipmentState(@NotNull AstPlayer astPlayer) {
        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        syncActiveEquipmentLoadout(
            astPlayer.getAccount().getUuid(),
            inventory.getHelmet(),
            inventory.getChestplate(),
            inventory.getLeggings(),
            inventory.getBoots(),
            inventory.getItemInOffHand(),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_NECKLACE),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_RING),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_EARRING),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_BRACELET),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_BELT),
            getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_CHARM)
        );
    }

    private @NotNull ItemStack emptyToAir(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return new ItemStack(Material.AIR);
        }
        return itemStack;
    }

    // ---------------------------------------------------------------
    // ACCESSORY_SLOT
    // ---------------------------------------------------------------

    /**
     * ACCESSORY_SLOT インベントリをプレイヤーのアクセサリスロットへ反映します。
     * <p>
     * slot_index 1 はオフハンドへ反映します。2 以降は DB 管理のみです。
     */
    public void applyAccessorySlotInventoryToGui(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }

        var bukkitPlayer = astPlayer.getBukkit();
        ItemStack[] snapshot = new ItemStack[AccessorySlotLayout.SLOT_MAX + 1];
        snapshot[AccessorySlotLayout.SLOT_OFF_HAND] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_OFF_HAND));
        snapshot[AccessorySlotLayout.SLOT_NECKLACE] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_NECKLACE));
        snapshot[AccessorySlotLayout.SLOT_RING] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_RING));
        snapshot[AccessorySlotLayout.SLOT_EARRING] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_EARRING));
        snapshot[AccessorySlotLayout.SLOT_BRACELET] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_BRACELET));
        snapshot[AccessorySlotLayout.SLOT_BELT] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_BELT));
        snapshot[AccessorySlotLayout.SLOT_CHARM] = itemOrAir(getAccessorySnapshotItem(astPlayer, AccessorySlotLayout.SLOT_CHARM));
        AccessorySlotLayout.applySnapshot(bukkitPlayer, snapshot);

        bukkitPlayer.updateInventory();
    }

    /**
     * プレイヤーの現在のアクセサリスロット（オフハンド）をスナップショットとして保存します。
     *
     * @param astPlayer 保存対象プレイヤー
     */
    public void saveAccessorySlotSnapshot(@NotNull AstPlayer astPlayer) {
        syncCurrentEquipmentState(astPlayer);
    }

    /**
     * 装備 GUI の内容を Bukkit 装備欄と DB スナップショットへ反映します。
     *
     * @param astPlayer 保存対象プレイヤー
     * @param head 頭装備
     * @param chest 胴装備
     * @param legs 脚装備
     * @param feet 足装備
     * @param offHand オフハンド装備
     * @param accessory2 拡張アクセサリ1
     * @param accessory3 拡張アクセサリ2
     */
    public void saveEquipmentGui(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @Nullable ItemStack offHand,
        @Nullable ItemStack accessory2,
        @Nullable ItemStack accessory3,
        @Nullable ItemStack accessory4,
        @Nullable ItemStack accessory5,
        @Nullable ItemStack accessory6,
        @Nullable ItemStack accessory7
    ) {
        var bukkitInventory = astPlayer.getBukkit().getInventory();
        bukkitInventory.setHelmet(itemOrAir(head));
        bukkitInventory.setChestplate(itemOrAir(chest));
        bukkitInventory.setLeggings(itemOrAir(legs));
        bukkitInventory.setBoots(itemOrAir(feet));
        bukkitInventory.setItemInOffHand(itemOrAir(offHand));

        var accountId = astPlayer.getAccount().getUuid();

        ItemStack[] equipSnapshot = new ItemStack[EquipSlotLayout.SLOT_MAX + 1];
        equipSnapshot[EquipSlotLayout.SLOT_HEAD] = itemOrAir(head);
        equipSnapshot[EquipSlotLayout.SLOT_CHEST] = itemOrAir(chest);
        equipSnapshot[EquipSlotLayout.SLOT_LEGS] = itemOrAir(legs);
        equipSnapshot[EquipSlotLayout.SLOT_FEET] = itemOrAir(feet);
        var equipInventory = ensureInventory(accountId, InventoryType.EQUIP_SLOT, EquipSlotLayout.SLOT_MAX, accountId);
        updateMetadataAsync(equipInventory.getInventoryId(), snapshotCodec.encode(equipSnapshot), accountId);

        syncActiveEquipmentLoadout(
            accountId,
            head,
            chest,
            legs,
            feet,
            offHand,
            accessory2,
            accessory3,
            accessory4,
            accessory5,
            accessory6,
            accessory7
        );

        astPlayer.getBukkit().updateInventory();
    }

    private boolean applyActiveEquipmentLoadoutToGui(@NotNull AstPlayer astPlayer) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return false;
        }

        EquipmentLoadoutModel loadout = getActiveEquipmentLoadout(astPlayer.getAccount().getUuid());
        if (loadout == null || loadout.getSlots().isEmpty()) {
            return false;
        }

        PlayerInventory inventory = astPlayer.getBukkit().getInventory();
        inventory.setHelmet(new ItemStack(Material.AIR));
        inventory.setChestplate(new ItemStack(Material.AIR));
        inventory.setLeggings(new ItemStack(Material.AIR));
        inventory.setBoots(new ItemStack(Material.AIR));

        ItemStack desiredOffHand = new ItemStack(Material.AIR);
        for (EquipmentLoadoutSlotModel slot : loadout.getSlots()) {
            ItemStack itemStack = itemStackResolver.resolve(toInventoryEntry(slot));
            if (itemStack == null) {
                continue;
            }
            if (SLOT_TYPE_ACCESSORY.equalsIgnoreCase(slot.getSlotType()) && slot.getSlotIndex() == 0) {
                desiredOffHand = itemStack;
                continue;
            }
            applyLoadoutSlot(inventory, slot.getSlotType(), slot.getSlotIndex(), itemStack);
        }
        if (!isSameItemStack(inventory.getItemInOffHand(), desiredOffHand)) {
            inventory.setItemInOffHand(desiredOffHand);
        }

        astPlayer.getBukkit().updateInventory();
        return true;
    }

    private void syncActiveEquipmentLoadout(
        @NotNull UUID accountId,
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @Nullable ItemStack offHand,
        @Nullable ItemStack accessory2,
        @Nullable ItemStack accessory3,
        @Nullable ItemStack accessory4,
        @Nullable ItemStack accessory5,
        @Nullable ItemStack accessory6,
        @Nullable ItemStack accessory7
    ) {
        ItemStack headSnapshot = cloneItemStack(head);
        ItemStack chestSnapshot = cloneItemStack(chest);
        ItemStack legsSnapshot = cloneItemStack(legs);
        ItemStack feetSnapshot = cloneItemStack(feet);
        ItemStack offHandSnapshot = cloneItemStack(offHand);
        ItemStack accessory2Snapshot = cloneItemStack(accessory2);
        ItemStack accessory3Snapshot = cloneItemStack(accessory3);
        ItemStack accessory4Snapshot = cloneItemStack(accessory4);
        ItemStack accessory5Snapshot = cloneItemStack(accessory5);
        ItemStack accessory6Snapshot = cloneItemStack(accessory6);
        ItemStack accessory7Snapshot = cloneItemStack(accessory7);

        // 装備の脱着直後に applyActiveEquipmentLoadoutToGui が古いキャッシュを読んで
        // 取り外したはずの装備を再適用しないよう、DB 書き込み前にキャッシュを同期更新する。
        updateActiveLoadoutCacheOptimistically(
            accountId,
            headSnapshot,
            chestSnapshot,
            legsSnapshot,
            feetSnapshot,
            offHandSnapshot,
            accessory2Snapshot,
            accessory3Snapshot,
            accessory4Snapshot,
            accessory5Snapshot,
            accessory6Snapshot,
            accessory7Snapshot
        );

        LoadoutPayload payload = new LoadoutPayload(
            headSnapshot,
            chestSnapshot,
            legsSnapshot,
            feetSnapshot,
            offHandSnapshot,
            accessory2Snapshot,
            accessory3Snapshot,
            accessory4Snapshot,
            accessory5Snapshot,
            accessory6Snapshot,
            accessory7Snapshot
        );

        // submit を更新すると最新 payload で上書きされ、既存スケジュールはそのまま 1 秒後に発火する。
        // 保留中・実行中いずれの状態でも hasPendingLoadoutWrites が true を返すため、
        // 古い DB 状態で equipmentLoadoutCache が refresh される事故は起きない。
        loadoutCoalescer.submit(
            accountId,
            payload,
            latest -> executeLoadoutSync(accountId, latest)
        );
    }

    /**
     * coalescer の flush から呼び出される、装備ロードアウト DB 書き込み本体。
     */
    private void executeLoadoutSync(
        @NotNull UUID accountId,
        @NotNull LoadoutPayload payload
    ) {
        // 実 DB 書き込みが完了するまで refresh を抑止するために in-flight 件数を増減する。
        AtomicInteger inflight = loadoutWriteCounts.computeIfAbsent(accountId, key -> new AtomicInteger());
        inflight.incrementAndGet();
        // 同一アカウントの装備書き込みは直列化する。
        CompletableFuture<Void> previousChain = loadoutWriteChains
            .getOrDefault(accountId, CompletableFuture.completedFuture(null))
            .exceptionally(error -> null);

        CompletableFuture<Void> future = previousChain.thenRunAsync(() -> {
            EquipmentLoadoutModel loadout = ensureActiveEquipmentLoadout(accountId);
            if (loadout == null) {
                return;
            }
            syncLoadoutSlot(loadout, SLOT_TYPE_HEAD, 0, payload.head(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_CHEST, 0, payload.chest(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_LEGS, 0, payload.legs(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_FEET, 0, payload.feet(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, 0, payload.offHand(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, 1, payload.accessory2(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, 2, payload.accessory3(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, 3, payload.accessory4(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, 4, payload.accessory5(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, 5, payload.accessory6(), accountId);
            syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, 6, payload.accessory7(), accountId);
        });

        loadoutWriteChains.put(accountId, future);
        future.whenComplete((ignored, error) -> {
            inflight.decrementAndGet();
            loadoutWriteChains.compute(accountId, (key, current) -> current == future ? null : current);
        });
        trackWriteTask(future);
    }

    /** 装備ロードアウト書き込みを coalescer に詰めるためのペイロード。 */
    private record LoadoutPayload(
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @Nullable ItemStack offHand,
        @Nullable ItemStack accessory2,
        @Nullable ItemStack accessory3,
        @Nullable ItemStack accessory4,
        @Nullable ItemStack accessory5,
        @Nullable ItemStack accessory6,
        @Nullable ItemStack accessory7
    ) {
    }

    private @Nullable ItemStack cloneItemStack(@Nullable ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }

    /**
     * アクティブな装備ロードアウトのキャッシュを、与えられた装備状態へ同期的に書き換えます。
     * <p>
     * 装備の脱着直後にキャッシュが古いまま残り、{@link #applyActiveEquipmentLoadoutToGui} が
     * 取り外したはずの装備を再適用してしまう状況を防ぐために、DB 反映を待たずに呼び出します。
     *
     * @param accountId 対象アカウントID
     * @param head      頭装備（null/AIR で未装備として扱う）
     * @param chest     胴装備
     * @param legs      脚装備
     * @param feet      足装備
     * @param offHand   オフハンド装備
     * @param accessory2 アクセサリスロット1
     * @param accessory3 アクセサリスロット2
     * @param accessory4 アクセサリスロット3
     * @param accessory5 アクセサリスロット4
     * @param accessory6 アクセサリスロット5
     * @param accessory7 アクセサリスロット6
     */
    private void updateActiveLoadoutCacheOptimistically(
        @NotNull UUID accountId,
        @Nullable ItemStack head,
        @Nullable ItemStack chest,
        @Nullable ItemStack legs,
        @Nullable ItemStack feet,
        @Nullable ItemStack offHand,
        @Nullable ItemStack accessory2,
        @Nullable ItemStack accessory3,
        @Nullable ItemStack accessory4,
        @Nullable ItemStack accessory5,
        @Nullable ItemStack accessory6,
        @Nullable ItemStack accessory7
    ) {
        List<EquipmentLoadoutModel> cached = equipmentLoadoutCache.get(accountId);
        if (cached == null || cached.isEmpty()) {
            return;
        }
        EquipmentLoadoutModel active = cached.stream()
            .filter(loadout -> loadout.isActive() && !loadout.isDeleted())
            .findFirst()
            .orElse(null);
        if (active == null) {
            return;
        }

        Map<String, Map<Integer, EquipmentLoadoutSlotModel>> bySlot = new HashMap<>();
        for (EquipmentLoadoutSlotModel slot : active.getSlots()) {
            bySlot.computeIfAbsent(slot.getSlotType(), key -> new HashMap<>())
                .put(slot.getSlotIndex(), slot);
        }

        LocalDateTime now = LocalDateTime.now();
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_HEAD, 0, head, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_CHEST, 0, chest, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_LEGS, 0, legs, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_FEET, 0, feet, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_ACCESSORY, 0, offHand, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_ACCESSORY, 1, accessory2, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_ACCESSORY, 2, accessory3, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_ACCESSORY, 3, accessory4, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_ACCESSORY, 4, accessory5, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_ACCESSORY, 5, accessory6, active, accountId, now);
        applyOptimisticLoadoutSlot(bySlot, SLOT_TYPE_ACCESSORY, 6, accessory7, active, accountId, now);

        List<EquipmentLoadoutSlotModel> nextSlots = bySlot.values().stream()
            .flatMap(map -> map.values().stream())
            .toList();

        EquipmentLoadoutModel updated = new EquipmentLoadoutModel(
            active.getEquipmentLoadoutId(),
            active.getAccountId(),
            active.getLoadoutProfile(),
            active.getLoadoutName(),
            active.getSortOrder(),
            active.isActive(),
            active.getMetadataJson(),
            nextSlots,
            active.getCreatedAt(),
            now,
            active.getCreatedBy(),
            accountId,
            active.isDeleted()
        );

        List<EquipmentLoadoutModel> next = new ArrayList<>(cached);
        next.removeIf(loadout -> loadout.getEquipmentLoadoutId().equals(active.getEquipmentLoadoutId()));
        next.add(updated);
        equipmentLoadoutCache.put(accountId, List.copyOf(next));
    }

    private void applyOptimisticLoadoutSlot(
        @NotNull Map<String, Map<Integer, EquipmentLoadoutSlotModel>> bySlot,
        @NotNull String slotType,
        int slotIndex,
        @Nullable ItemStack itemStack,
        @NotNull EquipmentLoadoutModel active,
        @NotNull UUID accountId,
        @NotNull LocalDateTime now
    ) {
        UUID equipmentInstanceId = readEquipmentInstanceId(itemStack);
        Map<Integer, EquipmentLoadoutSlotModel> slots = bySlot.computeIfAbsent(slotType, key -> new HashMap<>());
        if (equipmentInstanceId == null) {
            slots.remove(slotIndex);
            return;
        }
        EquipmentLoadoutSlotModel existing = slots.get(slotIndex);
        slots.put(slotIndex, new EquipmentLoadoutSlotModel(
            existing != null ? existing.getEquipmentLoadoutSlotId() : UUID.randomUUID(),
            active.getEquipmentLoadoutId(),
            slotType,
            slotIndex,
            equipmentInstanceId,
            existing != null ? existing.getCreatedAt() : now,
            now,
            existing != null ? existing.getCreatedBy() : accountId,
            accountId,
            false
        ));
    }

    private void syncLoadoutSlot(
        @NotNull EquipmentLoadoutModel loadout,
        @NotNull String slotType,
        int slotIndex,
        @Nullable ItemStack itemStack,
        @NotNull UUID updatedBy
    ) {
        UUID equipmentInstanceId = readEquipmentInstanceId(itemStack);
        try {
            if (equipmentInstanceId == null) {
                equipmentLoadoutRepository.deleteSlot(loadout.getEquipmentLoadoutId(), slotType, slotIndex, updatedBy);
                return;
            }

            equipmentLoadoutRepository.upsertSlot(
                loadout.getEquipmentLoadoutId(),
                slotType,
                slotIndex,
                equipmentInstanceId,
                updatedBy
            );
        } catch (RuntimeException e) {
            Logger.warn(LogId.W_5254, loadout.getEquipmentLoadoutId(), slotType, slotIndex, e.getMessage());
        }
    }

    private @Nullable UUID readEquipmentInstanceId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        String instanceId = ItemStackFactory.getEquipmentInstanceId(itemStack);
        if (instanceId == null || instanceId.isBlank()) {
            return null;
        }
        return parseUuidOrNull(instanceId);
    }

    private InventoryEntryModel toInventoryEntry(@NotNull EquipmentLoadoutSlotModel slot) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            slot.getEquipmentLoadoutSlotId(),
            slot.getEquipmentLoadoutId(),
            slot.getSlotIndex(),
            ItemCategory.EQUIPMENT.name(),
            null,
            InventoryInstanceType.EQUIPMENT.getCode(),
            slot.getEquipmentInstanceId(),
            1L,
            null,
            now,
            now,
            slot.getCreatedBy(),
            slot.getUpdatedBy(),
            false
        );
    }

    private void applyLoadoutSlot(
        @NotNull PlayerInventory inventory,
        @NotNull String slotType,
        int slotIndex,
        @NotNull ItemStack itemStack
    ) {
        switch (slotType.toUpperCase(java.util.Locale.ROOT)) {
            case "WEAPON" -> {
            }
            case SLOT_TYPE_HEAD -> inventory.setHelmet(itemStack);
            case SLOT_TYPE_CHEST -> inventory.setChestplate(itemStack);
            case SLOT_TYPE_LEGS -> inventory.setLeggings(itemStack);
            case SLOT_TYPE_FEET -> inventory.setBoots(itemStack);
            case SLOT_TYPE_ACCESSORY -> {
                if (slotIndex == 0) {
                    inventory.setItemInOffHand(itemStack);
                }
            }
            default -> {
            }
        }
    }

    /**
     * 指定アイテムが装備 GUI の対象スロットへ配置できるか判定します。
     *
     * @param itemStack 判定対象アイテム
     * @param equipmentType Bukkit 装備欄に対応するスロット種別
     * @param extendedAccessory 拡張アクセサリスロットか
     * @return 配置可能なら true
     */
    public boolean canPlaceInEquipmentGuiSlot(
        @Nullable ItemStack itemStack,
        @Nullable EquipmentType equipmentType,
        boolean extendedAccessory
    ) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return true;
        }
        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        String category = ItemStackFactory.getCategory(itemStack);
        if (itemId == null || category == null || ItemCategory.fromApiValue(category) != ItemCategory.EQUIPMENT) {
            return false;
        }
        ItemModel model = resolveItemModel(itemId);
        if (model == null || model.getEquipment() == null) {
            return false;
        }

        ItemEquipmentSlot itemSlot = model.getEquipment().getSlot();
        if (extendedAccessory) {
            return itemSlot == ItemEquipmentSlot.ACCESSORY;
        }
        return EquipmentType.fromItemEquipmentSlot(itemSlot) == equipmentType;
    }

    public @NotNull EquipmentType getEquipmentTypeForItem(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return EquipmentType.UNSUPPORTED;
        }
        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        String category = ItemStackFactory.getCategory(itemStack);
        if (itemId == null || category == null || ItemCategory.fromApiValue(category) != ItemCategory.EQUIPMENT) {
            return EquipmentType.UNSUPPORTED;
        }
        ItemModel model = resolveItemModel(itemId);
        if (model == null || model.getEquipment() == null) {
            return EquipmentType.UNSUPPORTED;
        }
        return EquipmentType.fromItemEquipmentSlot(model.getEquipment().getSlot());
    }

    /**
     * 保存済みアクセサリスナップショットから指定スロットのアイテムを取得します。
     *
     * @param astPlayer 対象プレイヤー
     * @param slotIndex ACCESSORY_SLOT の slot_index
     * @return 保存済みアイテム。未保存または空の場合は null
     */
    public @Nullable ItemStack getAccessorySnapshotItem(@NotNull AstPlayer astPlayer, int slotIndex) {
        if (!AccessorySlotLayout.isManagedSlot(slotIndex)) {
            return null;
        }
        int loadoutSlotIndex = toAccessoryLoadoutSlotIndex(slotIndex);
        if (loadoutSlotIndex < 0) {
            return null;
        }
        EquipmentLoadoutModel loadout = getActiveEquipmentLoadout(astPlayer.getAccount().getUuid());
        if (loadout == null || loadout.getSlots().isEmpty()) {
            return null;
        }
        return loadout.getSlots().stream()
            .filter(slot -> SLOT_TYPE_ACCESSORY.equals(slot.getSlotType()))
            .filter(slot -> slot.getSlotIndex() == loadoutSlotIndex)
            .filter(slot -> slot.getEquipmentInstanceId() != null)
            .map(this::toInventoryEntry)
            .map(itemStackResolver::resolve)
            .filter(item -> item != null)
            .filter(item -> item.getType() != Material.AIR)
            .findFirst()
            .orElse(null);
    }

    /**
     * 指定エントリのアクセサリアイテムをアクセサリスロットへ移動します。
     * <p>
     * エントリを ACCESSORY_SLOT インベントリへ登録し、Bukkit インベントリへも反映します。
     *
     * @param astPlayer     対象プレイヤー
     * @param entry         移動元エントリ（EQUIPMENT インベントリのエントリ）
     * @param slotIndex     ACCESSORY_SLOT のスロット番号（1=オフハンド, 2〜3=拡張）
     */
    public void moveToAccessorySlot(
        @NotNull AstPlayer astPlayer,
        @NotNull InventoryEntryModel entry,
        int slotIndex
    ) {
        if (!AccessorySlotLayout.isManagedSlot(slotIndex)) {
            return;
        }

        var accountId = astPlayer.getAccount().getUuid();
        int loadoutSlotIndex = toAccessoryLoadoutSlotIndex(slotIndex);
        EquipmentLoadoutModel loadout = ensureActiveEquipmentLoadout(accountId);
        if (loadout != null && loadoutSlotIndex >= 0) {
            ItemStack itemStack = itemStackResolver.resolve(entry);
            syncLoadoutSlot(loadout, SLOT_TYPE_ACCESSORY, loadoutSlotIndex, itemStack, accountId);
        }

        EquipmentType.fromAccessorySlotIndex(slotIndex)
            .applyTo(astPlayer.getBukkit().getInventory(), itemStackResolver.resolve(entry));
        astPlayer.getBukkit().updateInventory();
    }

    private int toAccessoryLoadoutSlotIndex(int accessorySlot) {
        if (accessorySlot == AccessorySlotLayout.SLOT_OFF_HAND) {
            return 0;
        }
        if (accessorySlot == AccessorySlotLayout.SLOT_NECKLACE) {
            return 1;
        }
        if (accessorySlot == AccessorySlotLayout.SLOT_RING) {
            return 2;
        }
        if (accessorySlot == AccessorySlotLayout.SLOT_EARRING) {
            return 3;
        }
        if (accessorySlot == AccessorySlotLayout.SLOT_BRACELET) {
            return 4;
        }
        if (accessorySlot == AccessorySlotLayout.SLOT_BELT) {
            return 5;
        }
        if (accessorySlot == AccessorySlotLayout.SLOT_CHARM) {
            return 6;
        }
        return -1;
    }

    // ---------------------------------------------------------------

    /**
     * 通常インベントリの管理対象スロットだけをスナップショットとして保存します。
     * <p>
     * ホットバー、装備欄、オフハンドは通常インベントリの表示対象外として保存しません。
     *
     * @param astPlayer 保存対象プレイヤー
     */
    public void saveNormalInventorySnapshot(@NotNull AstPlayer astPlayer) {
        // NORMAL inventory は API entry を表示するだけのため、Bukkit ItemStack の JSON 保存は行いません。
    }

    private @NotNull ItemStack itemOrAir(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return new ItemStack(Material.AIR);
        }
        return itemStack;
    }

    private ItemModel resolveItemModel(String itemId) {
        ItemModel loaded = itemService.findLoadedById(itemId);
        if (loaded != null) {
            return loaded;
        }
        return itemService.loadItem(itemId);
    }

    /**
     * Inventory entry から対応する ItemModel を解決します。
     *
     * @param entry 解決対象 entry
     * @return 対応する ItemModel。解決できない場合は null
     */
    private @Nullable ItemModel resolveItemModel(@NotNull InventoryEntryModel entry) {
        if (entry.getItemId() != null && !entry.getItemId().isBlank()) {
            return resolveItemModel(entry.getItemId());
        }
        InventoryInstanceType instanceType = InventoryInstanceType.fromCode(entry.getInstanceType());
        if (instanceType == null || entry.getInstanceId() == null) {
            return null;
        }
        return switch (instanceType) {
            case EQUIPMENT -> {
                EquipmentInstance instance = itemService.findEquipmentInstanceById(entry.getInstanceId().toString());
                yield instance == null ? null : resolveItemModel(instance.getItemId());
            }
            case RUNE -> {
                RuneInstance instance = itemService.findRuneInstanceById(entry.getInstanceId().toString());
                yield instance == null ? null : resolveItemModel(instance.getItemId());
            }
        };
    }

    /**
     * entry のアイテム情報を指定スロットへコピーする draft を生成します。
     *
     * @param entry コピー元 entry
     * @param slotIndex 保存先 slot_index
     * @return API 更新用 draft
     */
    private @NotNull InventoryEntryDraft copyEntryDraft(@NotNull InventoryEntryModel entry, @Nullable Integer slotIndex) {
        return new InventoryEntryDraft(
            slotIndex,
            entry.getItemCategory(),
            entry.getItemId(),
            entry.getInstanceType(),
            entry.getInstanceId(),
            entry.getQuantity(),
            entry.getMetadataJson()
        );
    }

    /**
     * entry が同じアイテム実体を指しているか判定します。
     *
     * @param left 比較元
     * @param right 比較先
     * @return 同一アイテムとみなせる場合 true
     */
    private boolean isSameEntryIdentity(@NotNull InventoryEntryModel left, @NotNull InventoryEntryModel right) {
        return left.getItemCategory().equalsIgnoreCase(right.getItemCategory())
            && java.util.Objects.equals(left.getItemId(), right.getItemId())
            && java.util.Objects.equals(left.getInstanceType(), right.getInstanceType())
            && java.util.Objects.equals(left.getInstanceId(), right.getInstanceId());
    }

    /**
     * 指定アイテムをインスタンスアイテムとして通常インベントリへ追加します。
     */
    private int addInstanceItems(
        @NotNull InventoryModel inventory,
        @NotNull ItemModel model,
        int amount,
        @NotNull InventoryInstanceType instanceType,
        @NotNull Set<Integer> usedSlots,
        @NotNull UUID accountId
    ) {
        int granted = 0;
        List<InventoryEntryDraft> drafts = getEntries(inventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .map(entry -> copyEntryDraft(entry, entry.getSlotIndex()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (int i = 0; i < amount; i++) {
            Integer slot = findNextFreeSlot(inventory, usedSlots);
            if (slot == null) {
                break;
            }

            UUID instanceId = createInstanceId(model, accountId, instanceType);
            if (instanceId == null) {
                break;
            }
            drafts.add(new InventoryEntryDraft(slot, model.getCategory(), null, instanceType.getCode(), instanceId, 1L, null));
            usedSlots.add(slot);
            granted++;
        }
        if (granted > 0) {
            replaceEntriesOptimistically(inventory.getInventoryId(), drafts, accountId);
        }
        return granted;
    }

    /**
     * 指定アイテムをスタック可能アイテムとして通常インベントリへ追加します。
     */
    private int addStackedItems(
        @NotNull InventoryModel inventory,
        @NotNull ItemModel model,
        int amount,
        @NotNull Set<Integer> usedSlots,
        @NotNull UUID accountId
    ) {
        int granted = 0;
        int remaining = amount;
        int maxStack = Math.max(1, model.getMaxStack());
        List<InventoryEntryModel> entries = getEntries(inventory.getInventoryId());
        List<InventoryEntryDraft> drafts = entries.stream()
            .filter(entry -> !entry.isDeleted())
            .map(entry -> copyEntryDraft(entry, entry.getSlotIndex()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        for (int index = 0; index < drafts.size(); index++) {
            if (remaining <= 0) {
                break;
            }
            InventoryEntryDraft draft = drafts.get(index);
            if (!isStackableDraft(draft, model, maxStack)) {
                continue;
            }

            long room = maxStack - draft.getQuantity();
            if (room <= 0) {
                continue;
            }

            int addAmount = (int) Math.min(room, remaining);
            drafts.set(index, new InventoryEntryDraft(
                draft.getSlotIndex(),
                draft.getItemCategory(),
                draft.getItemId(),
                draft.getInstanceType(),
                draft.getInstanceId(),
                draft.getQuantity() + addAmount,
                draft.getMetadataJson()
            ));
            granted += addAmount;
            remaining -= addAmount;
        }

        while (remaining > 0) {
            Integer slot = findNextFreeSlot(inventory, usedSlots);
            if (slot == null) {
                break;
            }

            int stackAmount = Math.min(maxStack, remaining);
            drafts.add(new InventoryEntryDraft(slot, model.getCategory(), model.getId(), null, null, (long) stackAmount, null));
            usedSlots.add(slot);
            granted += stackAmount;
            remaining -= stackAmount;
        }
        if (granted > 0) {
            replaceEntriesOptimistically(inventory.getInventoryId(), drafts, accountId);
        }
        return granted;
    }

    private boolean isStackableEntry(
        @NotNull InventoryEntryModel entry,
        @NotNull ItemModel model,
        int maxStack
    ) {
        if (maxStack <= 1) {
            return false;
        }
        if (entry.getItemId() == null || !entry.getItemId().equals(model.getId())) {
            return false;
        }
        if (!entry.getItemCategory().equalsIgnoreCase(model.getCategory())) {
            return false;
        }
        if (entry.getInstanceType() != null || entry.getInstanceId() != null) {
            return false;
        }
        return entry.getQuantity() < maxStack;
    }

    private boolean isStackableDraft(
        @NotNull InventoryEntryDraft draft,
        @NotNull ItemModel model,
        int maxStack
    ) {
        if (maxStack <= 1) {
            return false;
        }
        if (draft.getItemId() == null || !draft.getItemId().equals(model.getId())) {
            return false;
        }
        if (!draft.getItemCategory().equalsIgnoreCase(model.getCategory())) {
            return false;
        }
        if (draft.getInstanceType() != null || draft.getInstanceId() != null) {
            return false;
        }
        return draft.getQuantity() < maxStack;
    }

    /**
     * インスタンスタイプに応じて API 側の実体を作成し、UUID を返します。
     */
    private @Nullable UUID createInstanceId(
        @NotNull ItemModel model,
        @NotNull UUID accountId,
        @NotNull InventoryInstanceType instanceType
    ) {
        String instanceId = switch (instanceType) {
            case EQUIPMENT -> createEquipmentInstanceId(model, accountId);
            case RUNE -> createRuneInstanceId(model, accountId);
        };
        return instanceId == null ? null : parseUuidOrNull(instanceId);
    }

    /**
     * 装備インスタンスを生成し、生成されたインスタンスIDを返します。
     */
    private @Nullable String createEquipmentInstanceId(@NotNull ItemModel model, @NotNull UUID accountId) {
        EquipmentInstance instance = itemService.createEquipmentInstance(
            model.getId(),
            accountId.toString(),
            "command",
            accountId.toString()
        );
        return instance == null ? null : instance.getEquipmentInstanceId();
    }

    /**
     * ルーンインスタンスを生成し、生成されたインスタンスIDを返します。
     */
    private @Nullable String createRuneInstanceId(@NotNull ItemModel model, @NotNull UUID accountId) {
        RuneInstance instance = itemService.createRuneInstance(
            model.getId(),
            accountId.toString(),
            "command",
            accountId.toString()
        );
        return instance == null ? null : instance.getRuneInstanceId();
    }

    /**
     * UUID文字列をUUIDへ変換します。変換不能な場合は null を返します。
     */
    private UUID parseUuidOrNull(@NotNull String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void clearGuiInventory(Player bukkitPlayer) {
        var inventory = bukkitPlayer.getInventory();
        inventory.clear();

        var storage = inventory.getStorageContents();
        Arrays.fill(storage, new ItemStack(Material.AIR));
        inventory.setStorageContents(storage);

        inventory.setArmorContents(new ItemStack[] {
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
        });
        inventory.setItemInMainHand(new ItemStack(Material.AIR));
        inventory.setItemInOffHand(new ItemStack(Material.AIR));
    }

    /**
     * 通常表示領域（Bukkit storage slot 9〜35）だけを空にします。
     *
     * @param bukkitPlayer 対象プレイヤー
     */
    private void clearManagedStorageSlots(@NotNull Player bukkitPlayer) {
        PlayerInventory inventory = bukkitPlayer.getInventory();
        for (int dbSlot = NormalInventoryLayout.DB_SLOT_START; dbSlot <= NormalInventoryLayout.DB_SLOT_END; dbSlot++) {
            setStorageItemIfChanged(inventory, NormalInventoryLayout.toGuiSlotIndex(dbSlot), null);
        }
    }

    /**
     * 同等の ItemStack が既に入っている場合は Bukkit インベントリを書き換えず、不要な再配置表示を抑制します。
     *
     * @param inventory 対象インベントリ
     * @param bukkitSlot Bukkit storage slot
     * @param itemStack 設定したい ItemStack
     */
    private boolean setStorageItemIfChanged(
        @NotNull PlayerInventory inventory,
        int bukkitSlot,
        @Nullable ItemStack itemStack
    ) {
        ItemStack next = itemOrAir(itemStack);
        ItemStack current = inventory.getItem(bukkitSlot);
        if (isSameItemStack(current, next)) {
            return false;
        }
        inventory.setItem(bukkitSlot, next);
        return true;
    }

    /**
     * 表示上同一と扱える ItemStack か判定します。
     *
     * @param current 現在の ItemStack
     * @param next 反映予定の ItemStack
     * @return 同一表示であれば true
     */
    private boolean isSameItemStack(@Nullable ItemStack current, @Nullable ItemStack next) {
        boolean currentEmpty = current == null || current.getType() == Material.AIR;
        boolean nextEmpty = next == null || next.getType() == Material.AIR;
        if (currentEmpty || nextEmpty) {
            return currentEmpty == nextEmpty;
        }
        return current.getAmount() == next.getAmount() && current.isSimilar(next);
    }

    private boolean isDefaultProfile(@NotNull InventoryModel inventory) {
        return DEFAULT_PROFILE.getCode().equalsIgnoreCase(inventory.getInventoryProfile());
    }

    private @NotNull InventoryType resolveTargetInventoryType(@NotNull ItemModel model) {
        return switch (ItemCategory.fromApiValue(model.getCategory())) {
            case EQUIPMENT -> InventoryType.EQUIPMENT;
            case RUNE -> InventoryType.RUNE;
            case CURRENCY -> InventoryType.CURRENCY;
            default -> InventoryType.NORMAL;
        };
    }

    /**
     * Inventory entry のカテゴリから戻し先インベントリ種別を解決します。
     *
     * @param entry 対象 entry
     * @return 戻し先の InventoryType
     */
    private @NotNull InventoryType resolveTargetInventoryType(@NotNull InventoryEntryModel entry) {
        return switch (ItemCategory.fromApiValue(entry.getItemCategory())) {
            case EQUIPMENT -> InventoryType.EQUIPMENT;
            case RUNE -> InventoryType.RUNE;
            case CURRENCY -> InventoryType.CURRENCY;
            default -> InventoryType.NORMAL;
        };
    }

    /**
     * インベントリ種別に応じた使用済みスロットを収集します。
     *
     * @param inventory 対象インベントリ
     * @return 使用済みスロット集合
     */
    private @NotNull Set<Integer> collectUsedSlots(@NotNull InventoryModel inventory) {
        List<InventoryEntryModel> entries = getEntries(inventory.getInventoryId());
        if (inventory.getInventoryType() != InventoryType.CURRENCY) {
            return NormalInventoryLayout.collectUsedSlots(entries);
        }

        Set<Integer> usedSlots = new HashSet<>();
        for (InventoryEntryModel entry : entries) {
            Integer slotIndex = entry.getSlotIndex();
            if (slotIndex != null && slotIndex > 0 && !entry.isDeleted()) {
                usedSlots.add(slotIndex);
            }
        }
        return usedSlots;
    }

    /**
     * インベントリ種別に応じて次の空きスロットを返します。
     *
     * @param inventory 対象インベントリ
     * @param usedSlots 使用済みスロット集合
     * @return 空きスロット。上限に達している場合は null
     */
    private @Nullable Integer findNextFreeSlot(
        @NotNull InventoryModel inventory,
        @NotNull Set<Integer> usedSlots
    ) {
        if (inventory.getInventoryType() != InventoryType.CURRENCY) {
            return NormalInventoryLayout.findNextFreeSlot(usedSlots);
        }

        int candidate = NormalInventoryLayout.DB_SLOT_START;
        while (candidate > 0) {
            if (!usedSlots.contains(candidate)) {
                return candidate;
            }
            candidate++;
        }
        return null;
    }

    private @Nullable Integer resolveSlotCapacity(@NotNull InventoryType inventoryType) {
        if (inventoryType == InventoryType.CURRENCY) {
            return null;
        }
        return inventoryType.isSlotted() ? NormalInventoryLayout.CAPACITY : null;
    }

    // ---------------------------------------------------------------
    // 共通: アイテム返却 + 表示インベントリ自動切替 + スロット詰め
    // ---------------------------------------------------------------

    /**
     * 既存インスタンスや通常アイテムを所有インベントリへ戻し、
     * 必要であれば表示インベントリ種別を返却先カテゴリへ自動切替します。
     * <p>
     * 装備 GUI / 防具スロット / アクセサリスロットなど、Bukkit 上の管理スロットから
     * AstralRecord 管理対象のインベントリへアイテムを戻す操作で共通的に利用してください。
     * 既存の equipment_instance / rune_instance を保持したまま entry を再作成します。
     *
     * @param astPlayer 対象プレイヤー
     * @param itemStack 戻すアイテム（既存インスタンスを参照）
     * @return 戻し先インベントリ種別。戻せなかった場合は null
     */
    public @Nullable InventoryType returnItemToOwnedInventory(
        @NotNull AstPlayer astPlayer,
        @Nullable ItemStack itemStack
    ) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return null;
        }
        String itemId = ItemStackFactory.getAstralItemId(itemStack);
        String categoryCode = ItemStackFactory.getCategory(itemStack);
        if (itemId == null || categoryCode == null) {
            return null;
        }
        ItemModel model = resolveItemModel(itemId);
        if (model == null) {
            return null;
        }

        ItemCategory category = ItemCategory.fromApiValue(categoryCode);
        InventoryType targetType = resolveTargetInventoryType(model);
        UUID accountId = astPlayer.getAccount().getUuid();
        InventoryModel targetInventory = ensureInventory(accountId, targetType, resolveSlotCapacity(targetType), accountId);

        boolean added = switch (category) {
            case EQUIPMENT -> addExistingInstanceEntry(
                targetInventory,
                accountId,
                model,
                InventoryInstanceType.EQUIPMENT,
                ItemStackFactory.getEquipmentInstanceId(itemStack)
            );
            case RUNE -> addExistingInstanceEntry(
                targetInventory,
                accountId,
                model,
                InventoryInstanceType.RUNE,
                ItemStackFactory.getRuneInstanceId(itemStack)
            );
            default -> {
                int amount = Math.max(1, itemStack.getAmount());
                Set<Integer> usedSlots = collectUsedSlots(targetInventory);
                yield addStackedItems(targetInventory, model, amount, usedSlots, accountId) > 0;
            }
        };
        if (!added) {
            return null;
        }

        compactInventoryEntries(targetInventory.getInventoryId(), accountId);
        autoSwitchDisplayedInventory(astPlayer, targetType);
        return targetType;
    }

    /**
     * 表示インベントリが指定種別と異なる場合、自動的に対象種別へ切り替えます。
     * 切替条件は {@link io.github.maaasu.astralRecord.feature.account.model.AccountMode#shouldReflectInventoryToGui()} を満たす場合のみ。
     *
     * @param astPlayer 対象プレイヤー
     * @param targetType 切替先インベントリ種別
     */
    private void autoSwitchDisplayedInventory(@NotNull AstPlayer astPlayer, @NotNull InventoryType targetType) {
        if (!astPlayer.getAccount().getMode().shouldReflectInventoryToGui()) {
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        if (targetType == InventoryType.CURRENCY) {
            return;
        }
        if (getDisplayedInventoryType(accountId) == targetType) {
            applyDisplayedInventoryToGui(astPlayer);
            return;
        }
        applyInventoryToGui(astPlayer, targetType);
    }

    /**
     * 既存インスタンスを参照する entry を空きスロットへ追加します。
     *
     * @param inventory 追加先インベントリ
     * @param accountId 操作アカウントID
     * @param model 元 ItemModel（ログ・カテゴリ取得用）
     * @param instanceType インスタンス種別
     * @param instanceIdValue 既存インスタンスID（UUID 文字列）
     * @return 追加できた場合 true
     */
    private boolean addExistingInstanceEntry(
        @NotNull InventoryModel inventory,
        @NotNull UUID accountId,
        @NotNull ItemModel model,
        @NotNull InventoryInstanceType instanceType,
        @Nullable String instanceIdValue
    ) {
        UUID instanceId = instanceIdValue == null ? null : parseUuidOrNull(instanceIdValue);
        if (instanceId == null) {
            return false;
        }
        Set<Integer> usedSlots = collectUsedSlots(inventory);
        Integer slot = findNextFreeSlot(inventory, usedSlots);
        if (slot == null) {
            return false;
        }
        List<InventoryEntryDraft> drafts = getEntries(inventory.getInventoryId()).stream()
            .filter(entry -> !entry.isDeleted())
            .map(entry -> copyEntryDraft(entry, entry.getSlotIndex()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        drafts.add(new InventoryEntryDraft(slot, model.getCategory(), null, instanceType.getCode(), instanceId, 1L, null));
        replaceEntriesOptimistically(inventory.getInventoryId(), drafts, accountId);
        return true;
    }

    /**
     * 指定インベントリの entry を slot_index 1, 2, 3, ... の連続値に詰め直します。
     * <p>
     * slot_index が null の entry は既存スロットの後ろへ並べ替え、最終的に管理範囲内で歯抜けを解消します。
     *
     * @param inventoryId 対象インベントリID
     * @param accountId 操作アカウントID
     */
    public void compactInventoryEntries(@NotNull UUID inventoryId, @NotNull UUID accountId) {
        List<InventoryEntryModel> entries = getEntries(inventoryId).stream()
            .filter(entry -> !entry.isDeleted())
            .sorted(java.util.Comparator.<InventoryEntryModel, Integer>comparing(
                entry -> entry.getSlotIndex() == null ? Integer.MAX_VALUE : entry.getSlotIndex()
            ).thenComparing(InventoryEntryModel::getCreatedAt))
            .toList();

        InventoryModel inventory = findCachedInventory(inventoryId);
        boolean unlimitedSlots = inventory != null && inventory.getInventoryType() == InventoryType.CURRENCY;
        int next = NormalInventoryLayout.DB_SLOT_START;
        List<InventoryEntryDraft> drafts = new ArrayList<>();
        boolean changed = false;
        for (InventoryEntryModel entry : entries) {
            if (!unlimitedSlots && next > NormalInventoryLayout.DB_SLOT_END) {
                break;
            }
            Integer current = entry.getSlotIndex();
            if (current != null && current == next) {
                drafts.add(copyEntryDraft(entry, current));
                next++;
                continue;
            }
            drafts.add(copyEntryDraft(entry, next));
            changed = true;
            next++;
        }
        if (changed) {
            replaceEntriesOptimistically(inventoryId, drafts, accountId);
        }
    }
}
