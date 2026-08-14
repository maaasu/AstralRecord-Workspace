package io.github.maaasu.astralRecord.feature.market.gui;

import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.market.model.MarketAccountSummary;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MarketGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_3-メソッド仕様.md
     * 章・見出し: # 23_3-メソッド仕様 > ## GUI 起動・プレイヤー操作
     * 検証契約: 54slotの出品一覧はfooter slot49の共通閉じるボタン、slot51の更新、共通ホットバーGUI holderを使用する。
     */
    @Test
    void rendersListingsWithStandardCloseNavigationLayout() {
        MarketGui gui = gui();
        var player = server().addPlayer();

        gui.openListings(player, UUID.randomUUID(), MarketScreen.BROWSE, List.of(), summary(), 1, 12_345L);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        MarketGui.MarketHolder holder = gui.getHolder(inventory);
        assertNotNull(holder);
        assertEquals(MarketGui.LIST_SIZE, inventory.getSize());
        assertInstanceOf(HotbarShortcutGuiHolder.class, inventory.getHolder());
        assertEquals(49, MarketGui.CLOSE_SLOT);
        assertEquals(MarketGui.CLOSE_SLOT, holder.getBackSlot());
        assertTrue(holder.isAlwaysCloseNavigation());
        assertItemName(inventory, MarketGui.CLOSE_SLOT, Material.BARRIER, "閉じる");
        assertItemName(inventory, MarketGui.REFRESH_SLOT, Material.CLOCK, "更新");
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
        assertItemName(inventory, MarketGui.BROWSE_SLOT, Material.SPECTRAL_ARROW, "出品一覧へ戻る");
        assertItemName(inventory, MarketGui.CLOSE_SLOT, Material.BARRIER, "閉じる");
        String selectionLore = loreText(inventory, MarketGui.ITEM_SLOT);
        assertTrue(selectionLore.contains("バッグまたはホットバー"));
        assertTrue(selectionLore.contains("上下にスクロール"));
        assertTrue(loreText(inventory, MarketGui.SUMMARY_SLOT).contains("所持 Gold: 12,345"));
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
