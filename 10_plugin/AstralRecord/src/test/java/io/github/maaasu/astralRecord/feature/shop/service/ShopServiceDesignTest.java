package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopAccess;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.model.ShopMode;
import io.github.maaasu.astralRecord.feature.shop.model.ShopPurchasePreview;
import io.github.maaasu.astralRecord.feature.shop.model.ShopRecipeCost;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopServiceDesignTest extends MockBukkitTestBase {

    @Test
    void previewCombinesEntryAndRecipeCostsBeforePurchaseCheck() {
        ShopHarness harness = shopHarness(new ShopRecipeCost(
            "starter_recipe",
            3,
            List.of(
                new ShopCostItem("herb", "material", 1),
                new ShopCostItem("crystal", "material", 4)
            )
        ));
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ShopEntry entry = shopEntry("potion", 1, 5, List.of(new ShopCostItem("herb", "material", 2)), "starter_recipe");
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(20L);
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "herb")).thenReturn(6L);
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "crystal")).thenReturn(7L);

        ShopPurchasePreview preview = harness.service.preview(player, entry, 2);

        assertEquals(2, preview.quantity());
        assertEquals(16L, preview.requiredGold());
        assertEquals(20L, preview.ownedGold());
        assertEquals(2, preview.requiredItems().size());
        assertEquals(6, preview.requiredItems().stream().filter(cost -> cost.itemId().equals("herb")).findFirst().orElseThrow().amount());
        assertEquals(8, preview.requiredItems().stream().filter(cost -> cost.itemId().equals("crystal")).findFirst().orElseThrow().amount());
        assertFalse(preview.canPurchase());
        assertEquals("crystal", preview.missingItems().get(0).itemId());
        assertEquals(1, preview.missingItems().get(0).amount());
    }

    @Test
    void purchasePaysGoldAndMaterialsBeforeGrantingItemsThenRefreshesInventory() {
        ShopHarness harness = shopHarness(new ShopRecipeCost(
            "starter_recipe",
            1,
            List.of(new ShopCostItem("herb", "material", 2))
        ));
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ItemModel potion = DesignTestFixtures.item("potion", ItemCategory.CONSUMABLE, 16);
        ShopEntry entry = shopEntry("potion", 2, 4, List.of(), "starter_recipe");
        when(harness.itemService.findLoadedById("potion")).thenReturn(potion);
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(20L);
        when(harness.inventoryService.getNormalItemAmount(player.getAccount().getUuid(), "herb")).thenReturn(6L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, potion, 6)).thenReturn(true);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(snapshot(player));
        when(harness.inventoryService.consumeGold(player.getAccount().getUuid(), 15L)).thenReturn(true);
        when(harness.inventoryService.consumeNormalItem(player.getAccount().getUuid(), "herb", 6)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, potion, 6, "shop")).thenReturn(6);
        when(harness.inventoryService.resolveInventoryType(potion)).thenReturn(InventoryType.BAG);

        boolean purchased = harness.service.purchase(player, entry, 3);

        assertTrue(purchased);
        InOrder order = inOrder(harness.inventoryService);
        order.verify(harness.inventoryService).canAddItemToNormalInventory(player, potion, 6);
        order.verify(harness.inventoryService).consumeGold(player.getAccount().getUuid(), 15L);
        order.verify(harness.inventoryService).consumeNormalItem(player.getAccount().getUuid(), "herb", 6);
        order.verify(harness.inventoryService).addItemToNormalInventory(player, potion, 6, "shop");
        order.verify(harness.inventoryService).applyInventoryToGui(player, InventoryType.BAG);
        order.verify(harness.inventoryService).saveNow(player.getAccount().getUuid());
    }

    @Test
    void purchaseDoesNotGrantItemsWhenPaymentFails() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ItemModel potion = DesignTestFixtures.item("potion", ItemCategory.CONSUMABLE, 16);
        ShopEntry entry = shopEntry("potion", 1, 4, List.of(), null);
        when(harness.itemService.findLoadedById("potion")).thenReturn(potion);
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(10L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, potion, 1)).thenReturn(true);
        InventoryService.InventoryStateSnapshot snapshot = snapshot(player);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(snapshot);
        when(harness.inventoryService.consumeGold(player.getAccount().getUuid(), 4L)).thenReturn(false);
        when(harness.inventoryService.restoreState(snapshot)).thenReturn(true);

        boolean purchased;
        try (MockedStatic<AstralRecord> ignored = mockPluginLogger()) {
            purchased = harness.service.purchase(player, entry, 1);
        }

        assertFalse(purchased);
        verify(harness.inventoryService, never()).addItemToNormalInventory(player, potion, 1, "shop");
        verify(harness.inventoryService, never()).applyInventoryToGui(player, InventoryType.BAG);
        verify(harness.inventoryService, never()).saveNow(player.getAccount().getUuid());
        verify(harness.inventoryService).restoreState(snapshot);
    }

    @Test
    void purchaseRestoresPaymentWhenItemGrantIsPartial() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ItemModel potion = DesignTestFixtures.item("potion", ItemCategory.CONSUMABLE, 16);
        ShopEntry entry = shopEntry("potion", 2, 4, List.of(), null);
        InventoryService.InventoryStateSnapshot snapshot = snapshot(player);
        when(harness.itemService.findLoadedById("potion")).thenReturn(potion);
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(10L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, potion, 2)).thenReturn(true);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(snapshot);
        when(harness.inventoryService.consumeGold(player.getAccount().getUuid(), 4L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, potion, 2, "shop")).thenReturn(1);
        when(harness.inventoryService.restoreState(snapshot)).thenReturn(true);

        try (MockedStatic<AstralRecord> ignored = mockPluginLogger()) {
            assertFalse(harness.service.purchase(player, entry, 1));
        }

        verify(harness.inventoryService).restoreState(snapshot);
        verify(harness.inventoryService, never()).saveNow(player.getAccount().getUuid());
    }

    @Test
    void previewChecksGoldCurrencyCostAgainstTotalGoldBalance() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ShopEntry entry = new ShopEntry(
            "gold_to_coin",
            "gold_coin",
            "currency",
            1,
            1,
            null,
            null,
            null,
            0,
            List.of(new ShopCostItem("gold", "currency", 10)),
            null
        );
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(25L);

        ShopPurchasePreview preview = harness.service.preview(player, entry, 2);

        assertTrue(preview.canPurchase());
        assertEquals(20, preview.requiredItems().get(0).amount());
        verify(harness.currencyService, never()).getCurrencyAmount(player.getAccount().getUuid(), "gold");
        verify(harness.inventoryService, never()).getNormalItemAmount(player.getAccount().getUuid(), "gold");
    }

    @Test
    void previewChecksNonGoldCurrencyCostAgainstExactCurrencyBalance() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ShopEntry entry = new ShopEntry(
            "token_to_coin",
            "gold_coin",
            "currency",
            1,
            1,
            null,
            null,
            null,
            0,
            List.of(new ShopCostItem("silver_token", "currency", 10)),
            null
        );
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(1_000L);
        when(harness.currencyService.getCurrencyAmount(player.getAccount().getUuid(), "silver_token")).thenReturn(19L);

        ShopPurchasePreview preview = harness.service.preview(player, entry, 2);

        assertFalse(preview.canPurchase());
        assertEquals(20, preview.requiredItems().get(0).amount());
        assertEquals("silver_token", preview.missingItems().get(0).itemId());
        assertEquals(1, preview.missingItems().get(0).amount());
        verify(harness.currencyService).getCurrencyAmount(player.getAccount().getUuid(), "silver_token");
        verify(harness.inventoryService, never()).getNormalItemAmount(player.getAccount().getUuid(), "silver_token");
    }

    @Test
    void purchaseConsumesCurrencyCostAndGrantsCurrencyAtomically() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ItemModel goldCoin = DesignTestFixtures.item("gold_coin", ItemCategory.CURRENCY, 64);
        ShopEntry entry = new ShopEntry(
            "gold_to_coin",
            "gold_coin",
            "currency",
            1,
            1,
            null,
            null,
            null,
            0,
            List.of(new ShopCostItem("gold", "currency", 10)),
            null
        );
        InventoryService.InventoryStateSnapshot snapshot = snapshot(player);
        when(harness.itemService.findLoadedById("gold_coin")).thenReturn(goldCoin);
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(10L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, goldCoin, 1)).thenReturn(true);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(snapshot);
        when(harness.inventoryService.consumeCurrency(player.getAccount().getUuid(), "gold", 10L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, goldCoin, 1, "shop")).thenReturn(1);
        when(harness.inventoryService.resolveInventoryType(goldCoin)).thenReturn(InventoryType.CURRENCY);

        assertTrue(harness.service.purchase(player, entry, 1));

        InOrder order = inOrder(harness.inventoryService);
        order.verify(harness.inventoryService).consumeCurrency(player.getAccount().getUuid(), "gold", 10L);
        order.verify(harness.inventoryService).addItemToNormalInventory(player, goldCoin, 1, "shop");
        order.verify(harness.inventoryService).saveNow(player.getAccount().getUuid());
        verify(harness.inventoryService, never()).applyInventoryToGui(player, InventoryType.CURRENCY);
    }

    @Test
    void commandLookupExcludesNpcOnlyShop() {
        ShopHarness harness = shopHarness(null);
        ShopDefinition exchange = new ShopDefinition(
            "currency_exchange",
            "ゴールド両替所",
            ShopMode.EXCHANGE,
            ShopAccess.NPC_ONLY,
            List.of()
        );
        when(harness.shopRepository.findAll()).thenReturn(List.of(exchange));

        assertEquals(exchange, harness.service.findByIdOrName("currency_exchange"));
        assertNull(harness.service.findCommandAccessibleByIdOrName("currency_exchange"));
    }

    private ShopEntry shopEntry(
        String itemId,
        int amount,
        int priceGold,
        List<ShopCostItem> requiredItems,
        String recipeId
    ) {
        return new ShopEntry(
            itemId,
            itemId,
            "material",
            amount,
            1,
            null,
            null,
            null,
            priceGold,
            requiredItems,
            recipeId
        );
    }

    private ShopHarness shopHarness(ShopRecipeCost recipe) {
        ShopRepository shopRepository = mock(ShopRepository.class);
        ShopRecipeRepository recipeRepository = mock(ShopRecipeRepository.class);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        CurrencyService currencyService = mock(CurrencyService.class);
        if (recipe != null) {
            when(recipeRepository.findShopRecipeById(recipe.recipeId())).thenReturn(recipe);
        }
        ShopService service = new ShopService(
            shopRepository,
            recipeRepository,
            itemService,
            inventoryService,
            currencyService
        );
        return new ShopHarness(shopRepository, itemService, inventoryService, currencyService, service);
    }

    private InventoryService.InventoryStateSnapshot snapshot(AstPlayer player) {
        return new InventoryService.InventoryStateSnapshot(
            player.getAccount().getUuid(),
            Map.of(),
            InventoryType.BAG,
            false
        );
    }

    private MockedStatic<AstralRecord> mockPluginLogger() {
        MockedStatic<AstralRecord> mocked = Mockito.mockStatic(AstralRecord.class);
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        mocked.when(AstralRecord::getInstance).thenReturn(plugin);
        return mocked;
    }

    private record ShopHarness(
        ShopRepository shopRepository,
        ItemService itemService,
        InventoryService inventoryService,
        CurrencyService currencyService,
        ShopService service
    ) {
    }
}
