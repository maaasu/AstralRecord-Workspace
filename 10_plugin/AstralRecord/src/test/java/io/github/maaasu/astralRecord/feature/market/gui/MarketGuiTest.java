package io.github.maaasu.astralRecord.feature.market.gui;

import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.market.model.MarketAccountSummary;
import io.github.maaasu.astralRecord.feature.market.model.MarketListing;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MarketGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## GUI 起動・プレイヤー操作
     * 検証契約: 54slotの出品一覧は上段中央の更新、閉じるボタンを持たない共通ホットバーGUI holder、ページ有無に応じたfooterナビゲーションを使用する。
     */
    @Test
    void rendersBrowseListingsWithHeaderNavigationWithoutCloseButton() {
        MarketGui gui = gui();
        var player = server().addPlayer();

        gui.openListings(player, UUID.randomUUID(), MarketScreen.BROWSE, List.of(), summary(), 1, 12_345L, false);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        MarketGui.MarketHolder holder = gui.getHolder(inventory);
        assertNotNull(holder);
        assertEquals(MarketGui.LIST_SIZE, inventory.getSize());
        assertInstanceOf(HotbarShortcutGuiHolder.class, inventory.getHolder());
        assertEquals(-1, holder.getBackSlot());
        assertFalse(holder.isAlwaysCloseNavigation());
        assertItemName(inventory, MarketGui.HEADER_ACTION_SLOT, Material.CLOCK, "更新");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, Objects.requireNonNull(inventory.getItem(MarketGui.SELL_SELECT_BACK_SLOT)).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, Objects.requireNonNull(inventory.getItem(MarketGui.NEXT_SLOT)).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## GUI 起動・プレイヤー操作
     * 検証契約: 自分の出品一覧は使用中・出品可能・未開放の出品枠を異なるアイコンで表示し、上段中央に新規出品ボタンを表示する。
     */
    @Test
    void rendersOwnListingSlotsWithAvailableAndLockedStates() {
        MarketGui gui = gui();
        var player = server().addPlayer();
        MarketAccountSummary summary = new MarketAccountSummary(
            UUID.randomUUID(),
            1,
            5,
            1,
            3,
            0,
            "NOVICE",
            null,
            Instant.EPOCH
        );

        gui.openListings(player, UUID.randomUUID(), MarketScreen.MY_LISTINGS, List.of(), summary, 1, 12_345L, false);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertItemName(inventory, MarketGui.HEADER_ACTION_SLOT, Material.CHEST, "新しく出品する");
        assertItemName(inventory, MarketGui.CONTENT_START_SLOT, Material.IRON_BARS, "使用中の出品枠");
        assertItemName(inventory, MarketGui.CONTENT_START_SLOT + 1, Material.LIGHT_GRAY_STAINED_GLASS_PANE, "出品可能枠");
        assertItemName(inventory, MarketGui.CONTENT_START_SLOT + 2, Material.LIGHT_GRAY_STAINED_GLASS_PANE, "出品可能枠");
        assertItemName(inventory, MarketGui.CONTENT_START_SLOT + 3, Material.IRON_BARS, "未開放の出品枠");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## GUI 起動・プレイヤー操作
     * 検証契約: 前後ページボタンは存在するページだけに紙アイコンで表示し、存在しない側は表示しない。
     */
    @Test
    void rendersOnlyExistingPageNavigationItems() {
        MarketGui gui = gui();
        var player = server().addPlayer();

        gui.openListings(player, UUID.randomUUID(), MarketScreen.BROWSE, List.of(), summary(), 1, 0L, true);

        Inventory firstPage = player.getOpenInventory().getTopInventory();
        assertItemName(firstPage, MarketGui.NEXT_SLOT, Material.PAPER, "次のページ");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, Objects.requireNonNull(firstPage.getItem(MarketGui.PREVIOUS_SLOT)).getType());

        var lastPagePlayer = server().addPlayer();
        gui.openListings(lastPagePlayer, UUID.randomUUID(), MarketScreen.BROWSE, List.of(), summary(), 2, 0L, false);

        Inventory lastPage = lastPagePlayer.getOpenInventory().getTopInventory();
        assertItemName(lastPage, MarketGui.PREVIOUS_SLOT, Material.PAPER, "前のページ");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, Objects.requireNonNull(lastPage.getItem(MarketGui.NEXT_SLOT)).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## GUI 起動・プレイヤー操作
     * 検証契約: 公開中の出品アイテムにはAPIから取得した出品者アカウント名を表示する。
     */
    @Test
    void rendersSellerAccountNameForBrowseListing() {
        MarketGui gui = gui();
        var player = server().addPlayer();

        gui.openListings(player, UUID.randomUUID(), MarketScreen.BROWSE, List.of(listing("market-seller")), summary(), 1, 0L, false);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertTrue(loreText(inventory, MarketGui.CONTENT_START_SLOT).contains("出品者: market-seller#0"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## GUI 起動・プレイヤー操作
     * 検証契約: 出品選択画面はバッグ・ホットバーの選択手順とスクロール案内を表示し、出品枠表示には実際のGold残高を表示する。
     */
    @Test
    void rendersClearItemSelectionGuidanceAndCurrentGoldBalance() {
        MarketGui gui = gui();
        var player = server().addPlayer();

        gui.openSellSelect(player, UUID.randomUUID(), summary(), 12_345L);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertItemName(inventory, MarketGui.SELL_SELECT_BACK_SLOT, Material.SPECTRAL_ARROW, "自分の出品一覧へ戻る");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, Objects.requireNonNull(inventory.getItem(MarketGui.BROWSE_SLOT)).getType());
        String selectionLore = loreText(inventory, MarketGui.ITEM_SLOT);
        assertTrue(selectionLore.contains("バッグまたはホットバー"));
        assertTrue(selectionLore.contains("上下にスクロール"));
        assertTrue(loreText(inventory, MarketGui.SUMMARY_SLOT).contains("所持 Gold: 12,345"));
        String summaryLore = loreText(inventory, MarketGui.SUMMARY_SLOT);
        assertTrue(summaryLore.contains("取引実績: 0件"));
        assertTrue(summaryLore.contains("現在Tier: NOVICE"));
        assertTrue(summaryLore.contains("1個につき +1枠"));
        assertTrue(summaryLore.contains("α: +6 / β: +9 / γ: +9 / δ: +9"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## GUI 起動・プレイヤー操作
     * 検証契約: 複数個出品の購入確認では、選択数量とその数量に対応する購入額を表示する。
     */
    @Test
    void rendersSelectedPartialPurchaseQuantityAndPrice() {
        MarketGui gui = gui();
        var player = server().addPlayer();

        gui.openPurchaseConfirm(player, UUID.randomUUID(), listing("market-seller", 5L, 5L), 3L, 1_000L);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertItemName(inventory, MarketGui.QUANTITY_SLOT, Material.HOPPER, "購入数: 3");
        assertItemName(inventory, MarketGui.PRICE_SLOT, Material.GOLD_INGOT, "購入額: 300 Gold");
        assertTrue(loreText(inventory, MarketGui.QUANTITY_SLOT).contains("残り: 5"));
    }

    private MarketGui gui() {
        return new MarketGui(mock(ItemService.class), mock(ItemStackFactory.class));
    }

    private MarketAccountSummary summary() {
        return new MarketAccountSummary(
            UUID.randomUUID(),
            1,
            5,
            2,
            5,
            0,
            "NOVICE",
            null,
            Instant.EPOCH
        );
    }

    private MarketListing listing(String sellerAccountName) {
        return listing(sellerAccountName, 1L, 1L);
    }

    private MarketListing listing(String sellerAccountName, long quantity, long remainingQuantity) {
        Instant listedAt = Instant.EPOCH;
        return new MarketListing(
            UUID.randomUUID(),
            UUID.randomUUID(),
            sellerAccountName,
            0,
            null,
            null,
            "MATERIAL",
            "stone",
            null,
            null,
            quantity,
            remainingQuantity,
            "gold",
            100,
            quantity * 100,
            1,
            null,
            null,
            "HIGH",
            null,
            null,
            "ACTIVE",
            null,
            listedAt,
            listedAt.plusSeconds(3600),
            null,
            null,
            1,
            listedAt,
            listedAt,
            0L,
            List.of()
        );
    }

    private void assertItemName(Inventory inventory, int slot, Material material, String name) {
        assertEquals(material, Objects.requireNonNull(inventory.getItem(slot)).getType());
        assertEquals(name, PlainTextComponentSerializer.plainText().serialize(
            Objects.requireNonNull(inventory.getItem(slot)).getItemMeta().displayName()
        ));
    }

    private String loreText(Inventory inventory, int slot) {
        return Objects.requireNonNull(inventory.getItem(slot)).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", String::concat);
    }
}
