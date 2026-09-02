package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemCurrency;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSummary;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentOrbOperationResult;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.SetEffect;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * アイテム機能の最小サービス。
 * APIから取得したアイテムをメモリに保持し、一覧/詳細参照に使用します。
 */
public class ItemService {
    public static final String DEFAULT_CURRENCY_ITEM_ID = "gold";
    public static final String LEGACY_DEFAULT_CURRENCY_ITEM_ID = "ast_gold";
    public static final String ASTRALD_CURRENCY_ITEM_ID = "astrald";
    public static final String STORAGE_EXPANSION_TOKEN_ITEM_ID = "storage_expansion_token";
    public static final String STORAGE_REMOTE_ACCESS_TOKEN_ITEM_ID = "storage_cloud_access_token";

    private final ItemRepository itemRepository;
    private final SetEffectRepository setEffectRepository;
    private volatile MasterDataSnapshot loadedMasterData;
    private volatile boolean masterDataSnapshotPublished;
    private final Map<String, SetEffect> loadedSetEffects;
    private final Map<String, EquipmentInstance> loadedEquipmentInstances;
    private final Map<String, Object> instanceReloadLocks;
    private final Map<String, PendingDurabilityUpdate> dirtyEquipmentDurability;
    private final Object equipmentStateMutex = new Object();
    private long durabilityRevision;

    public ItemService() {
        this(new ItemRepository(), new SetEffectRepository());
    }

    ItemService(
        @NotNull ItemRepository itemRepository,
        @NotNull SetEffectRepository setEffectRepository
    ) {
        this.itemRepository = itemRepository;
        this.setEffectRepository = setEffectRepository;
        this.loadedMasterData = new MasterDataSnapshot(Map.of(), Map.of());
        this.masterDataSnapshotPublished = false;
        this.loadedSetEffects = new ConcurrentHashMap<>();
        this.loadedEquipmentInstances = new ConcurrentHashMap<>();
        this.instanceReloadLocks = new ConcurrentHashMap<>();
        this.dirtyEquipmentDurability = new ConcurrentHashMap<>();
    }

    /**
     * 全カテゴリのアイテムを API から一括取得してキャッシュへ登録します。
     * 起動時の初期ロードに使用します。
     *
     * @return ロードしたアイテムの総件数
     */
    public int loadAll() {
        MasterDataSnapshot snapshot = loadMasterDataSnapshot();
        replaceMasterDataSnapshot(snapshot);
        return snapshot.size();
    }

