package io.github.maaasu.astralRecord.feature.shop.gui;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.shop.model.ShopAccess;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.model.ShopMode;
import io.github.maaasu.astralRecord.feature.shop.model.ShopSpecialPurchaseState;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_2-ユースケース.md
     * 章・見出し: # 20_2-ユースケース > ## UC-20-03 商品を preview する
     * 検証契約: アストラルドだけを対価とする商品は、Gold 0 の表示を出さず、必要な通貨として表示する。
     */
    @Test
    void astraldOnlyShopItemShowsCurrencyCostWithoutGoldZero() {
        ShopService shopService = mock(ShopService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        ItemModel token = new ItemModel(
            1,
            "market_expansion_token_alpha",
            ItemCategory.CURRENCY.getApiValue(),
            "マーケット拡張トークンα",
            "COPPER_CHEST",
            "common",
            1,
            0,
            null,
            null,
            List.of(),
            true,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        ShopEntry entry = new ShopEntry(
            "market_expansion_token_alpha",
            token.getId(),
            "currency",
            1,
            1,
            0,
            null,
            null,
            0,
            List.of(new ShopCostItem("astrald", "currency", 50)),
            null
        );
        when(shopService.resolveItem(entry)).thenReturn(token);
        when(shopService.resolveGoldCost(entry)).thenReturn(0);
        when(shopService.resolveRequiredItems(entry)).thenReturn(entry.requiredItems());
        when(shopService.previewSpecialPurchase(null, token)).thenReturn(ShopSpecialPurchaseState.standard());
        when(shopService.resolveItemDisplayName(entry.requiredItems().get(0))).thenReturn("アストラルド");
        when(itemStackFactory.createShopDisplay(token, 1)).thenReturn(new ItemStack(Material.PAPER));

        ShopGui gui = new ShopGui(
            new NamespacedKey("astralrecord", "shop_entry_id_test"),
            shopService,
            itemStackFactory
        );
        var player = server().addPlayer();
        gui.openList(player, new ShopDefinition(
            "astrald_shop",
            "アストラルドショップ",
            ShopMode.SHOP,
            ShopAccess.NPC_ONLY,
            List.of(entry)
        ));

        Inventory inventory = player.getOpenInventory().getTopInventory();
        String lore = inventory.getItem(10).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", String::concat);
        assertTrue(lore.contains("必要な通貨"));
        assertTrue(lore.contains("アストラルド ×50"));
        assertFalse(lore.contains("価格: 0 ゴールド"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_2-ユースケース.md
     * 章・見出し: # 20_2-ユースケース > ## UC-20-03 商品を preview する
     * 検証契約: アストラルドだけを対価とする購入確認画面は、アストラルド価格を表示し、Gold 0 を表示しない。
     */
    @Test
    void astraldOnlyShopItemConfirmationShowsCurrencyCostWithoutGoldZero() {
        ShopService shopService = mock(ShopService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        ItemModel token = new ItemModel(
            1,
            "market_expansion_token_alpha",
            ItemCategory.CURRENCY.getApiValue(),
            "マーケット拡張トークンα",
            "COPPER_CHEST",
            "common",
            1,
            0,
            null,
            null,
            List.of(),
            true,
            true,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        ShopEntry entry = new ShopEntry(
            "market_expansion_token_alpha",
            token.getId(),
            "currency",
            1,
            1,
            0,
            null,
            null,
            0,
            List.of(new ShopCostItem("astrald", "currency", 50)),
            null
        );
        when(shopService.resolveItem(entry)).thenReturn(token);
        when(shopService.resolveItemDisplayName(entry)).thenReturn("マーケット拡張トークンα");
        when(shopService.resolveItemDisplayName(entry.requiredItems().get(0))).thenReturn("アストラルド");
        when(itemStackFactory.createShopDisplay(token, 1)).thenReturn(new ItemStack(Material.PAPER));

        ShopGui gui = new ShopGui(
            new NamespacedKey("astralrecord", "shop_entry_id_confirmation_test"),
            shopService,
            itemStackFactory
        );
        var player = server().addPlayer();
        ShopDefinition shop = new ShopDefinition(
            "astrald_shop",
            "アストラルドショップ",
            ShopMode.SHOP,
            ShopAccess.NPC_ONLY,
            List.of(entry)
        );
        gui.openConfirm(
            player,
            shop,
            entry,
            1,
            new io.github.maaasu.astralRecord.feature.shop.model.ShopPurchasePreview(
                1,
                0,
                0,
                entry.requiredItems(),
                List.of(),
                true
            )
        );

        String lore = player.getOpenInventory().getTopInventory().getItem(ShopGui.BUY_SLOT).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", String::concat);
        assertTrue(lore.contains("アストラルド ×50"));
        assertFalse(lore.contains("ゴールド:"));
        assertFalse(lore.contains("0 ゴールド"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 3. スキルジェム購入表示
     * 検証契約: 最大レベルのスキルジェムは※付き購入不可文言を表示し、数量変更UIを出さない。
     */
    @Test
    void maxLevelSkillGemShowsPurchaseBlockMessageAndNoQuantityControls() {
        ShopService shopService = mock(ShopService.class);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        ItemModel gem = mock(ItemModel.class);
        ShopEntry entry = new ShopEntry(
            "astral_edge_from_raw",
            "00_skill_gem_adventurer_astral_edge",
            "skill_gem",
            1,
            1,
            0,
            null,
            null,
            0,
            List.of(),
            null
        );
        ShopDefinition shop = new ShopDefinition(
            "skill_gem_exchange",
            "スキルジェム交換",
            ShopMode.EXCHANGE,
            ShopAccess.NPC_ONLY,
            List.of(entry)
        );
        when(shopService.resolveItem(entry)).thenReturn(gem);
        when(shopService.resolveItemDisplayName(entry)).thenReturn("アストラルエッジジェム");
        when(itemStackFactory.createShopDisplay(gem, 1)).thenReturn(new ItemStack(Material.AMETHYST_SHARD));
        ShopSpecialPurchaseState max = ShopSpecialPurchaseState.maxLevel(5);
        ShopGui gui = new ShopGui(
            new NamespacedKey("astralrecord", "shop_entry_id_max_level_test"),
            shopService,
            itemStackFactory
        );
        var player = server().addPlayer();

        gui.openConfirm(
            player,
            shop,
            entry,
            10,
            new io.github.maaasu.astralRecord.feature.shop.model.ShopPurchasePreview(
                1, 0, 0, List.of(), List.of(), false, max
            )
        );

        Inventory inventory = player.getOpenInventory().getTopInventory();
        String previewLore = inventory.getItem(ShopGui.ITEM_PREVIEW_SLOT).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", String::concat);
        String buyLore = inventory.getItem(ShopGui.BUY_SLOT).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", String::concat);
        assertTrue(previewLore.contains("※ すでにレベルMAXのため購入できません。"));
        assertTrue(buyLore.contains("レベルMAXのため購入できません"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(ShopGui.QUANTITY_PLUS_1_SLOT).getType());
    }
}
