package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopAccess;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.model.ShopMode;
import io.github.maaasu.astralRecord.feature.shop.model.ShopRecipeCost;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopServiceRecipeValidationTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入 preview
     * 検証契約: 未解決レシピは対価を省いた購入を許可せず、支払い・付与・保存へ進まない。
     */
    @Test
    void missingRecipeRejectsPreviewAndPurchaseBeforeInventoryMutation() {
        Fixture f = new Fixture();
        ShopEntry entry = entry("missing");
        when(f.items.findLoadedById(entry.itemId())).thenReturn(mock(ItemModel.class));
        try (var logs = mockStatic(Logger.class)) {
            var preview = f.service.preview(f.player, entry, 3);
            assertFalse(preview.canPurchase());
            assertFalse(preview.inventoryFull());
            assertEquals(3, preview.quantity());
            assertEquals(0, preview.requiredGold());
            assertTrue(preview.requiredItems().isEmpty());
            assertTrue(preview.missingItems().isEmpty());
            assertFalse(f.service.purchase(f.player, entry, 3).join());
            verifyNoInteractions(f.inventory, f.currency);
            logs.verify(() -> Logger.log(LogId.W_6301, entry.id(), entry.itemId(), "missing"), times(1));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入 preview
     * 検証契約: レシピが有効でも、商品全量の収納先に空きがなければ購入を拒否する。
     */
    @Test
    void validRecipeStillRejectsInsufficientInventoryCapacity() {
        Fixture f = new Fixture();
        f.recipes.replaceCache(Map.of("recipe", new ShopRecipeCost("recipe", 20, List.of())));
        when(f.currency.getGoldAmount(f.accountId)).thenReturn(1000L);
        when(f.inventory.getSpendableNormalItemAmount(f.accountId, "material")).thenReturn(100L);
        when(f.inventory.canAddItemToNormalInventory(eq(f.player), any(ItemModel.class), anyInt())).thenReturn(false);
        var preview = f.service.preview(f.player, entry("recipe"), 1);
        assertFalse(preview.canPurchase());
        assertTrue(preview.inventoryFull());
        assertTrue(preview.missingItems().isEmpty());
        assertEquals(30L, preview.requiredGold());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## Cost 解決
     * 検証契約: 有効なレシピの追加費用と、未指定・空白レシピの直接費用を維持する。
     */
    @Test
    void validRecipeAddsCostsAndOptionalRecipeKeepsDirectCosts() {
        Fixture f = new Fixture();
        f.recipes.replaceCache(Map.of("valid", new ShopRecipeCost("valid", 20,
            List.of(new ShopCostItem("material", "material", 3)))));
        when(f.currency.getGoldAmount(f.accountId)).thenReturn(1000L);
        when(f.inventory.getSpendableNormalItemAmount(f.accountId, "material")).thenReturn(100L);
        for (String recipe : new String[] {null, "", "  ", "valid"}) {
            var preview = f.service.preview(f.player, entry(recipe), 2);
            assertTrue(preview.canPurchase());
            assertEquals("valid".equals(recipe) ? 60L : 20L, preview.requiredGold());
            assertEquals("valid".equals(recipe) ? 10 : 4, preview.requiredItems().getFirst().amount());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## Cost 解決
     * 検証契約: マスタ再公開でレシピ修正を反映し、警告の抑制を次の公開までに限定する。
     */
    @Test
    void publicationRecoversFixedRecipeAndResetsWarningSuppression() {
        Fixture f = new Fixture();
        ShopEntry entry = entry("recipe");
        var shops = List.of(new ShopDefinition("shop", "Shop", ShopMode.SHOP, ShopAccess.PUBLIC, List.of(entry)));
        try (var logs = mockStatic(Logger.class)) {
            f.service.replaceCacheSnapshot(new ShopService.CacheSnapshot(shops, Map.of()));
            logs.verify(() -> Logger.log(LogId.W_6301, entry.id(), entry.itemId(), "recipe"));
            assertFalse(f.service.isRecipeAvailable(entry));
            assertFalse(f.service.isRecipeAvailable(entry));
            f.service.replaceCacheSnapshot(new ShopService.CacheSnapshot(shops,
                Map.of("recipe", new ShopRecipeCost("recipe", 20, List.of()))));
            assertTrue(f.service.isRecipeAvailable(entry));
            f.service.replaceCacheSnapshot(new ShopService.CacheSnapshot(shops, Map.of()));
            assertFalse(f.service.isRecipeAvailable(entry));
            logs.verify(() -> Logger.log(LogId.W_6301, entry.id(), entry.itemId(), "recipe"), times(2));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_3-メソッド仕様.md
     * 章・見出し: # 20_3-メソッド仕様 > ## 購入 preview
     * 検証契約: preview途中でマスタが再公開されても、検証済みレシピのGoldと素材を省略しない。
     */
    @Test
    void previewUsesOneResolvedRecipeForValidationGoldAndMaterials() {
        Fixture f = new Fixture();
        f.recipes.replaceCache(Map.of("recipe", new ShopRecipeCost("recipe", 20,
            List.of(new ShopCostItem("material", "material", 3)))));
        when(f.currency.getGoldAmount(f.accountId)).thenAnswer(call -> {
            f.recipes.replaceCache(Map.of());
            return 1000L;
        });
        when(f.inventory.getSpendableNormalItemAmount(f.accountId, "material")).thenReturn(100L);
        var preview = f.service.preview(f.player, entry("recipe"), 2);
        assertTrue(preview.canPurchase());
        assertEquals(60L, preview.requiredGold());
        assertEquals(10, preview.requiredItems().getFirst().amount());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_4-統合フロー.md
     * 章・見出し: # 20_4-統合フロー > ## 3. Preview・購入 > ### 処理要点
     * 検証契約: 重要操作待機中にレシピが消えた場合も、lane内再検証で支払いと付与を拒否する。
     */
    @Test
    void missingRecipeAtMutationBoundaryRejectsWithoutPaymentOrGrant() {
        Fixture f = new Fixture();
        ShopEntry entry = entry("recipe");
        f.recipes.replaceCache(Map.of("recipe", new ShopRecipeCost("recipe", 20, List.of())));
        ItemModel model = mock(ItemModel.class);
        when(f.items.findLoadedById(entry.itemId())).thenReturn(model);
        when(f.currency.getGoldAmount(f.accountId)).thenReturn(1000L);
        when(f.inventory.getSpendableNormalItemAmount(f.accountId, "material")).thenReturn(100L);
        when(f.inventory.canAddItemToNormalInventory(f.player, model, 1)).thenReturn(true);
        when(f.inventory.<Boolean>executeCriticalPlayerMutation(eq(f.accountId), any())).thenAnswer(call -> {
            Supplier<InventorySaveCoordinator.CriticalMutation<Boolean>> mutation = call.getArgument(1);
            f.recipes.replaceCache(Map.of());
            try {
                mutation.get();
                return CompletableFuture.completedFuture(true);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        });
        try (var logs = mockStatic(Logger.class)) {
            assertFalse(f.service.purchase(f.player, entry, 1).join());
            verify(f.inventory, never()).snapshotState(any());
            verify(f.inventory, never()).consumeGold(any(), anyLong());
            verify(f.inventory, never()).addItemToNormalInventoryStateOnly(any(), any(), anyInt(), anyString());
        }
    }

    private static ShopEntry entry(String recipeId) {
        return new ShopEntry("entry", "product", "material", 1, 1, 0, null, null,
            10, List.of(new ShopCostItem("material", "material", 2)), recipeId);
    }

    private static final class Fixture {
        private final UUID accountId = UUID.randomUUID();
        private final ShopRecipeRepository recipes = new ShopRecipeRepository();
        private final ItemService items = mock(ItemService.class);
        private final InventoryService inventory = mock(InventoryService.class);
        private final CurrencyService currency = mock(CurrencyService.class);
        private final AstPlayer player = mock(AstPlayer.class, RETURNS_DEEP_STUBS);
        private final ShopService service = new ShopService(mock(ShopRepository.class), recipes, items, inventory, currency);

        private Fixture() {
            recipes.replaceCache(Map.of());
            when(player.getAccount().getMode()).thenReturn(AccountMode.PLAYER);
            when(player.getAccount().getUuid()).thenReturn(accountId);
            when(items.findLoadedById("product")).thenReturn(mock(ItemModel.class));
            when(inventory.canAddItemToNormalInventory(eq(player), any(ItemModel.class), anyInt())).thenReturn(true);
        }
    }
}
