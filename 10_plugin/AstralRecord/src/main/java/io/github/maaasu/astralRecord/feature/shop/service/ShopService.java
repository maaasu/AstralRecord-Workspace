package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.model.ShopPurchasePreview;
import io.github.maaasu.astralRecord.feature.shop.model.ShopRecipeCost;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public final class ShopService {
    private static final String PURCHASE_SOURCE = "shop";

    private final ShopRepository shopRepository;
    private final ShopRecipeRepository recipeRepository;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;
    private final Set<ShopEntry> reportedMissingRecipes = ConcurrentHashMap.newKeySet();
    private BiConsumer<AstPlayer, String> purchaseListener = (player, entryId) -> { };
    private BiConsumer<AstPlayer, String> purchaseSavedListener = (player, entryId) -> { };

    public ShopService(
        @NotNull ShopRepository shopRepository,
        @NotNull ShopRecipeRepository recipeRepository,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService,
        @NotNull CurrencyService currencyService
    ) {
        this.shopRepository = shopRepository;
        this.recipeRepository = recipeRepository;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.currencyService = currencyService;
    }

    public @NotNull List<ShopDefinition> findAll() {
        return shopRepository.findAll();
    }

    /**
     * 購入・交換成功時の通知先を設定します。
     *
     * @param purchaseListener 購入者とshop entry IDを受け取る通知先
     */
    public void setPurchaseListener(@NotNull BiConsumer<AstPlayer, String> purchaseListener) {
        this.purchaseListener = purchaseListener;
    }

    /**
     * インベントリ保存成功後の購入通知先を設定します。
     *
     * @param purchaseSavedListener 保存に成功した購入者とshop entry IDを受け取る通知先
     */
    public void setPurchaseSavedListener(@NotNull BiConsumer<AstPlayer, String> purchaseSavedListener) {
        this.purchaseSavedListener = purchaseSavedListener;
    }

    /**
     * ショップと SHOP レシピを先読みします。
     *
     * <p>起動時の非同期マスタロードで呼び出し、最初の GUI 操作にファイル I/O を持ち込みません。</p>
     *
     * @return 先読みしたショップ定義と SHOP レシピの合計件数
     */
    public int warmCaches() {
        CacheSnapshot snapshot = loadCacheSnapshot();
        replaceCacheSnapshot(snapshot);
        return snapshot.size();
    }

    /**
     * 現在のキャッシュを維持したまま、filebase から次のキャッシュを構築します。
     *
     * @return 公開前のショップキャッシュスナップショット
     */
    public @NotNull CacheSnapshot loadCacheSnapshot() {
        return new CacheSnapshot(
            shopRepository.loadSnapshot(),
            recipeRepository.loadSnapshot()
        );
    }

    /**
     * 構築済みのショップ・レシピを同じメインスレッド処理内で公開します。
     * 指定レシピがない商品は警告し、他の商品は引き続き販売できます。
     *
     * @param snapshot 公開するキャッシュスナップショット
     */
    public void replaceCacheSnapshot(@NotNull CacheSnapshot snapshot) {
        shopRepository.replaceCache(snapshot.shops());
        recipeRepository.replaceCache(snapshot.recipes());
        reportedMissingRecipes.clear();
        for (ShopDefinition shop : snapshot.shops()) {
            shop.entries().forEach(this::isRecipeAvailable);
        }
    }

    public @Nullable ShopDefinition findById(@NotNull String shopId) {
        return shopRepository.findById(shopId);
    }

    public @Nullable ShopDefinition findByIdOrName(@NotNull String value) {
        String normalized = normalizeShopLookup(value);
        if (normalized.isBlank()) {
            return null;
        }
        return findAll().stream()
            .filter(shop -> normalizeShopLookup(shop.id()).equals(normalized)
                || normalizeShopLookup(shop.name()).equals(normalized))
            .findFirst()
            .orElse(null);
    }

    /**
     * コマンドから開けるショップだけを ID または表示名で検索します。
     *
     * @param value ショップ ID または表示名
     * @return コマンド導線を許可するショップ。見つからない場合は {@code null}
     */
    public @Nullable ShopDefinition findCommandAccessibleByIdOrName(@NotNull String value) {
        ShopDefinition shop = findByIdOrName(value);
        return shop != null && shop.access().isCommandAccessible() ? shop : null;
    }

    public @Nullable ItemModel resolveItem(@NotNull ShopEntry entry) {
        ItemModel model = itemService.findLoadedById(entry.itemId());
        return model != null ? model : itemService.loadItem(entry.itemId(), entry.category());
    }

    /**
     * ショップ購入品の表示名を解決します。
     *
     * @param entry ショップ商品定義
     * @return アイテム表示名。解決できない場合は itemId
     */
    public @NotNull String resolveItemDisplayName(@NotNull ShopEntry entry) {
        return displayNameOrId(resolveItem(entry), entry.itemId());
    }

    /**
     * ショップ必要素材の表示名を解決します。
     *
     * @param cost 必要素材定義
     * @return アイテム表示名。解決できない場合は itemId
     */
    public @NotNull String resolveItemDisplayName(@NotNull ShopCostItem cost) {
        return displayNameOrId(resolveCostItem(cost), cost.itemId());
    }

    /**
     * 必要素材のマスタを解決します。
     * @param cost 必要素材
     * @return 解決済みマスタ。見つからない場合は null
     * @implNote キャッシュにない場合はマスタを読み込む。購入状態は変更しない
     */
    public @Nullable ItemModel resolveCostItem(@NotNull ShopCostItem cost) {
        ItemModel model = itemService.findLoadedById(cost.itemId());
        return model != null ? model : itemService.loadItem(cost.itemId(), cost.category());
    }

    /**
     * 指定レシピの存在・現在の対価・商品全量の収納可否から購入条件を計算します。
     * @param player 読み込み済みの購入者
     * @param entry 購入対象の商品
     * @param quantity 購入口数。1未満は1へ補正
     * @return 対価不足と支払い前の容量不足を含む購入可否。所持品の消費・付与は行わない
     * @implNote レシピは一度だけ解決し、存在判定と費用計算で共有する。未解決なら公開ごと・商品ごとに一度警告し、所持状態は変更しない
     */
    public @NotNull ShopPurchasePreview preview(
        @NotNull AstPlayer player,
        @NotNull ShopEntry entry,
        int quantity
    ) {
        int safeQuantity = Math.max(1, quantity);
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return new ShopPurchasePreview(safeQuantity, 0L, 0L, List.of(), List.of(), false, false);
        }
        ShopRecipeCost recipe = resolveRecipe(entry);
        if (!isRecipeAvailable(entry, recipe)) {
            return new ShopPurchasePreview(safeQuantity, 0L, 0L, List.of(), List.of(), false, false);
        }
        UUID accountId = player.getAccount().getUuid();
        long ownedGold = currencyService.getGoldAmount(accountId);
        long requiredGold = (long) resolveGoldCost(entry, recipe) * safeQuantity;
        List<ShopCostItem> requiredItems = resolveRequiredItems(entry, recipe).stream()
            .map(cost -> cost.multiplied(safeQuantity))
            .toList();
        boolean hasStorageRemoteAccess = inventoryService.hasStorageRemoteAccessToken(accountId);
        List<ShopCostItem> missingItems = new ArrayList<>();
        if (ownedGold < requiredGold) {
            missingItems.add(new ShopCostItem(ItemService.DEFAULT_CURRENCY_ITEM_ID, "currency", toIntAmount(requiredGold - ownedGold)));
        }
        for (ShopCostItem cost : requiredItems) {
            long owned = getOwnedCostAmount(accountId, cost, hasStorageRemoteAccess);
            if (owned < cost.amount()) {
                missingItems.add(new ShopCostItem(
                    cost.itemId(),
                    cost.category(),
                    toIntAmount(cost.amount() - owned)
                ));
            }
        }
        ItemModel model = resolveItem(entry);
        int amount = Math.max(1, entry.amount()) * safeQuantity;
        boolean inventoryFull = model != null
            && !inventoryService.canAddItemToNormalInventory(player, model, amount);
        return new ShopPurchasePreview(
            safeQuantity,
            requiredGold,
            ownedGold,
            requiredItems,
            missingItems,
            inventoryFull,
            model != null && missingItems.isEmpty() && !inventoryFull
        );
    }

    /**
     * 支払いと商品付与を完成スナップショットで確定します。
     * 返された future は SQL ACK 後にだけ {@code true} で完了します。
     */
    public @NotNull CompletableFuture<Boolean> purchase(
        @NotNull AstPlayer player,
        @NotNull ShopEntry entry,
        int quantity
    ) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return CompletableFuture.completedFuture(false);
        }
        ItemModel model = resolveItem(entry);
        if (model == null) {
            return CompletableFuture.completedFuture(false);
        }
        ShopPurchasePreview preview = preview(player, entry, quantity);
        if (!preview.canPurchase()) {
            return CompletableFuture.completedFuture(false);
        }
        UUID accountId = player.getAccount().getUuid();
        String playerName = player.getBukkit().getName();
        int amount = Math.max(1, entry.amount()) * preview.quantity();
        if (!inventoryService.canAddItemToNormalInventory(player, model, amount)) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> persistence = inventoryService.executeCriticalPlayerMutation(accountId, () -> {
            ShopPurchasePreview currentPreview = preview(player, entry, quantity);
            if (!currentPreview.canPurchase()
                || !inventoryService.canAddItemToNormalInventory(player, model, amount)) {
                throw new PurchaseRejectedException("purchase_precondition_changed");
            }

            InventoryService.InventoryStateSnapshot inventoryBefore = inventoryService.snapshotState(accountId);
            if (inventoryBefore == null) {
                throw new PurchaseRejectedException("inventory_state_unavailable");
            }
            Runnable equipmentRollback = itemService.captureEquipmentStateRollback(accountId);
            try {
                if (currentPreview.requiredGold() > 0L
                    && !inventoryService.consumeGold(accountId, currentPreview.requiredGold())) {
                    throw new PurchaseRejectedException("gold_consume_failed");
                }
                boolean hasStorageRemoteAccess = inventoryService.hasStorageRemoteAccessToken(accountId);
                for (ShopCostItem cost : currentPreview.requiredItems()) {
                    if (!consumeCost(accountId, cost, hasStorageRemoteAccess)) {
                        throw new PurchaseRejectedException("cost_consume_failed:" + cost.itemId());
                    }
                }
                int granted = inventoryService.addItemToNormalInventoryStateOnly(player, model, amount, PURCHASE_SOURCE);
                if (granted != amount) {
                    throw new PurchaseRejectedException("item_grant_failed:" + granted);
                }
                return new InventorySaveCoordinator.CriticalMutation<>(true, () ->
                    restorePurchase(inventoryBefore, equipmentRollback, playerName, entry, amount,
                        "snapshot_save_failed"));
            } catch (RuntimeException | Error failure) {
                restorePurchase(inventoryBefore, equipmentRollback, playerName, entry, amount,
                    failure.getMessage() == null ? "purchase_mutation_failed" : failure.getMessage());
                throw failure;
            }
        });
        return persistence.exceptionally(failure -> {
            Throwable cause = unwrapCompletionFailure(failure);
            if (cause instanceof PurchaseRejectedException) return false;
            throw new CompletionException(cause);
        });
    }

    /** SQL ACK 後に Bukkit main thread から購入結果を反映します。 */
    public void applyCommittedPurchase(@NotNull AstPlayer player, @NotNull ShopEntry entry) {
        ItemModel model = resolveItem(entry);
        if (model != null) {
            InventoryType type = inventoryService.resolveInventoryType(model);
            if (type != InventoryType.CURRENCY) {
                inventoryService.applyInventoryToGui(player, type);
            }
        }
        purchaseListener.accept(player, entry.id());
        purchaseSavedListener.accept(player, entry.id());
    }


    private void restorePurchase(
        @NotNull InventoryService.InventoryStateSnapshot snapshot,
        @NotNull Runnable equipmentRollback,
        @NotNull String playerName,
        @NotNull ShopEntry entry,
        int amount,
        @NotNull String reason
    ) {
        boolean restored = inventoryService.restoreState(snapshot);
        equipmentRollback.run();
        Logger.log(
            LogId.W_6300,
            playerName,
            entry.id(),
            entry.itemId(),
            amount,
            restored ? reason : reason + ":rollback_failed"
        );
    }

    private static final class PurchaseRejectedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private PurchaseRejectedException(@NotNull String detail) {
            super(detail);
        }
    }

    private static @NotNull Throwable unwrapCompletionFailure(@NotNull Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public int resolveGoldCost(@NotNull ShopEntry entry) {
        return resolveGoldCost(entry, resolveRecipe(entry));
    }

    /**
     * 解決済みの同じレシピから1口分のGold費用を計算します。
     * @param entry 商品定義
     * @param recipe 存在判定に使用したレシピ。未指定時は null
     * @return 直接費用とレシピ費用の合計
     */
    private int resolveGoldCost(@NotNull ShopEntry entry, @Nullable ShopRecipeCost recipe) {
        int cost = Math.max(0, entry.priceGold());
        if (recipe != null) {
            cost += Math.max(0, recipe.requiredCurrency());
        }
        return cost;
    }

    public @NotNull List<ShopCostItem> resolveRequiredItems(@NotNull ShopEntry entry) {
        return resolveRequiredItems(entry, resolveRecipe(entry));
    }

    /**
     * 解決済みの同じレシピから1口分の必要素材を合算します。
     * @param entry 商品定義
     * @param recipe 存在判定に使用したレシピ。未指定時は null
     * @return 直接素材とレシピ素材を同じカテゴリ・IDごとに合算した一覧
     */
    private @NotNull List<ShopCostItem> resolveRequiredItems(@NotNull ShopEntry entry, @Nullable ShopRecipeCost recipe) {
        Map<String, ShopCostItem> merged = new LinkedHashMap<>();
        mergeCosts(merged, entry.requiredItems());
        if (recipe != null) {
            mergeCosts(merged, recipe.ingredients());
        }
        return List.copyOf(merged.values());
    }

    private @Nullable ShopRecipeCost resolveRecipe(@NotNull ShopEntry entry) {
        if (entry.recipeId() == null || entry.recipeId().isBlank()) {
            return null;
        }
        return recipeRepository.findShopRecipeById(entry.recipeId());
    }

    /**
     * 商品に指定された SHOP レシピが解決できるか判定します。
     * @param entry 判定する商品
     * @return レシピ未指定、または指定レシピが存在する場合は true
     * @implNote 未解決の場合はマスタ公開ごと・商品ごとに一度だけ管理者向け警告を出力する
     */
    public boolean isRecipeAvailable(@NotNull ShopEntry entry) {
        return isRecipeAvailable(entry, resolveRecipe(entry));
    }

    /**
     * 解決済みレシピの有無を検証し、未解決商品は重複を抑制して警告します。
     * @param entry 商品定義
     * @param recipe 判定に使用する解決済みレシピ
     * @return 未指定・空白または解決可能なら true
     */
    private boolean isRecipeAvailable(@NotNull ShopEntry entry, @Nullable ShopRecipeCost recipe) {
        if (entry.recipeId() == null || entry.recipeId().isBlank() || recipe != null) {
            return true;
        }
        if (reportedMissingRecipes.add(entry)) {
            Logger.log(LogId.W_6301, entry.id(), entry.itemId(), entry.recipeId());
        }
        return false;
    }

    private void mergeCosts(@NotNull Map<String, ShopCostItem> target, @NotNull List<ShopCostItem> costs) {
        for (ShopCostItem cost : costs) {
            String key = cost.category().toLowerCase(java.util.Locale.ROOT) + ":" + cost.itemId().toLowerCase(java.util.Locale.ROOT);
            ShopCostItem existing = target.get(key);
            if (existing == null) {
                target.put(key, cost);
                continue;
            }
            target.put(key, new ShopCostItem(existing.itemId(), existing.category(), existing.amount() + cost.amount()));
        }
    }

    private @NotNull String displayNameOrId(@Nullable ItemModel model, @NotNull String fallbackItemId) {
        if (model == null || model.getName() == null || model.getName().isBlank()) {
            return fallbackItemId;
        }
        String displayName = ColorCodeUtil.stripColor(ColorCodeUtil.translateAlternateColorCodes(model.getName()));
        return displayName == null || displayName.isBlank() ? fallbackItemId : displayName;
    }

    private @NotNull String normalizeShopLookup(@NotNull String value) {
        return value
            .replaceAll("(?i)&[0-9a-fk-or]", "")
            .trim()
            .replaceAll("\\s+", " ")
            .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 素材の消費条件と同じ対象から、必要数に制限しない現在の利用可能数を返します。
     * @param accountId 閲覧者のアカウント
     * @param cost 表示する必要素材
     * @return トークン所持時はストレージも含む利用可能数
     */
    public long getOwnedCostAmount(@NotNull UUID accountId, @NotNull ShopCostItem cost) {
        return getOwnedCostAmount(accountId, cost, inventoryService.hasStorageRemoteAccessToken(accountId));
    }

    private long getOwnedCostAmount(
        @NotNull UUID accountId,
        @NotNull ShopCostItem cost,
        boolean hasStorageRemoteAccess
    ) {
        if (isCurrencyCost(cost)) {
            if (isGoldCurrencyId(cost.itemId())) {
                return currencyService.getGoldAmount(accountId);
            }
            return hasStorageRemoteAccess
                ? inventoryService.getSpendableCurrencyAmountIncludingStorage(accountId, cost.itemId())
                : inventoryService.getCurrencyAmount(accountId, cost.itemId());
        }
        return hasStorageRemoteAccess
            ? inventoryService.getSpendableNormalItemAmountIncludingStorage(accountId, cost.itemId())
            : inventoryService.getSpendableNormalItemAmount(accountId, cost.itemId());
    }

    private boolean consumeCost(
        @NotNull UUID accountId,
        @NotNull ShopCostItem cost,
        boolean hasStorageRemoteAccess
    ) {
        if (isCurrencyCost(cost)) {
            if (isGoldCurrencyId(cost.itemId())) {
                return inventoryService.consumeCurrency(accountId, cost.itemId(), cost.amount());
            }
            return hasStorageRemoteAccess
                ? inventoryService.consumeCurrencyIncludingStorage(accountId, cost.itemId(), cost.amount())
                : inventoryService.consumeCurrency(accountId, cost.itemId(), cost.amount());
        }
        return hasStorageRemoteAccess
            ? inventoryService.consumeNormalItemIncludingStorage(accountId, cost.itemId(), cost.amount())
            : inventoryService.consumeNormalItem(accountId, cost.itemId(), cost.amount());
    }

    private boolean isCurrencyCost(@NotNull ShopCostItem cost) {
        return "currency".equalsIgnoreCase(cost.category());
    }

    private boolean isGoldCurrencyId(@NotNull String itemId) {
        return ItemService.DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(itemId)
            || ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(itemId)
            || ItemService.LEGACY_PREVIOUS_DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(itemId)
            || GoldDenomination.GOLD == GoldDenomination.findByItemId(itemId);
    }

    private int toIntAmount(long amount) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, amount));
    }

    /** 公開前のショップ定義と SHOP レシピを保持します。 */
    public record CacheSnapshot(
        @NotNull List<ShopDefinition> shops,
        @NotNull Map<String, ShopRecipeCost> recipes
    ) {
        public CacheSnapshot {
            shops = List.copyOf(shops);
            recipes = Map.copyOf(recipes);
        }

        public int size() {
            return shops.size() + recipes.size();
        }
    }
}