    /**
     * API から全アイテムを取得し、公開前の immutable スナップショットを作成します。
     *
     * @return アイテムマスタスナップショット
     */
    public @NotNull MasterDataSnapshot loadMasterDataSnapshot() {
        Map<String, ItemModel> snapshot = new LinkedHashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();

        try {
            List<ItemSummary> summaries = itemRepository.findAll();
            for (ItemSummary summary : summaries) {
                ItemModel item = itemRepository.findById(summary.getId(), summary.getCategory());
                if (item == null) {
                    Logger.log(LogId.W_5200, summary.getCategory(), summary.getId());
                    throw new IllegalStateException(
                        "Item detail is unavailable: " + summary.getCategory() + ":" + summary.getId());
                }

                snapshot.put(normalize(item.getId()), item);
                categoryCounts.merge(item.getCategory().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, "loadAll");
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to prepare item master snapshot", e);
        }

        for (GoldDenomination denomination : GoldDenomination.values()) {
            ItemModel currency = createGoldCurrencyItem(denomination, denomination.itemId());
            snapshot.put(normalize(currency.getId()), currency);
            categoryCounts.merge(currency.getCategory().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        ItemModel astrald = createAstraldCurrencyItem();
        snapshot.put(normalize(astrald.getId()), astrald);
        categoryCounts.merge(astrald.getCategory().toLowerCase(Locale.ROOT), 1, Integer::sum);
        for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
            Logger.log(LogId.I_5202, entry.getKey(), entry.getValue());
        }

        Map<String, EnchantMaster> enchantMasters = new LinkedHashMap<>();
        Set<String> enchantMasterIds = snapshot.values().stream()
            .filter(item -> item.getOrb() != null && item.getOrb().getEffect() != null)
            .filter(item -> item.getOrb().getEffect().getType() == ItemOrbEffectType.ENCHANT)
            .map(item -> item.getOrb().getEffect().getEnchantMasterId())
            .filter(id -> id != null && !id.isBlank())
            .map(this::normalize)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (String enchantMasterId : enchantMasterIds) {
            EnchantMaster master = itemRepository.findEnchantMasterById(enchantMasterId);
            if (master == null) {
                throw new IllegalStateException("Enchant master is unavailable: " + enchantMasterId);
            }
            enchantMasters.put(normalize(master.getId()), master);
        }

        Logger.log(LogId.I_5203, snapshot.size());
        return new MasterDataSnapshot(snapshot, enchantMasters);
    }

    /**
     * 準備済みアイテムマスタを実行時キャッシュへ一括反映します。
     * 装備インスタンスなどのプレイヤー実行時状態は保持します。
     *
     * @param snapshot アイテムマスタスナップショット
     */
    public void replaceMasterDataSnapshot(@NotNull MasterDataSnapshot snapshot) {
        loadedMasterData = snapshot;
        masterDataSnapshotPublished = true;
        loadedSetEffects.clear();
        snapshot.items().values().forEach(item -> Logger.log(LogId.D_5203, item));
    }

    /** Reloads only filebase/API-backed item caches; runtime equipment state is preserved. */
    public void clearMasterDataCache() {
        loadedMasterData = new MasterDataSnapshot(Map.of(), Map.of());
        masterDataSnapshotPublished = false;
        loadedSetEffects.clear();
    }

    /**
     * アイテムマスタの完全スナップショットが公開済みかを返します。
     *
     * @return 全カテゴリのアイテムマスタスナップショットが公開済みの場合は {@code true}
     */
    public boolean isMasterDataLoaded() {
        return masterDataSnapshotPublished;
    }

    /**
     * 指定カテゴリのアイテムを API から一括取得してキャッシュへ登録します。
     *
     * @param category カテゴリ
     * @return ロードしたアイテム件数
     */
    public int loadAllByCategory(@NotNull String category) {
        String normalizedCategory = normalize(category);
        if (normalizedCategory.isBlank()) {
            return 0;
        }

        try {
            List<ItemModel> items = new ArrayList<>(
                itemRepository.findAllByCategory(normalizedCategory));
            int total = items.size();
            if (ItemCategory.CURRENCY.getApiValue().equalsIgnoreCase(normalizedCategory)) {
                List<ItemModel> builtInItems = createBuiltInItems();
                items.addAll(builtInItems);
                total += builtInItems.size();
            }
            cacheItemsWithEnchantDependencies(items);
            Logger.log(LogId.I_5202, normalizedCategory, total);
            return total;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, normalizedCategory);
            return 0;
        }
    }

    /**
     * アイテムをAPIから取得してロード済みキャッシュへ登録します。
     * カテゴリが不明な場合は一覧APIで解決します。
     *
     * @param itemId アイテム ID
     */
    public @Nullable ItemModel loadItem(@NotNull String itemId) {
        String normalizedId = normalize(itemId);
        if (normalizedId.isBlank()) {
            return null;
        }
        ItemModel builtin = resolveBuiltinItem(normalizedId);
        if (builtin != null) {
            cacheItem(builtin);
            return builtin;
        }

        try {
            List<ItemSummary> summaries = itemRepository.findAll();
            ItemSummary summary = summaries.stream()
                .filter(s -> normalizedId.equals(normalize(s.getId())))
                .findFirst()
                .orElse(null);
            if (summary == null) {
                return null;
            }
            return loadItem(summary.getId(), summary.getCategory());
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, normalizedId);
            return null;
        }
    }

    /**
     * アイテムをAPIから取得してロード済みキャッシュへ登録します。
     *
     * @param itemId   アイテム ID
     * @param category カテゴリ
     */
    public @Nullable ItemModel loadItem(@NotNull String itemId, @NotNull String category) {
        String normalizedId = normalize(itemId);
        if (normalizedId.isBlank()) {
            return null;
        }
        ItemModel builtin = resolveBuiltinItem(normalizedId);
        if (builtin != null) {
            cacheItem(builtin);
            return builtin;
        }

        ItemModel item = itemRepository.findById(itemId, category);
        if (item == null) {
            item = resolveBuiltinItem(normalizedId);
            if (item == null) {
                return null;
            }
        }

        cacheItem(item);
        return item;
    }

    /**
     * ロード済みアイテムの一覧を返します。
     */
    public @NotNull List<ItemModel> getLoadedItems() {
        return loadedMasterData.items().values().stream()
            .sorted(Comparator.comparing(ItemModel::getCategory).thenComparing(ItemModel::getId))
            .toList();
    }

