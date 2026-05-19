package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSummary;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * アイテム機能の最小サービス。
 * APIから取得したアイテムをメモリに保持し、一覧/詳細参照に使用します。
 */
public class ItemService {

    private final ItemRepository itemRepository;
    private final Map<String, ItemModel> loadedItems;

    public ItemService() {
        this.itemRepository = new ItemRepository();
        this.loadedItems = new ConcurrentHashMap<>();
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

        for (Map.Entry<String, Integer> entry : categoryCounts.entrySet()) {
            Logger.log(LogId.I_5202, entry.getKey(), entry.getValue());
        }

        Logger.log(LogId.I_5203, total);
        return total;
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
            Logger.log(LogId.I_5202, normalizedCategory, items.size());
            return items.size();
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

        ItemModel item = itemRepository.findById(itemId, category);
        if (item == null) {
            return null;
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
            return itemRepository.createEquipmentInstance(equipmentId, accountId, source, createdBy);
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, equipmentId);
            return null;
        }
    }

    public @Nullable EquipmentInstance findEquipmentInstanceById(@NotNull String instanceId) {
        try {
            return itemRepository.findEquipmentInstanceById(instanceId);
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, instanceId);
            return null;
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
            return itemRepository.createRuneInstance(runeId, accountId, source, createdBy);
        } catch (Exception e) {
            Logger.log(LogId.E_5202, e, runeId);
            return null;
        }
    }

    public @Nullable RuneInstance findRuneInstanceById(@NotNull String instanceId) {
        try {
            return itemRepository.findRuneInstanceById(instanceId);
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



