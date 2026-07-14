package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemCurrency;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSummary;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.model.SetEffect;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final ItemRepository itemRepository;
    private final SetEffectRepository setEffectRepository;
    private final Map<String, ItemModel> loadedItems;
    private final Map<String, SetEffect> loadedSetEffects;
    private final Map<String, EquipmentInstance> loadedEquipmentInstances;
    private final Map<String, RuneInstance> loadedRuneInstances;
    private final Map<String, PendingDurabilityUpdate> dirtyEquipmentDurability;

    public ItemService() {
        this.itemRepository = new ItemRepository();
        this.setEffectRepository = new SetEffectRepository();
        this.loadedItems = new ConcurrentHashMap<>();
        this.loadedSetEffects = new ConcurrentHashMap<>();
        this.loadedEquipmentInstances = new ConcurrentHashMap<>();
        this.loadedRuneInstances = new ConcurrentHashMap<>();
        this.dirtyEquipmentDurability = new ConcurrentHashMap<>();
    }

    /**
     * 全カテゴリのアイテムを API から一括取得してキャッシュへ登録します。
     * 起動時の初期ロードに使用します。
     *
     * @return ロードしたアイテムの総件数
     */
    public int loadAll() {
        int total = 0;
        Map<String, Integer> categoryCounts = new HashMap<>();

        try {
            List<ItemSummary> summaries = itemRepository.findAll();
            for (ItemSummary summary : summaries) {
                ItemModel item = itemRepository.findById(summary.getId(), summary.getCategory());
                if (item == null) {
                    Logger.log(LogId.W_5200, summary.getCategory(), summary.getId());
                    continue;
                }

                cacheItem(item);
                categoryCounts.merge(item.getCategory().toLowerCase(Locale.ROOT), 1, Integer::sum);
                total++;
            }
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, "loadAll");
        }

        total += cacheBuiltInItems(categoryCounts);
        for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
            Logger.log(LogId.I_5202, entry.getKey(), entry.getValue());
        }

        Logger.log(LogId.I_5203, total);
        return total;
    }

    /** Reloads only filebase/API-backed item caches; runtime equipment state is preserved. */
    public void clearMasterDataCache() {
        loadedItems.clear();
        loadedSetEffects.clear();
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
            List<io.github.maaasu.astralRecord.feature.item.model.ItemModel> items =
                itemRepository.findAllByCategory(normalizedCategory);
            for (io.github.maaasu.astralRecord.feature.item.model.ItemModel item : items) {
                cacheItem(item);
            }
            int total = items.size();
            if (ItemCategory.CURRENCY.getApiValue().equalsIgnoreCase(normalizedCategory)) {
                total += cacheBuiltInItems(new HashMap<>());
            }
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
        return loadedItems.values().stream()
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

        return loadedItems.get(normalizedId);
    }

    private @Nullable ItemModel resolveBuiltinItem(@NotNull String normalizedId) {
        if (DEFAULT_CURRENCY_ITEM_ID.equals(normalizedId) || LEGACY_DEFAULT_CURRENCY_ITEM_ID.equals(normalizedId)) {
            return createGoldCurrencyItem(normalizedId);
        }
        if (ASTRALD_CURRENCY_ITEM_ID.equals(normalizedId)) {
            return createAstraldCurrencyItem();
        }
        return null;
    }

    private int cacheBuiltInItems(@NotNull Map<String, Integer> categoryCounts) {
        ItemModel gold = createGoldCurrencyItem(DEFAULT_CURRENCY_ITEM_ID);
        cacheItem(gold);
        categoryCounts.merge(gold.getCategory().toLowerCase(Locale.ROOT), 1, Integer::sum);
        ItemModel astrald = createAstraldCurrencyItem();
        cacheItem(astrald);
        categoryCounts.merge(astrald.getCategory().toLowerCase(Locale.ROOT), 1, Integer::sum);
        return 2;
    }

    private @NotNull ItemModel createGoldCurrencyItem(@NotNull String itemId) {
        return new ItemModel(
            1,
            itemId,
            ItemCategory.CURRENCY.getApiValue(),
            "ゴールド",
            "GOLD_NUGGET",
            "common",
            64,
            0,
            null,
            null,
            List.of("冒険や取引で使う基本通貨です。"),
            false,
            true,
            null,
            new ItemCurrency("gold", "default", null),
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
                loadedEquipmentInstances.put(normalize(instance.getEquipmentInstanceId()), instance);
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
        EquipmentInstance cached = loadedEquipmentInstances.get(normalizedId);
        if (cached != null) {
            return cached;
        }
        try {
            EquipmentInstance loaded = itemRepository.findEquipmentInstanceById(instanceId);
            if (loaded != null) {
                loadedEquipmentInstances.put(normalizedId, loaded);
            }
            return loaded;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, instanceId);
            return null;
        }
    }

    public @Nullable EquipmentInstance enhanceEquipmentInstance(
        @NotNull String instanceId,
        int targetLevel,
        @NotNull String updatedBy
    ) {
        try {
            EquipmentInstance instance = itemRepository.enhanceEquipmentInstance(instanceId, targetLevel, updatedBy);
            if (instance != null) {
                loadedEquipmentInstances.put(normalize(instance.getEquipmentInstanceId()), instance);
            }
            return instance;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, instanceId);
            return null;
        }
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
        EquipmentInstance current = findEquipmentInstanceById(instanceId);
        if (current == null) {
            return null;
        }
        int clampedValue = Math.max(0, Math.min(current.getDurabilityMax(), durabilityValue));
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
            current.getRunes(),
            current.getEnchantPools()
        );
        loadedEquipmentInstances.put(normalizedId, updated);
        dirtyEquipmentDurability.put(
            normalizedId,
            new PendingDurabilityUpdate(updated.getEquipmentInstanceId(), updated.getAccountId(), clampedValue, updatedBy)
        );
        return updated;
    }

    /**
     * 対象アカウントに未保存の装備耐久値変更があるかを判定します。
     *
     * @param accountId 対象アカウント ID
     * @return 未保存の耐久値変更がある場合は {@code true}
     */
    public boolean hasDirtyEquipmentDurability(@NotNull UUID accountId) {
        String targetAccountId = accountId.toString();
        return dirtyEquipmentDurability.values().stream()
            .anyMatch(update -> update.accountId().equalsIgnoreCase(targetAccountId));
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
        boolean allOk = true;
        for (Map.Entry<String, PendingDurabilityUpdate> entry : dirtyEquipmentDurability.entrySet()) {
            PendingDurabilityUpdate pending = entry.getValue();
            if (!pending.accountId().equalsIgnoreCase(targetAccountId)) {
                continue;
            }
            EquipmentInstance cached = loadedEquipmentInstances.get(entry.getKey());
            int durabilityValue = cached == null ? pending.durabilityValue() : cached.getDurabilityValue();
            try {
                EquipmentInstance persisted = itemRepository.updateEquipmentDurability(
                    pending.instanceId(),
                    durabilityValue,
                    pending.updatedBy()
                );
                if (persisted != null) {
                    loadedEquipmentInstances.put(entry.getKey(), persisted);
                    dirtyEquipmentDurability.remove(entry.getKey(), pending);
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
        dirtyEquipmentDurability.entrySet().removeIf(entry -> entry.getValue().accountId().equalsIgnoreCase(targetAccountId));
    }

    private record PendingDurabilityUpdate(
        @NotNull String instanceId,
        @NotNull String accountId,
        int durabilityValue,
        @NotNull String updatedBy
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
                loadedEquipmentInstances.remove(normalizedId);
            }
            return deleted;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, instanceId);
            return false;
        }
    }

    /**
     * ルーンインスタンスを API 経由で新規作成します。
     *
     * @param runeId    アイテムテンプレート ID
     * @param accountId 所有アカウント ID（UUID 文字列）
     * @param source    取得元（例: "command", "loot_drop"）
     * @param createdBy 作成者アカウント ID（UUID 文字列）
     * @return 作成されたルーンインスタンス。失敗時は null
     */
    public @Nullable RuneInstance createRuneInstance(
        @NotNull String runeId,
        @NotNull String accountId,
        @NotNull String source,
        @NotNull String createdBy
    ) {
        try {
            RuneInstance instance = itemRepository.createRuneInstance(runeId, accountId, source, createdBy);
            if (instance != null) {
                loadedRuneInstances.put(normalize(instance.getRuneInstanceId()), instance);
            }
            return instance;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, runeId);
            return null;
        }
    }

    public @Nullable RuneInstance findRuneInstanceById(@NotNull String instanceId) {
        String normalizedId = normalize(instanceId);
        if (normalizedId.isBlank()) {
            return null;
        }
        RuneInstance cached = loadedRuneInstances.get(normalizedId);
        if (cached != null) {
            return cached;
        }
        try {
            RuneInstance loaded = itemRepository.findRuneInstanceById(instanceId);
            if (loaded != null) {
                loadedRuneInstances.put(normalizedId, loaded);
            }
            return loaded;
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, instanceId);
            return null;
        }
    }

    /**
     * アイテムをキャッシュへ登録し、詳細情報を debug ログへ出力します。
     *
     * @param item 登録するアイテム
     */
    private void cacheItem(@NotNull ItemModel item) {
        loadedItems.put(normalize(item.getId()), item);
        Logger.log(LogId.D_5203, item);
    }

    private @NotNull String normalize(@NotNull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}



