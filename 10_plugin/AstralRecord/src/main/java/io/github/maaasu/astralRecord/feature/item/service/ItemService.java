package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemCurrency;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSummary;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
import io.github.maaasu.astralRecord.feature.item.model.EnchantMaster;
import io.github.maaasu.astralRecord.feature.item.model.SetEffect;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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
    /** snapshot 保存へ渡す装備全体の最新世代。耐久だけの変更も同じ集合へ含める。 */
    private final Map<String, DirtyEquipmentState> dirtyEquipmentState;
    /** 完成 player-state snapshot の transaction 内で新規作成する装備個体 ID。 */
    private final Set<String> pendingEquipmentCreations;
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
        this.dirtyEquipmentState = new ConcurrentHashMap<>();
        this.pendingEquipmentCreations = ConcurrentHashMap.newKeySet();
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
     * 装備個体を通信せずに生成し、次の player-state snapshot で inventory entry と同時作成します。
     * 乱数値と UUID は呼出時に一度だけ確定するため、同じ snapshot の再送でも変化しません。
     *
     * @param model 装備マスタ
     * @param accountId 所有アカウント
     * @return ローカル確定した新規装備。装備マスタでない場合は null
     */
    public @Nullable EquipmentInstance createLocalEquipmentInstance(
        @NotNull ItemModel model,
        @NotNull UUID accountId
    ) {
        try {
            var equipment = model.getEquipment();
            if (equipment == null) {
                return null;
            }

            String instanceId = UUID.randomUUID().toString();
            int runeMaxSlots = equipment.getRune() == null
                ? 0
                : resolveRandomInt(equipment.getRune().getMaxSlotsRaw());
            int durabilityMax = equipment.getDurability() == null
                ? 0
                : Math.max(0, equipment.getDurability().getMax());
            List<EquipmentStatRoll> statRolls = new ArrayList<>();
            int sortOrder = 0;
            for (var stat : equipment.getStats()) {
                if (stat.getStatus() == null || stat.getStatus().isBlank()) {
                    continue;
                }
                statRolls.add(new EquipmentStatRoll(
                    UUID.randomUUID().toString(),
                    stat.getStatus().trim(),
                    resolveRandomNumericString(stat.getRawMin(), stat.getMin()),
                    resolveRandomNumericString(stat.getRawMax(), stat.getMax()),
                    sortOrder++
                ));
            }
            String now = Instant.now().toString();
            EquipmentInstance instance = new EquipmentInstance(
                instanceId,
                accountId.toString(),
                model.getId(),
                0,
                Math.max(0, runeMaxSlots),
                0,
                durabilityMax,
                durabilityMax,
                now,
                now,
                List.copyOf(statRolls),
                List.of(),
                List.of()
            );
            String key = normalize(instanceId);
            synchronized (equipmentStateMutex) {
                loadedEquipmentInstances.put(key, instance);
                pendingEquipmentCreations.add(key);
                markEquipmentStateDirty(key, instance);
            }
            return instance;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_5202, exception, model.getId());
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
     * APIを待たずに確定した装備状態をキャッシュへ反映します。
     * <p>
     * 完全スナップショット保存の対象として記録します。耐久だけの変更も同じdirty集合で
     * capture/ackされるため、別経路の保存がオーブ操作の値を上書きしません。
     * </p>
     *
     * @param instance Plugin側で計算済みの装備個体
     * @return 反映した装備個体。不正なIDの場合はnull
     */
    public @Nullable EquipmentInstance applyLocalEquipmentInstance(
        @NotNull EquipmentInstance instance
    ) {
        String key = normalize(instance.getEquipmentInstanceId());
        if (key.isBlank()) {
            return null;
        }
        synchronized (equipmentStateMutex) {
            loadedEquipmentInstances.put(key, instance);
            markEquipmentStateDirty(key, instance);
            return instance;
        }
    }

    /**
     * 重要操作の保存失敗時に、対象アカウントの装備 cache と dirty 世代を操作前へ戻す補償を返します。
     */
    public @NotNull Runnable captureEquipmentStateRollback(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        Map<String, EquipmentInstance> loadedBefore = new LinkedHashMap<>();
        Map<String, PendingDurabilityUpdate> durabilityBefore = new LinkedHashMap<>();
        Map<String, DirtyEquipmentState> dirtyBefore = new LinkedHashMap<>();
        Set<String> pendingCreationsBefore = new HashSet<>();
        synchronized (equipmentStateMutex) {
            loadedEquipmentInstances.forEach((key, instance) -> {
                if (instance.getAccountId().equalsIgnoreCase(targetAccountId)) {
                    loadedBefore.put(key, instance);
                    if (pendingEquipmentCreations.contains(key)) pendingCreationsBefore.add(key);
                }
            });
            dirtyEquipmentDurability.forEach((key, pending) -> {
                if (pending.accountId().equalsIgnoreCase(targetAccountId)) durabilityBefore.put(key, pending);
            });
            dirtyEquipmentState.forEach((key, dirty) -> {
                if (dirty.instance().getAccountId().equalsIgnoreCase(targetAccountId)) dirtyBefore.put(key, dirty);
            });
        }
        return () -> {
            synchronized (equipmentStateMutex) {
                Set<String> currentAccountInstanceIds = loadedEquipmentInstances.entrySet().stream()
                    .filter(entry -> entry.getValue().getAccountId().equalsIgnoreCase(targetAccountId))
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
                pendingEquipmentCreations.removeAll(currentAccountInstanceIds);
                loadedEquipmentInstances.entrySet().removeIf(
                    entry -> entry.getValue().getAccountId().equalsIgnoreCase(targetAccountId));
                dirtyEquipmentDurability.entrySet().removeIf(
                    entry -> entry.getValue().accountId().equalsIgnoreCase(targetAccountId));
                dirtyEquipmentState.entrySet().removeIf(
                    entry -> entry.getValue().instance().getAccountId().equalsIgnoreCase(targetAccountId));
                loadedEquipmentInstances.putAll(loadedBefore);
                dirtyEquipmentDurability.putAll(durabilityBefore);
                dirtyEquipmentState.putAll(dirtyBefore);
                pendingEquipmentCreations.addAll(pendingCreationsBefore);
            }
        };
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
        long revision = ++durabilityRevision;
        dirtyEquipmentState.put(key, new DirtyEquipmentState(merged, revision));
        dirtyEquipmentDurability.put(key, new PendingDurabilityUpdate(
            pending.instanceId(),
            loaded.getAccountId(),
            loaded.getDurabilityValue(),
            mergedDurabilityValue,
            loaded.getAccountId(),
            revision
        ));
        return merged;
    }

    /** 同一個体の強制 reload 同士を直列化し、同じ trade の二 account lane が競合しないようにします。 */
    private @NotNull Object instanceReloadLock(@NotNull String instanceType, @NotNull String key) {
        return instanceReloadLocks.computeIfAbsent(instanceType + ":" + key, ignored -> new Object());
    }

    /** 指定IDの共通エンチャントマスタを通信なしでスナップショットから取得します。 */
    public @Nullable EnchantMaster findEnchantMasterById(@NotNull String enchantMasterId) {
        String key = normalize(enchantMasterId);
        return loadedMasterData.enchantMasters().get(key);
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
                current.getUpdatedAt(),
                current.getStatRolls(),
                current.getEnchants(),
                current.getRunes()
            );
            loadedEquipmentInstances.put(normalizedId, updated);
            markEquipmentStateDirty(normalizedId, updated);
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
     * 対象アカウントの未保存装備全体を、同一の装備状態世代として取得します。
     * 呼出側は inventory state lock 中にこの戻り値と所持品スナップショットを同時にcaptureすること。
     *
     * @param accountId 対象アカウント ID
     * @return 保存対象の装備個体。返却順は装備個体ID順
     */
    public @NotNull List<EquipmentInstance> snapshotDirtyEquipmentState(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        synchronized (equipmentStateMutex) {
            return dirtyEquipmentState.values().stream()
                .map(DirtyEquipmentState::instance)
                .filter(instance -> instance.getAccountId().equalsIgnoreCase(targetAccountId))
                .sorted(Comparator.comparing(EquipmentInstance::getEquipmentInstanceId, String.CASE_INSENSITIVE_ORDER))
                .toList();
        }
    }

    /** player-state snapshot に新規作成として含める装備個体 ID を返します。 */
    public @NotNull Set<String> snapshotPendingEquipmentCreationIds(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        synchronized (equipmentStateMutex) {
            return loadedEquipmentInstances.entrySet().stream()
                .filter(entry -> pendingEquipmentCreations.contains(entry.getKey()))
                .filter(entry -> entry.getValue().getAccountId().equalsIgnoreCase(targetAccountId))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    /**
     * 保存済みcaptureを確認し、capture以後に変化していないdirtyだけを解除します。
     * APIが返した更新日時はcapture世代が古くても現在cacheの同一個体へ反映し、後続の保存の
     * optimistic concurrency基準を最新化します。
     *
     * @param accountId 対象アカウント ID
     * @param captured 保存要求へ同梱した装備個体
     * @param updatedAtById APIが確定した装備個体IDごとの更新日時
     */
    public void acknowledgeEquipmentState(
        @NotNull UUID accountId,
        @NotNull List<EquipmentInstance> captured,
        @NotNull Map<String, String> updatedAtById
    ) {
        String targetAccountId = accountId.toString();
        Map<String, EquipmentInstance> capturedById = captured.stream()
            .filter(instance -> instance.getAccountId().equalsIgnoreCase(targetAccountId))
            .collect(java.util.stream.Collectors.toMap(
                instance -> normalize(instance.getEquipmentInstanceId()),
                instance -> instance,
                (left, right) -> right,
                LinkedHashMap::new
            ));
        synchronized (equipmentStateMutex) {
            for (Map.Entry<String, EquipmentInstance> entry : capturedById.entrySet()) {
                DirtyEquipmentState currentDirty = dirtyEquipmentState.get(entry.getKey());
                if (currentDirty != null && currentDirty.instance().equals(entry.getValue())) {
                    dirtyEquipmentState.remove(entry.getKey(), currentDirty);
                    dirtyEquipmentDurability.remove(entry.getKey());
                    pendingEquipmentCreations.remove(entry.getKey());
                }
            }
            updatedAtById.forEach((instanceId, updatedAt) -> {
                String key = normalize(instanceId);
                EquipmentInstance current = loadedEquipmentInstances.get(key);
                if (current == null || updatedAt == null || updatedAt.isBlank()) {
                    return;
                }
                loadedEquipmentInstances.put(key, withUpdatedAt(current, updatedAt));
                DirtyEquipmentState dirty = dirtyEquipmentState.get(key);
                if (dirty != null) {
                    dirtyEquipmentState.put(key, new DirtyEquipmentState(withUpdatedAt(dirty.instance(), updatedAt), dirty.revision()));
                }
            });
        }
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
            dirtyEquipmentState.entrySet().removeIf(
                entry -> entry.getValue().instance().getAccountId().equalsIgnoreCase(targetAccountId));
            pendingEquipmentCreations.removeIf(key -> {
                EquipmentInstance instance = loadedEquipmentInstances.get(key);
                return instance == null || instance.getAccountId().equalsIgnoreCase(targetAccountId);
            });
        }
    }

    /** 保存成功後に対象アカウントの装備個体 cache と durability dirty を同じ境界で破棄します。 */
    public void clearEquipmentState(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        synchronized (equipmentStateMutex) {
            Set<String> targetInstanceIds = loadedEquipmentInstances.entrySet().stream()
                .filter(entry -> entry.getValue().getAccountId().equalsIgnoreCase(targetAccountId))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
            pendingEquipmentCreations.removeAll(targetInstanceIds);
            loadedEquipmentInstances.entrySet().removeIf(
                entry -> entry.getValue().getAccountId().equalsIgnoreCase(targetAccountId));
            dirtyEquipmentDurability.entrySet().removeIf(
                entry -> entry.getValue().accountId().equalsIgnoreCase(targetAccountId));
            dirtyEquipmentState.entrySet().removeIf(
                entry -> entry.getValue().instance().getAccountId().equalsIgnoreCase(targetAccountId));
        }
    }

    /**
     * 保存前に破棄する個体、または API が削除・譲渡済みと確定した個体をローカル状態から破棄します。
     * durability dirty も同じ排他境界で除去し、後続保存による旧所有者からの復活を防ぎます。
     *
     * @param instanceId 破棄する装備個体 ID
     */
    public void evictEquipmentInstanceFromCache(@NotNull String instanceId) {
        String normalizedId = normalize(instanceId);
        if (normalizedId.isBlank()) {
            return;
        }
        synchronized (equipmentStateMutex) {
            loadedEquipmentInstances.remove(normalizedId);
            dirtyEquipmentDurability.remove(normalizedId);
            dirtyEquipmentState.remove(normalizedId);
            pendingEquipmentCreations.remove(normalizedId);
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

    /** 完全スナップショットへ保存する装備状態と、そのローカル更新世代です。 */
    private record DirtyEquipmentState(@NotNull EquipmentInstance instance, long revision) {
    }

    /** 呼出元が equipmentStateMutex を保持している前提で、最新装備状態をdirtyとして記録します。 */
    private void markEquipmentStateDirty(@NotNull String key, @NotNull EquipmentInstance instance) {
        dirtyEquipmentState.put(key, new DirtyEquipmentState(instance, ++durabilityRevision));
    }

    /** 装備個体の更新日時だけをAPI確定値へ差し替えます。 */
    private @NotNull EquipmentInstance withUpdatedAt(@NotNull EquipmentInstance current, @NotNull String updatedAt) {
        return new EquipmentInstance(
            current.getEquipmentInstanceId(), current.getAccountId(), current.getItemId(),
            current.getEnhanceLevel(), current.getRuneMaxSlots(), current.getTranscendenceRank(),
            current.getDurabilityMax(), current.getDurabilityValue(), current.getCreatedAt(), updatedAt,
            current.getStatRolls(), current.getEnchants(), current.getRunes()
        );
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

    /** API 側の RangeValueResolver と同じ固定値 / min~max 契約をローカルで解決します。 */
    private int resolveRandomInt(@NotNull String rawValue) {
        String value = rawValue.trim().replace('～', '~');
        int separator = value.indexOf('~');
        if (separator < 0) {
            return Integer.parseInt(value);
        }
        int min = Integer.parseInt(value.substring(0, separator).trim());
        int max = Integer.parseInt(value.substring(separator + 1).trim());
        if (min > max) {
            int swapped = min;
            min = max;
            max = swapped;
        }
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, Math.addExact(max, 1));
    }

    /** API 側と同様に、小数範囲は小数第4位へ丸めて一度だけ確定します。 */
    private @NotNull String resolveRandomNumericString(@Nullable String rawValue, double fallbackValue) {
        String value = rawValue == null || rawValue.isBlank()
            ? BigDecimal.valueOf(fallbackValue).stripTrailingZeros().toPlainString()
            : rawValue.trim().replace('～', '~');
        int separator = value.indexOf('~');
        if (separator < 0) {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        }
        String minText = value.substring(0, separator).trim();
        String maxText = value.substring(separator + 1).trim();
        try {
            int min = Integer.parseInt(minText);
            int max = Integer.parseInt(maxText);
            if (min > max) {
                int swapped = min;
                min = max;
                max = swapped;
            }
            return Integer.toString(min == max
                ? min
                : ThreadLocalRandom.current().nextInt(min, Math.addExact(max, 1)));
        } catch (NumberFormatException ignored) {
            BigDecimal min = new BigDecimal(minText);
            BigDecimal max = new BigDecimal(maxText);
            if (min.compareTo(max) > 0) {
                BigDecimal swapped = min;
                min = max;
                max = swapped;
            }
            BigDecimal sample = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble());
            return min.add(max.subtract(min).multiply(sample))
                .setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
        }
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
