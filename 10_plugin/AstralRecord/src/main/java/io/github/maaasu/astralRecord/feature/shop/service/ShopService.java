package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

public final class ShopService {
    private static final String PURCHASE_SOURCE = "shop";

    private final ShopRepository shopRepository;
    private final ShopRecipeRepository recipeRepository;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;
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
     *
     * @param snapshot 公開するキャッシュスナップショット
     */
    public void replaceCacheSnapshot(@NotNull CacheSnapshot snapshot) {
        shopRepository.replaceCache(snapshot.shops());
        recipeRepository.replaceCache(snapshot.recipes());
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
        ItemModel model = itemService.findLoadedById(cost.itemId());
        if (model == null) {
            model = itemService.loadItem(cost.itemId(), cost.category());
        }
        return displayNameOrId(model, cost.itemId());
    }

    public @NotNull ShopPurchasePreview preview(
        @NotNull AstPlayer player,
        @NotNull ShopEntry entry,
        int quantity
    ) {
        int safeQuantity = Math.max(1, quantity);
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return new ShopPurchasePreview(safeQuantity, 0L, 0L, List.of(), List.of(), false);
        }
        UUID accountId = player.getAccount().getUuid();
        long ownedGold = currencyService.getGoldAmount(accountId);
        long requiredGold = (long) resolveGoldCost(entry) * safeQuantity;
        List<ShopCostItem> requiredItems = resolveRequiredItems(entry).stream()
            .map(cost -> cost.multiplied(safeQuantity))
            .toList();
        List<ShopCostItem> missingItems = new ArrayList<>();
        if (ownedGold < requiredGold) {
            missingItems.add(new ShopCostItem(ItemService.DEFAULT_CURRENCY_ITEM_ID, "currency", (int) (requiredGold - ownedGold)));
        }
        for (ShopCostItem cost : requiredItems) {
            long owned = getOwnedCostAmount(accountId, cost);
            if (owned < cost.amount()) {
                missingItems.add(new ShopCostItem(
                    cost.itemId(),
                    cost.category(),
                    toIntAmount(cost.amount() - owned)
                ));
            }
        }
        return new ShopPurchasePreview(
            safeQuantity,
            requiredGold,
            ownedGold,
            requiredItems,
            missingItems,
            missingItems.isEmpty()
        );
    }

    public boolean purchase(@NotNull AstPlayer player, @NotNull ShopEntry entry, int quantity) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return false;
        }
        ItemModel model = resolveItem(entry);
        if (model == null) {
            return false;
        }
        ShopPurchasePreview preview = preview(player, entry, quantity);
        if (!preview.canPurchase()) {
            return false;
        }
        UUID accountId = player.getAccount().getUuid();
        int amount = Math.max(1, entry.amount()) * preview.quantity();
        if (!inventoryService.canAddItemToNormalInventory(player, model, amount)) {
            return false;
        }
        InventoryService.InventoryStateSnapshot snapshot = inventoryService.snapshotState(accountId);
        if (snapshot == null) {
            return false;
        }
        if (preview.requiredGold() > 0L && !inventoryService.consumeGold(accountId, preview.requiredGold())) {
            restorePurchase(snapshot, player, entry, amount, "gold_consume_failed");
            return false;
        }
        for (ShopCostItem cost : preview.requiredItems()) {
            if (!consumeCost(accountId, cost)) {
                restorePurchase(snapshot, player, entry, amount, "cost_consume_failed:" + cost.itemId());
                return false;
            }
        }
        int granted = inventoryService.addItemToNormalInventory(player, model, amount, PURCHASE_SOURCE);
        if (granted != amount) {
            restorePurchase(snapshot, player, entry, amount, "item_grant_failed:" + granted);
            return false;
        }
        InventoryType type = inventoryService.resolveInventoryType(model);
        if (type != InventoryType.CURRENCY) {
            inventoryService.applyInventoryToGui(player, type);
        }
        CompletableFuture<Boolean> saveFuture = inventoryService.saveNow(accountId);
        purchaseListener.accept(player, entry.id());
        if (saveFuture != null) {
            saveFuture.thenAccept(saved -> {
                if (Boolean.TRUE.equals(saved)) {
                    purchaseSavedListener.accept(player, entry.id());
                }
            });
        }
        return true;
    }

    private void restorePurchase(
        @NotNull InventoryService.InventoryStateSnapshot snapshot,
        @NotNull AstPlayer player,
        @NotNull ShopEntry entry,
        int amount,
        @NotNull String reason
    ) {
        boolean restored = inventoryService.restoreState(snapshot);
        Logger.log(
            LogId.W_6300,
            player.getBukkit().getName(),
            entry.id(),
            entry.itemId(),
            amount,
            restored ? reason : reason + ":rollback_failed"
        );
    }

    public int resolveGoldCost(@NotNull ShopEntry entry) {
        int cost = Math.max(0, entry.priceGold());
        ShopRecipeCost recipe = resolveRecipe(entry);
        if (recipe != null) {
            cost += Math.max(0, recipe.requiredCurrency());
        }
        return cost;
    }

    public @NotNull List<ShopCostItem> resolveRequiredItems(@NotNull ShopEntry entry) {
        Map<String, ShopCostItem> merged = new LinkedHashMap<>();
        mergeCosts(merged, entry.requiredItems());
        ShopRecipeCost recipe = resolveRecipe(entry);
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

    private long getOwnedCostAmount(@NotNull UUID accountId, @NotNull ShopCostItem cost) {
        if (isCurrencyCost(cost)) {
            if (ItemService.DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(cost.itemId())) {
                return currencyService.getGoldAmount(accountId);
            }
            return inventoryService.getSpendableCurrencyAmountIncludingStorage(accountId, cost.itemId());
        }
        return inventoryService.getSpendableNormalItemAmountIncludingStorage(accountId, cost.itemId());
    }

    private boolean consumeCost(@NotNull UUID accountId, @NotNull ShopCostItem cost) {
        if (isCurrencyCost(cost)) {
            if (ItemService.DEFAULT_CURRENCY_ITEM_ID.equalsIgnoreCase(cost.itemId())) {
                return inventoryService.consumeCurrency(accountId, cost.itemId(), cost.amount());
            }
            return inventoryService.consumeCurrencyIncludingStorage(accountId, cost.itemId(), cost.amount());
        }
        return inventoryService.consumeNormalItemIncludingStorage(accountId, cost.itemId(), cost.amount());
    }

    private boolean isCurrencyCost(@NotNull ShopCostItem cost) {
        return "currency".equalsIgnoreCase(cost.category());
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
