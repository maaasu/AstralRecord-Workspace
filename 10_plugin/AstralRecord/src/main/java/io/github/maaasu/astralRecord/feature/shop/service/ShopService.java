package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.model.ShopPurchasePreview;
import io.github.maaasu.astralRecord.feature.shop.model.ShopRecipeCost;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ShopService {
    private static final String PURCHASE_SOURCE = "shop";

    private final ShopRepository shopRepository;
    private final ShopRecipeRepository recipeRepository;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final CurrencyService currencyService;

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

    public @Nullable ItemModel resolveItem(@NotNull ShopEntry entry) {
        ItemModel model = itemService.findLoadedById(entry.itemId());
        return model != null ? model : itemService.loadItem(entry.itemId(), entry.category());
    }

    public @NotNull ShopPurchasePreview preview(
        @NotNull AstPlayer player,
        @NotNull ShopEntry entry,
        int quantity
    ) {
        int safeQuantity = Math.max(1, quantity);
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
            long owned = inventoryService.getNormalItemAmount(accountId, cost.itemId());
            if (owned < cost.amount()) {
                missingItems.add(new ShopCostItem(cost.itemId(), cost.category(), (int) (cost.amount() - owned)));
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
        if (!inventoryService.consumeGold(accountId, preview.requiredGold())) {
            return false;
        }
        for (ShopCostItem cost : preview.requiredItems()) {
            if (!inventoryService.consumeNormalItem(accountId, cost.itemId(), cost.amount())) {
                return false;
            }
        }
        int granted = inventoryService.addItemToNormalInventory(player, model, amount, PURCHASE_SOURCE);
        if (granted <= 0) {
            return false;
        }
        InventoryType type = inventoryService.resolveInventoryType(model);
        if (type != InventoryType.CURRENCY) {
            inventoryService.applyInventoryToGui(player, type);
        }
        inventoryService.saveNow(accountId);
        return true;
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

    private @NotNull String normalizeShopLookup(@NotNull String value) {
        return value
            .replaceAll("(?i)&[0-9a-fk-or]", "")
            .trim()
            .replaceAll("\\s+", " ")
            .toLowerCase(java.util.Locale.ROOT);
    }
}