    /**
     * 指定カテゴリでロード済みアイテムを絞り込みます。
     */
    public @NotNull List<ItemModel> getLoadedItemsByCategory(@NotNull String category) {
        String normalized = normalize(category);
        if (normalized.isBlank()) {
            return List.of();
        }

        return getLoadedItems().stream()
            .filter(item -> item.getCategory().equalsIgnoreCase(normalized))
            .toList();
    }

    /**
     * IDでロード済みアイテムを検索します。
     */
    public @Nullable ItemModel findLoadedById(@NotNull String itemId) {
        String normalizedId = normalize(itemId);
        if (normalizedId.isBlank()) {
            return null;
        }

        return loadedMasterData.items().get(normalizedId);
    }

    private @Nullable ItemModel resolveBuiltinItem(@NotNull String normalizedId) {
        GoldDenomination denomination = GoldDenomination.findByItemId(normalizedId);
        if (denomination != null) {
            return createGoldCurrencyItem(denomination, normalizedId);
        }
        if (LEGACY_DEFAULT_CURRENCY_ITEM_ID.equals(normalizedId)) {
            return createGoldCurrencyItem(GoldDenomination.GOLD, normalizedId);
        }
        if (ASTRALD_CURRENCY_ITEM_ID.equals(normalizedId)) {
            return createAstraldCurrencyItem();
        }
        return null;
    }

    private @NotNull List<ItemModel> createBuiltInItems() {
        List<ItemModel> items = new ArrayList<>();
        for (GoldDenomination denomination : GoldDenomination.values()) {
            items.add(createGoldCurrencyItem(denomination, denomination.itemId()));
        }
        items.add(createAstraldCurrencyItem());
        return List.copyOf(items);
    }

    private @NotNull ItemModel createGoldCurrencyItem(
        @NotNull GoldDenomination denomination,
        @NotNull String itemId
    ) {
        return new ItemModel(
            1,
            itemId,
            ItemCategory.CURRENCY.getApiValue(),
            denomination.displayName(),
            denomination.icon(),
            "common",
            64,
            0,
            null,
            null,
            List.of(denomination.goldValue() + "ゴールド相当の取引通貨です。"),
            false,
            true,
            null,
            new ItemCurrency("gold", "denomination", null),
            null,
            null,
            null,
            null,
            null
        );
    }

    private @NotNull ItemModel createAstraldCurrencyItem() {
        return new ItemModel(
            1,
            ASTRALD_CURRENCY_ITEM_ID,
            ItemCategory.CURRENCY.getApiValue(),
            "アストラルド",
            "AMETHYST_SHARD",
            "rare",
            64,
            0,
            null,
            null,
            List.of("サーバへの支援で受け取れる特別な通貨です。"),
            true,
            true,
            null,
            new ItemCurrency("astrald", "donation", null),
            null,
            null,
            null,
            null,
            null
        );
    }

