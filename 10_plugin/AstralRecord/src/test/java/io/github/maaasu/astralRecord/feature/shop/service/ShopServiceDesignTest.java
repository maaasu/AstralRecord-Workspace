package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
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
import io.github.maaasu.astralRecord.feature.shop.model.ShopSpecialPurchaseState;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import io.github.maaasu.astralRecord.feature.shop.service.ShopSpecialPurchaseHandler;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopServiceDesignTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入 preview
     * 検証契約: directとrecipeのgold・同一item costを口数込みで合算し、所持不足量をpreviewへ出す。
     */
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
        when(harness.inventoryService.hasStorageRemoteAccessToken(player.getAccount().getUuid())).thenReturn(true);
        when(harness.inventoryService.getSpendableNormalItemAmountIncludingStorage(
            player.getAccount().getUuid(), "herb")).thenReturn(6L);
        when(harness.inventoryService.getSpendableNormalItemAmountIncludingStorage(
            player.getAccount().getUuid(), "crystal")).thenReturn(7L);

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入
     * 検証契約: capacity確認後にgold・素材を消費し、exact商品付与、BAG GUI反映、即時保存の順で完了する。
     */
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
        when(harness.inventoryService.hasStorageRemoteAccessToken(player.getAccount().getUuid())).thenReturn(true);
        when(harness.inventoryService.getSpendableNormalItemAmountIncludingStorage(
            player.getAccount().getUuid(), "herb")).thenReturn(6L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, potion, 6)).thenReturn(true);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(snapshot(player));
        when(harness.inventoryService.consumeGold(player.getAccount().getUuid(), 15L)).thenReturn(true);
        when(harness.inventoryService.consumeNormalItemIncludingStorage(
            player.getAccount().getUuid(), "herb", 6)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, potion, 6, "shop")).thenReturn(6);
        when(harness.inventoryService.resolveInventoryType(potion)).thenReturn(InventoryType.BAG);

        boolean purchased = harness.service.purchase(player, entry, 3);

        assertTrue(purchased);
        InOrder order = inOrder(harness.inventoryService);
        order.verify(harness.inventoryService).canAddItemToNormalInventory(player, potion, 6);
        order.verify(harness.inventoryService).consumeGold(player.getAccount().getUuid(), 15L);
        order.verify(harness.inventoryService).consumeNormalItemIncludingStorage(
            player.getAccount().getUuid(), "herb", 6);
        order.verify(harness.inventoryService).addItemToNormalInventory(player, potion, 6, "shop");
        order.verify(harness.inventoryService).applyInventoryToGui(player, InventoryType.BAG);
        order.verify(harness.inventoryService).saveNow(player.getAccount().getUuid());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入
     * 検証契約: 購入通知は即時に行い、保存成功通知はsaveNow完了後だけ行う。
     */
    @Test
    void purchaseNotifiesImmediatelyAndNotifiesSavedListenerAfterSuccessfulSave() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ItemModel potion = DesignTestFixtures.item("potion", ItemCategory.CONSUMABLE, 16);
        ShopEntry entry = shopEntry("potion", 1, 4, List.of(), null);
        CompletableFuture<Boolean> saveFuture = new CompletableFuture<>();
        List<String> events = new java.util.ArrayList<>();
        when(harness.itemService.findLoadedById("potion")).thenReturn(potion);
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(10L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, potion, 1)).thenReturn(true);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(snapshot(player));
        when(harness.inventoryService.consumeGold(player.getAccount().getUuid(), 4L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, potion, 1, "shop")).thenReturn(1);
        when(harness.inventoryService.resolveInventoryType(potion)).thenReturn(InventoryType.BAG);
        when(harness.inventoryService.saveNow(player.getAccount().getUuid())).thenReturn(saveFuture);
        harness.service.setPurchaseListener((ignoredPlayer, ignoredEntryId) -> events.add("immediate"));
        harness.service.setPurchaseSavedListener((ignoredPlayer, ignoredEntryId) -> events.add("saved"));

        assertTrue(harness.service.purchase(player, entry, 1));
        assertEquals(List.of("immediate"), events);

        saveFuture.complete(true);
        assertEquals(List.of("immediate", "saved"), events);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_4-統合フロー.md
     * 章・見出し: # 20_4-統合フロー > ## 3. Preview・購入
     * 検証契約: 支払い失敗時はsnapshotを復元し、商品付与・GUI反映・保存を行わない。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_4-統合フロー.md
     * 章・見出し: # 20_4-統合フロー > ## 3. Preview・購入 > ### 処理要点
     * 検証契約: 所持品容量不足時は支払い・商品付与・保存を行わず、購入を拒否する。
     */
    @Test
    void purchaseDoesNotConsumeWhenInventoryIsFull() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ItemModel potion = DesignTestFixtures.item("potion", ItemCategory.CONSUMABLE, 16);
        ShopEntry entry = shopEntry("potion", 1, 4, List.of(), null);
        when(harness.itemService.findLoadedById("potion")).thenReturn(potion);
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(10L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, potion, 1)).thenReturn(false);

        assertFalse(harness.service.purchase(player, entry, 1));

        verify(harness.inventoryService, never()).snapshotState(player.getAccount().getUuid());
        verify(harness.inventoryService, never()).consumeGold(player.getAccount().getUuid(), 4L);
        verify(harness.inventoryService, never()).addItemToNormalInventory(player, potion, 1, "shop");
        verify(harness.inventoryService, never()).saveNow(player.getAccount().getUuid());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入
     * 検証契約: STORAGEを含む素材決済後に商品付与数が要求量未満なら支払前snapshotへ復元し保存しない。
     */
    @Test
    void purchaseRestoresPaymentWhenItemGrantIsPartial() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ItemModel potion = DesignTestFixtures.item("potion", ItemCategory.CONSUMABLE, 16);
        ShopEntry entry = shopEntry(
            "potion",
            2,
            0,
            List.of(new ShopCostItem("storage_material", "material", 4)),
            null
        );
        InventoryService.InventoryStateSnapshot snapshot = snapshot(player);
        when(harness.itemService.findLoadedById("potion")).thenReturn(potion);
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(10L);
        when(harness.inventoryService.hasStorageRemoteAccessToken(player.getAccount().getUuid())).thenReturn(true);
        when(harness.inventoryService.getSpendableNormalItemAmountIncludingStorage(
            player.getAccount().getUuid(), "storage_material")).thenReturn(4L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, potion, 2)).thenReturn(true);
        when(harness.inventoryService.snapshotState(player.getAccount().getUuid())).thenReturn(snapshot);
        when(harness.inventoryService.consumeNormalItemIncludingStorage(
            player.getAccount().getUuid(), "storage_material", 4L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, potion, 2, "shop")).thenReturn(1);
        when(harness.inventoryService.restoreState(snapshot)).thenReturn(true);

        try (MockedStatic<AstralRecord> ignored = mockPluginLogger()) {
            assertFalse(harness.service.purchase(player, entry, 1));
        }

        verify(harness.inventoryService).consumeNormalItemIncludingStorage(
            player.getAccount().getUuid(), "storage_material", 4L);
        verify(harness.inventoryService).restoreState(snapshot);
        verify(harness.inventoryService, never()).saveNow(player.getAccount().getUuid());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入 preview
     * 検証契約: priceGoldはCURRENCY内の額面換算残高だけを使用し、STORAGE内のgold itemを合算しない。
     */
    @Test
    void previewDoesNotUseStorageItemsForPriceGold() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ShopEntry entry = shopEntry("potion", 1, 10, List.of(), null);
        when(harness.currencyService.getGoldAmount(player.getAccount().getUuid())).thenReturn(9L);
        when(harness.inventoryService.getSpendableCurrencyAmountIncludingStorage(
            player.getAccount().getUuid(), "gold")).thenReturn(Long.MAX_VALUE);

        ShopPurchasePreview preview = harness.service.preview(player, entry, 1);

        assertFalse(preview.canPurchase());
        assertEquals(1, preview.missingItems().size());
        verify(harness.inventoryService, never()).getSpendableCurrencyAmountIncludingStorage(
            player.getAccount().getUuid(), "gold");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入 preview
     * 検証契約: currencyカテゴリのgold costは単一entryでなく全額面換算残高に対して判定する。
     */
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
        verify(harness.inventoryService, never()).getSpendableCurrencyAmountIncludingStorage(
            player.getAccount().getUuid(), "gold");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入 preview
     * 検証契約: gold以外のcurrency costはCURRENCYとSTORAGEの同一currency IDを合算して不足量を判定する。
     */
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
        when(harness.inventoryService.hasStorageRemoteAccessToken(player.getAccount().getUuid())).thenReturn(true);
        when(harness.inventoryService.getSpendableCurrencyAmountIncludingStorage(
            player.getAccount().getUuid(), "silver_token")).thenReturn(19L);

        ShopPurchasePreview preview = harness.service.preview(player, entry, 2);

        assertFalse(preview.canPurchase());
        assertEquals(20, preview.requiredItems().get(0).amount());
        assertEquals("silver_token", preview.missingItems().get(0).itemId());
        assertEquals(1, preview.missingItems().get(0).amount());
        verify(harness.inventoryService).getSpendableCurrencyAmountIncludingStorage(
            player.getAccount().getUuid(), "silver_token");
        verify(harness.inventoryService, never()).getNormalItemAmount(player.getAccount().getUuid(), "silver_token");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入 preview
     * 検証契約: ストレージ遠隔アクセストークン未所持時は、STORAGEの必要素材を購入可能数量へ合算しない。
     */
    @Test
    void previewDoesNotUseStorageItemsWithoutRemoteAccessToken() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ShopEntry entry = shopEntry(
            "potion",
            1,
            0,
            List.of(new ShopCostItem("storage_material", "material", 4)),
            null
        );
        UUID accountId = player.getAccount().getUuid();
        when(harness.currencyService.getGoldAmount(accountId)).thenReturn(100L);
        when(harness.inventoryService.hasStorageRemoteAccessToken(accountId)).thenReturn(false);
        when(harness.inventoryService.getSpendableNormalItemAmount(accountId, "storage_material"))
            .thenReturn(0L);
        when(harness.inventoryService.getSpendableNormalItemAmountIncludingStorage(accountId, "storage_material"))
            .thenReturn(4L);

        ShopPurchasePreview preview = harness.service.preview(player, entry, 1);

        assertFalse(preview.canPurchase());
        assertEquals(1, preview.missingItems().size());
        assertEquals(4, preview.missingItems().getFirst().amount());
        verify(harness.inventoryService).getSpendableNormalItemAmount(accountId, "storage_material");
        verify(harness.inventoryService, never()).getSpendableNormalItemAmountIncludingStorage(
            accountId, "storage_material");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入
     * 検証契約: ストレージ遠隔アクセストークン未所持時は、ショップ必要素材を通常インベントリだけから消費する。
     */
    @Test
    void purchaseConsumesOnlyOwnedInventoryWithoutRemoteAccessToken() {
        ShopHarness harness = shopHarness(null);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ItemModel potion = DesignTestFixtures.item("potion", ItemCategory.CONSUMABLE, 16);
        ShopEntry entry = shopEntry(
            "potion",
            1,
            0,
            List.of(new ShopCostItem("material", "material", 2)),
            null
        );
        UUID accountId = player.getAccount().getUuid();
        when(harness.itemService.findLoadedById("potion")).thenReturn(potion);
        when(harness.currencyService.getGoldAmount(accountId)).thenReturn(0L);
        when(harness.inventoryService.hasStorageRemoteAccessToken(accountId)).thenReturn(false);
        when(harness.inventoryService.getSpendableNormalItemAmount(accountId, "material")).thenReturn(2L);
        when(harness.inventoryService.canAddItemToNormalInventory(player, potion, 1)).thenReturn(true);
        when(harness.inventoryService.snapshotState(accountId)).thenReturn(snapshot(player));
        when(harness.inventoryService.consumeNormalItem(accountId, "material", 2L)).thenReturn(true);
        when(harness.inventoryService.addItemToNormalInventory(player, potion, 1, "shop")).thenReturn(1);
        when(harness.inventoryService.resolveInventoryType(potion)).thenReturn(InventoryType.BAG);

        assertTrue(harness.service.purchase(player, entry, 1));

        verify(harness.inventoryService).consumeNormalItem(accountId, "material", 2L);
        verify(harness.inventoryService, never()).consumeNormalItemIncludingStorage(
            accountId, "material", 2L);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入
     * 検証契約: currency cost消費とcurrency商品付与を同じsnapshot境界で行い、成功時は保存するがBAG GUIを更新しない。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## Shop 検索
     * 検証契約: 一般検索ではNPC_ONLYを解決できるがcommand用検索からは除外する。
     */
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

    private InventoryEntryModel inventoryEntry(UUID entryId, UUID inventoryId, String itemId) {
        InventoryEntryModel entry = mock(InventoryEntryModel.class);
        when(entry.getInventoryEntryId()).thenReturn(entryId);
        when(entry.getInventoryId()).thenReturn(inventoryId);
        when(entry.getItemId()).thenReturn(itemId);
        when(entry.getQuantity()).thenReturn(1L);
        when(entry.isDeleted()).thenReturn(false);
        return entry;
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