    /**
     * セット効果 ID から定義を取得します。未キャッシュの場合は API 取得結果をキャッシュします。
     *
     * @param setId セット効果 ID
     * @return セット効果定義。見つからない場合は null
     */
    public @Nullable SetEffect findSetEffectById(@NotNull String setId) {
        String normalizedId = normalize(setId);
        if (normalizedId.isBlank()) {
            return null;
        }
        SetEffect cached = loadedSetEffects.get(normalizedId);
        if (cached != null) {
            return cached;
        }
        try {
            SetEffect loaded = setEffectRepository.findById(setId);
            if (loaded != null) {
                loadedSetEffects.put(normalizedId, loaded);
            }
            return loaded;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, setId);
            return null;
        }
    }

    public @NotNull List<String> getLoadedCategories() {
        return getLoadedItems().stream()
            .map(ItemModel::getCategory)
            .distinct()
            .toList();
    }

    public @NotNull List<String> getLoadedItemIds() {
        return getLoadedItems().stream()
            .map(ItemModel::getId)
            .distinct()
            .toList();
    }

    public boolean isSupportedCategory(@NotNull String category) {
        String normalized = normalize(category);
        return ItemCategory.supportedApiValues().stream()
            .anyMatch(supported -> supported.equals(normalized));
    }

    public @NotNull List<String> getSupportedCategories() {
        return ItemCategory.supportedApiValues();
    }

    /**
     * 装備インスタンスを API 経由で新規作成します。
     *
     * @param equipmentId アイテムテンプレート ID
     * @param accountId   所有アカウント ID（UUID 文字列）
     * @param source      取得元（例: "command", "loot_drop"）
     * @param createdBy   作成者アカウント ID（UUID 文字列）
     * @return 作成された装備インスタンス。失敗時は null
     */
    public @Nullable EquipmentInstance createEquipmentInstance(
        @NotNull String equipmentId,
        @NotNull String accountId,
        @NotNull String source,
        @NotNull String createdBy
    ) {
        try {
            EquipmentInstance instance = itemRepository.createEquipmentInstance(equipmentId, accountId, source, createdBy);
            if (instance != null) {
                synchronized (equipmentStateMutex) {
                    loadedEquipmentInstances.put(normalize(instance.getEquipmentInstanceId()), instance);
                }
            }
            return instance;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, equipmentId);
            return null;
        }
    }

    public @Nullable EquipmentInstance findEquipmentInstanceById(@NotNull String instanceId) {
        String normalizedId = normalize(instanceId);
        if (normalizedId.isBlank()) {
            return null;
        }
        EquipmentInstance cached;
        synchronized (equipmentStateMutex) {
            cached = loadedEquipmentInstances.get(normalizedId);
        }
        if (cached != null) {
            return cached;
        }
        try {
            EquipmentInstance loaded = itemRepository.findEquipmentInstanceById(instanceId);
            if (loaded != null) {
                synchronized (equipmentStateMutex) {
                    EquipmentInstance newer = loadedEquipmentInstances.get(normalizedId);
                    if (newer != null) {
                        return newer;
                    }
                    loadedEquipmentInstances.put(normalizedId, loaded);
                }
            }
            return loaded;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, instanceId);
            return null;
        }
    }

    /**
     * API通信を行わず、ロード済み装備個体だけを返します。
     *
     * @param instanceId 装備個体ID
     * @return キャッシュ済み個体。未ロードの場合は {@code null}
     */
    public @Nullable EquipmentInstance findLoadedEquipmentInstanceById(@NotNull String instanceId) {
        String normalizedId = normalize(instanceId);
        if (normalizedId.isBlank()) {
            return null;
        }
        synchronized (equipmentStateMutex) {
            return loadedEquipmentInstances.get(normalizedId);
        }
    }

    /**
     * 指定された装備個体を非同期I/Oスレッド用に事前ロードします。
     *
     * @param instanceIds 事前ロードする装備個体ID
     * @return 全個体を利用可能にできた結果。API障害と404を区別する
     */
    public @NotNull EquipmentPreloadResult preloadEquipmentInstances(@NotNull Collection<String> instanceIds) {
        boolean missing = false;
        for (String instanceId : instanceIds.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(id -> !id.isBlank())
            .distinct()
            .toList()) {
            if (findLoadedEquipmentInstanceById(instanceId) != null) {
                continue;
            }
            try {
                EquipmentInstance loaded = itemRepository.findEquipmentInstanceById(instanceId);
                if (loaded == null) {
                    missing = true;
                    continue;
                }
                String key = normalize(loaded.getEquipmentInstanceId());
                synchronized (equipmentStateMutex) {
                    loadedEquipmentInstances.putIfAbsent(key, loaded);
                }
            } catch (Exception exception) {
                Logger.log(LogId.E_5202, exception, instanceId);
                return EquipmentPreloadResult.UNAVAILABLE;
            }
        }
        return missing ? EquipmentPreloadResult.MISSING : EquipmentPreloadResult.COMPLETE;
    }

    /**
     * 指定された装備個体を API から強制再取得し、既存キャッシュを正本の内容で置換します。
     * <p>
     * トレードなどで API が装備個体の所有者を変更した直後に使用します。API 通信に失敗した場合は
     * 既存キャッシュを保持し、404 が返った個体だけをキャッシュから除去します。
     * </p>
     *
     * @param instanceIds 強制再取得する装備個体 ID
     * @return 全件の再取得結果。通信失敗は {@link EquipmentPreloadResult#UNAVAILABLE}、
     *         404 は {@link EquipmentPreloadResult#MISSING}
     */
    public @NotNull EquipmentPreloadResult reloadEquipmentInstances(@NotNull Collection<String> instanceIds) {
        boolean missing = false;
        boolean unavailable = false;
        for (String instanceId : instanceIds.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(id -> !id.isBlank())
            .distinct()
            .toList()) {
            String key = normalize(instanceId);
            synchronized (instanceReloadLock("equipment", key)) {
                EquipmentInstance cachedBefore;
                synchronized (equipmentStateMutex) {
                    cachedBefore = loadedEquipmentInstances.get(key);
                }
                try {
                    EquipmentInstance loaded = itemRepository.findEquipmentInstanceById(instanceId);
                    synchronized (equipmentStateMutex) {
                        if (loadedEquipmentInstances.get(key) != cachedBefore) {
                            unavailable = true;
                            continue;
                        }
                        if (loaded == null) {
                            loadedEquipmentInstances.remove(key);
                            dirtyEquipmentDurability.remove(key);
                            missing = true;
                        } else if (replaceEquipmentInstanceCacheLocked(key, loaded) == null) {
                            unavailable = true;
                        }
                    }
                } catch (Exception exception) {
                    Logger.log(LogId.E_5202, exception, instanceId);
                    unavailable = true;
                }
            }
        }
        return unavailable
            ? EquipmentPreloadResult.UNAVAILABLE
            : missing ? EquipmentPreloadResult.MISSING : EquipmentPreloadResult.COMPLETE;
    }

    /** API正本で個体本体を置換し、再取得中に発生した未保存耐久差分を保持します。 */
    private @Nullable EquipmentInstance replaceEquipmentInstanceCacheLocked(
        @NotNull String key,
        @NotNull EquipmentInstance loaded
    ) {
        PendingDurabilityUpdate pending = dirtyEquipmentDurability.get(key);
        if (pending == null) {
            loadedEquipmentInstances.put(key, loaded);
            return loaded;
        }
        int pendingDelta = 0;
        if (loaded.getDurabilityValue() == pending.baseDurabilityValue()) {
            if (pending.durabilityValue() != pending.baseDurabilityValue()) {
                pendingDelta = pending.durabilityValue() - pending.baseDurabilityValue();
            }
        } else if (loaded.getDurabilityValue() != pending.durabilityValue()) {
            // The API response is neither the pending base nor the already-applied local value.
            // Do not guess whether the response includes the dirty update; leave the cache and
            // dirty record untouched so the caller can retry through the recovery boundary.
            return null;
        }
        int mergedDurabilityValue = Math.max(0, Math.min(
            loaded.getDurabilityMax(),
            loaded.getDurabilityValue() + pendingDelta
        ));
        EquipmentInstance merged = new EquipmentInstance(
            loaded.getEquipmentInstanceId(),
            loaded.getAccountId(),
            loaded.getItemId(),
            loaded.getEnhanceLevel(),
            loaded.getRuneMaxSlots(),
            loaded.getTranscendenceRank(),
            loaded.getDurabilityMax(),
            mergedDurabilityValue,
            loaded.getCreatedAt(),
            loaded.getUpdatedAt(),
            loaded.getStatRolls(),
            loaded.getEnchants(),
            loaded.getRunes()
        );
        loadedEquipmentInstances.put(key, merged);
        dirtyEquipmentDurability.put(key, new PendingDurabilityUpdate(
            pending.instanceId(),
            loaded.getAccountId(),
            loaded.getDurabilityValue(),
            mergedDurabilityValue,
            loaded.getAccountId(),
            ++durabilityRevision
        ));
        return merged;
    }

    /** 同一個体の強制 reload 同士を直列化し、同じ trade の二 account lane が競合しないようにします。 */
    private @NotNull Object instanceReloadLock(@NotNull String instanceType, @NotNull String key) {
        return instanceReloadLocks.computeIfAbsent(instanceType + ":" + key, ignored -> new Object());
    }

    /**
     * オーブ装備操作をAPIへ送信し、確定装備を耐久dirtyと原子的にマージします。
     *
     * @param operationId 冪等操作ID
     * @param accountId 所有アカウントID
     * @param instanceId 対象装備個体ID
     * @param orbInventoryEntryId 共通消費順で直近に解決したオーブentry ID
     * @param orbItemId オーブitem ID
     * @param runeItemId 装着するルーンitem ID。脱着・通常操作ではnull
     * @param runeSlotIndex 脱着するルーンスロット。装着・通常操作ではnull
     * @return API確定結果。通信失敗時は {@code null}
     */
    public @Nullable EquipmentOrbOperationResult applyEquipmentOrbOperation(
        @NotNull String operationId,
        @NotNull String accountId,
        @NotNull String instanceId,
        @NotNull String orbInventoryEntryId,
        @NotNull String orbItemId,
        @Nullable String runeItemId,
        @Nullable Integer runeSlotIndex
    ) {
        try {
            return mergeOrbOperationResult(itemRepository.applyEquipmentOrbOperation(
                operationId, accountId, instanceId, orbInventoryEntryId, orbItemId, runeItemId, runeSlotIndex));
        } catch (Exception exception) {
            Logger.log(LogId.E_5202, exception, instanceId);
            return null;
        }
    }

    /**
     * 保存済みオーブ操作結果を照会し、確定装備をキャッシュへ反映します。
     *
     * @param operationId 冪等操作ID
     * @param accountId 所有アカウントID
     * @return 保存済み結果。未確定または通信失敗時は {@code null}
     */
    public @Nullable EquipmentOrbOperationResult findEquipmentOrbOperation(
        @NotNull String operationId,
        @NotNull String accountId
    ) {
        try {
            return mergeOrbOperationResult(itemRepository.findEquipmentOrbOperation(operationId, accountId));
        } catch (Exception exception) {
            Logger.log(LogId.E_5202, exception, operationId);
            return null;
        }
    }

    private @Nullable EquipmentOrbOperationResult mergeOrbOperationResult(
        @Nullable EquipmentOrbOperationResult result
    ) {
        if (result == null || result.getEquipment() == null) {
            return result;
        }
        EquipmentInstance merged = cacheEquipmentMutationResult(result.getEquipment());
        return new EquipmentOrbOperationResult(
            result.getOperationId(),
            result.getResult(),
            result.getOperationType(),
            merged,
            result.getTargetAvailable(),
            result.getAffectedInventoryEntryIds(),
            result.getPaymentConsumed(),
            result.getEnhancementSucceeded(),
            result.getFailAction(),
            result.getSuccessRate(),
            result.getRepairedAmount(),
            result.getTransitionName()
        );
    }

    /** 指定IDの共通エンチャントマスタを通信なしでスナップショットから取得します。 */
    public @Nullable EnchantMaster findEnchantMasterById(@NotNull String enchantMasterId) {
        String key = normalize(enchantMasterId);
        return loadedMasterData.enchantMasters().get(key);
    }

    private @NotNull EquipmentInstance cacheEquipmentMutationResult(@NotNull EquipmentInstance result) {
        String key = normalize(result.getEquipmentInstanceId());
        synchronized (equipmentStateMutex) {
            return cacheEquipmentMutationResultLocked(key, result);
        }
    }

    private @NotNull EquipmentInstance cacheEquipmentMutationResultLocked(
        @NotNull String key,
        @NotNull EquipmentInstance result
    ) {
        PendingDurabilityUpdate pending = dirtyEquipmentDurability.get(key);
        if (pending == null) {
            loadedEquipmentInstances.put(key, result);
            return result;
        }
        int mergedDurabilityMax = result.getDurabilityMax();
        // pre-saveで確定したbaseline以前の損耗はAPI結果へ既に含まれる。
        // operation待機中に発生した差分だけを結果へ重ね、REPAIR効果を古い欠損量で打ち消さない。
        int pendingDelta = pending.durabilityValue() - pending.baseDurabilityValue();
        int mergedDurabilityValue = Math.max(0, Math.min(
            mergedDurabilityMax,
            result.getDurabilityValue() + pendingDelta
        ));
        EquipmentInstance merged = new EquipmentInstance(
            result.getEquipmentInstanceId(),
            result.getAccountId(),
            result.getItemId(),
            result.getEnhanceLevel(),
            result.getRuneMaxSlots(),
            result.getTranscendenceRank(),
            mergedDurabilityMax,
            mergedDurabilityValue,
            result.getCreatedAt(),
            result.getUpdatedAt(),
            result.getStatRolls(),
            result.getEnchants(),
            result.getRunes()
        );
        loadedEquipmentInstances.put(key, merged);
        dirtyEquipmentDurability.put(key, new PendingDurabilityUpdate(
            pending.instanceId(),
            pending.accountId(),
            result.getDurabilityValue(),
            mergedDurabilityValue,
            pending.updatedBy(),
            ++durabilityRevision
        ));
        return merged;
    }

    /**
     * 装備耐久値を plugin 側キャッシュへ即時反映し、次回保存時の API flush 対象として記録します。
     * 戦闘中の同期 HTTP を避けるため、このメソッド自体は API を呼びません。
     *
     * @param instanceId 装備インスタンス ID
     * @param durabilityValue 反映する現在耐久値
     * @param updatedBy 更新者アカウント ID
     * @return 更新後のキャッシュ上装備インスタンス。対象が見つからない場合は {@code null}
     */
    public @Nullable EquipmentInstance updateEquipmentDurability(
        @NotNull String instanceId,
        int durabilityValue,
        @NotNull String updatedBy
    ) {
        String normalizedId = normalize(instanceId);
        if (normalizedId.isBlank()) {
            return null;
        }
        synchronized (equipmentStateMutex) {
            EquipmentInstance current = loadedEquipmentInstances.get(normalizedId);
            if (current == null) {
                return null;
            }
            int clampedValue = Math.max(0, Math.min(current.getDurabilityMax(), durabilityValue));
            PendingDurabilityUpdate previousPending = dirtyEquipmentDurability.get(normalizedId);
            int baseDurabilityValue = previousPending == null
                ? current.getDurabilityValue()
                : previousPending.baseDurabilityValue();
            EquipmentInstance updated = new EquipmentInstance(
                current.getEquipmentInstanceId(),
                current.getAccountId(),
                current.getItemId(),
                current.getEnhanceLevel(),
                current.getRuneMaxSlots(),
                current.getTranscendenceRank(),
                current.getDurabilityMax(),
                clampedValue,
                current.getCreatedAt(),
                LocalDateTime.now().toString(),
                current.getStatRolls(),
                current.getEnchants(),
                current.getRunes()
            );
            loadedEquipmentInstances.put(normalizedId, updated);
            dirtyEquipmentDurability.put(
                normalizedId,
                new PendingDurabilityUpdate(
                    updated.getEquipmentInstanceId(),
                    updated.getAccountId(),
                    baseDurabilityValue,
                    clampedValue,
                    updatedBy,
                    ++durabilityRevision
                )
            );
            return updated;
        }
    }

    /**
     * 対象アカウントに未保存の装備耐久値変更があるかを判定します。
     *
     * @param accountId 対象アカウント ID
     * @return 未保存の耐久値変更がある場合は {@code true}
     */
    public boolean hasDirtyEquipmentDurability(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        synchronized (equipmentStateMutex) {
            return dirtyEquipmentDurability.values().stream()
                .anyMatch(update -> update.accountId().equalsIgnoreCase(targetAccountId));
        }
    }

    /**
     * 対象アカウントの未保存装備耐久値を API へ反映します。
     * 失敗した更新は dirty に残し、次回保存で再試行できる状態にします。
     *
     * @param accountId 対象アカウント ID
     * @return 対象の dirty 更新をすべて反映できた場合は {@code true}
     */
    public boolean flushDirtyEquipmentDurability(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        List<Map.Entry<String, PendingDurabilityUpdate>> snapshots;
        synchronized (equipmentStateMutex) {
            snapshots = dirtyEquipmentDurability.entrySet().stream()
                .filter(entry -> entry.getValue().accountId().equalsIgnoreCase(targetAccountId))
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        }
        boolean allOk = true;
        for (Map.Entry<String, PendingDurabilityUpdate> entry : snapshots) {
            PendingDurabilityUpdate pending = entry.getValue();
            try {
                EquipmentInstance persisted = itemRepository.updateEquipmentDurability(
                    pending.instanceId(),
                    pending.durabilityValue(),
                    pending.updatedBy()
                );
                if (persisted != null) {
                    synchronized (equipmentStateMutex) {
                        PendingDurabilityUpdate current = dirtyEquipmentDurability.get(entry.getKey());
                        if (current != null && current.revision() == pending.revision()) {
                            loadedEquipmentInstances.put(entry.getKey(), persisted);
                            dirtyEquipmentDurability.remove(entry.getKey());
                        } else {
                            allOk = false;
                        }
                    }
                } else {
                    allOk = false;
                }
            } catch (RuntimeException e) {
                Logger.warn(LogId.W_5252, pending.instanceId(), e.getMessage());
                allOk = false;
            }
        }
        return allOk;
    }

    /**
     * 対象アカウントの未保存装備耐久値を破棄します。
     * ログアウト後の state 破棄と同じ境界で呼び出します。
     *
     * @param accountId 対象アカウント ID
     */
    public void clearDirtyEquipmentDurability(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        synchronized (equipmentStateMutex) {
            dirtyEquipmentDurability.entrySet().removeIf(
                entry -> entry.getValue().accountId().equalsIgnoreCase(targetAccountId));
        }
    }

    /** 保存成功後に対象アカウントの装備個体 cache と durability dirty を同じ境界で破棄します。 */
    public void clearEquipmentState(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        synchronized (equipmentStateMutex) {
            loadedEquipmentInstances.entrySet().removeIf(
                entry -> entry.getValue().getAccountId().equalsIgnoreCase(targetAccountId));
            dirtyEquipmentDurability.entrySet().removeIf(
                entry -> entry.getValue().accountId().equalsIgnoreCase(targetAccountId));
        }
    }

    /**
     * API が削除・譲渡済みと確定した装備個体を、通信せずローカル状態から破棄します。
     * durability dirty も同じ排他境界で除去し、後続保存による旧所有者からの復活を防ぎます。
     *
     * @param instanceId API 正本で利用不能と確定した装備個体 ID
     */
    public void evictEquipmentInstanceFromCache(@NotNull String instanceId) {
        String normalizedId = normalize(instanceId);
        if (normalizedId.isBlank()) {
            return;
        }
        synchronized (equipmentStateMutex) {
            loadedEquipmentInstances.remove(normalizedId);
            dirtyEquipmentDurability.remove(normalizedId);
        }
    }

    private record PendingDurabilityUpdate(
        @NotNull String instanceId,
        @NotNull String accountId,
        int baseDurabilityValue,
        int durabilityValue,
        @NotNull String updatedBy,
        long revision
    ) {
    }

    public boolean deleteEquipmentInstance(@NotNull String instanceId) {
        String normalizedId = normalize(instanceId);
        if (normalizedId.isBlank()) {
            return false;
        }
        try {
            boolean deleted = itemRepository.deleteEquipmentInstance(instanceId);
            if (deleted) {
                synchronized (equipmentStateMutex) {
                    loadedEquipmentInstances.remove(normalizedId);
                    dirtyEquipmentDurability.remove(normalizedId);
                }
            }
            return deleted;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, instanceId);
            return false;
        }
    }

    /**
     * アイテムをキャッシュへ登録し、詳細情報を debug ログへ出力します。
     *
     * @param item 登録するアイテム
     */
    private void cacheItem(@NotNull ItemModel item) {
        cacheItemsWithEnchantDependencies(List.of(item));
    }

    /**
     * 参照する共通エンチャントマスタを先に全件取得し、itemと同じ世代で公開します。
     * 依存取得が失敗した場合は公開済みsnapshotを変更しません。
     */
    private void cacheItemsWithEnchantDependencies(@NotNull List<ItemModel> items) {
        Map<String, EnchantMaster> resolvedEnchantMasters = new LinkedHashMap<>();
        Set<String> enchantMasterIds = items.stream()
            .filter(item -> item.getOrb() != null && item.getOrb().getEffect() != null)
            .filter(item -> item.getOrb().getEffect().getType() == ItemOrbEffectType.ENCHANT)
            .map(item -> item.getOrb().getEffect().getEnchantMasterId())
            .filter(id -> id != null && !id.isBlank())
            .map(this::normalize)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        for (String enchantMasterId : enchantMasterIds) {
            EnchantMaster master = itemRepository.findEnchantMasterById(enchantMasterId);
            if (master == null) {
                throw new IllegalStateException("Enchant master is unavailable: " + enchantMasterId);
            }
            resolvedEnchantMasters.put(normalize(master.getId()), master);
        }

        publishCachedItems(items, resolvedEnchantMasters);
    }

    private synchronized void publishCachedItems(
        @NotNull List<ItemModel> items,
        @NotNull Map<String, EnchantMaster> resolvedEnchantMasters
    ) {
        Map<String, ItemModel> updatedItems = new LinkedHashMap<>(loadedMasterData.items());
        items.forEach(item -> updatedItems.put(normalize(item.getId()), item));
        Map<String, EnchantMaster> updatedEnchantMasters =
            new LinkedHashMap<>(loadedMasterData.enchantMasters());
        updatedEnchantMasters.putAll(resolvedEnchantMasters);
        loadedMasterData = new MasterDataSnapshot(updatedItems, updatedEnchantMasters);
        items.forEach(item -> Logger.log(LogId.D_5203, item));
    }

    /** 原子的に公開するアイテム・共通エンチャントマスタのスナップショットです。 */
    public record MasterDataSnapshot(
        @NotNull Map<String, ItemModel> items,
        @NotNull Map<String, EnchantMaster> enchantMasters
    ) {
        public MasterDataSnapshot {
            items = Map.copyOf(items);
            enchantMasters = Map.copyOf(enchantMasters);
        }

        /** @return アイテム件数 */
        public int size() {
            return items.size();
        }
    }

    /** 装備個体事前ロード結果です。 */
    public enum EquipmentPreloadResult {
        COMPLETE,
        MISSING,
        UNAVAILABLE,
    }

    private @NotNull String normalize(@NotNull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
