package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.currency.view.CurrencyGuiView;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_0-概要.md
     * 章・見出し: # 16_0-概要 > ## 4. GUI
     * 検証契約: MenuView経由で通貨一覧を初回表示したとき、両替条件未達ではslot 51がダミーpane、解除済みでは両替アイコンになる。
     */
    @Test
    void opensCurrencyGuiWithExchangeShortcutState() {
        MenuView menuView = createMenuView();
        Player player = server().addPlayer();

        menuView.openCurrency(player, List.of(), 0, false);
        Inventory lockedInventory = player.getOpenInventory().getTopInventory();
        assertEquals(MenuScreen.CURRENCY, menuView.getMenuScreen(lockedInventory));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, lockedInventory.getItem(CurrencyGuiView.EXCHANGE_SLOT).getType());

        player.closeInventory();
        menuView.openCurrency(player, List.of(), 0, true);
        Inventory unlockedInventory = player.getOpenInventory().getTopInventory();
        assertEquals(Material.EMERALD, unlockedInventory.getItem(51).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 1. menu facade
     * 検証契約: ガイド一覧を開く入口は GUIDE_OPENED を記録し、参加時案内 title を解除する。
     */
    @Test
    void openingGuideRecordsGuideOpenedCondition() {
        GuideService guideService = mock(GuideService.class);
        MenuView menuView = createMenuView(guideService);
        Player player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(UUID.randomUUID());

        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            menuView.openGuide(player);

            verify(guideService).recordCondition(astPlayer, GuideConditionType.GUIDE_OPENED, null);
        }
        assertEquals(MenuScreen.GUIDE, menuView.getMenuScreen(player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_4-統合フロー.md
     * 章・見出し: # 16_4-統合フロー > ## 1. 通貨一覧表示 > ### 処理要点
     * 検証契約: MenuView経由で通貨一覧を2ページ目に開いたとき、ページ番号を保持しつつ最新の両替条件未達状態をslot 51へ反映する。
     */
    @Test
    void redrawsExchangeShortcutStateWhenCurrencyPageChanges() {
        MenuView menuView = createMenuView();
        Player player = server().addPlayer();
        List<ItemStack> currencyItems = IntStream.range(0, 46)
            .mapToObj(index -> new ItemStack(Material.GOLD_NUGGET))
            .toList();

        menuView.openCurrency(player, currencyItems, 0, true);
        assertEquals(0, menuView.getPageIndex(player.getOpenInventory().getTopInventory()));
        assertEquals(Material.EMERALD, player.getOpenInventory().getItem(CurrencyGuiView.EXCHANGE_SLOT).getType());

        player.closeInventory();
        menuView.openCurrency(player, currencyItems, 1, false);
        Inventory pageTwoInventory = player.getOpenInventory().getTopInventory();
        assertEquals(1, menuView.getPageIndex(pageTwoInventory));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, pageTwoInventory.getItem(CurrencyGuiView.EXCHANGE_SLOT).getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: BE の残存 shortcut を除去した場合だけ MenuView がクライアント同期を行う。
     */
    @Test
    void bedrockCraftShortcutCleanupSyncsOnlyAfterRemoval() {
        MenuView menuView = createMenuView();
        Player player = mock(Player.class);
        InventoryView inventoryView = mock(InventoryView.class);
        CraftingInventory inventory = mock(CraftingInventory.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.isBedrock()).thenReturn(true);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(inventory.getMatrix()).thenReturn(new ItemStack[] {
            menuView.createCraftResultIcon(),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        });
        when(inventory.getResult()).thenReturn(new ItemStack(Material.AIR));
        when(player.getInventory()).thenReturn(playerInventory);
        when(playerInventory.getSize()).thenReturn(36);
        when(player.getItemOnCursor()).thenReturn(new ItemStack(Material.AIR));

        menuView.renderCraftShortcuts(
            astPlayer,
            MenuShortcutSettings.defaults(),
            emptyRenderContext()
        );

        verify(player).updateInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: BE の再描画時にクラフト欄が空でも、所持品とカーソルの残存 shortcut を除去して一度だけ同期する。
     */
    @Test
    void bedrockCraftShortcutCleanupRemovesInventoryAndCursorShortcut() {
        MenuView menuView = createMenuView();
        Player player = mock(Player.class);
        InventoryView inventoryView = mock(InventoryView.class);
        CraftingInventory craftingInventory = mock(CraftingInventory.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        ItemStack shortcut = menuView.createCraftResultIcon();
        when(astPlayer.isBedrock()).thenReturn(true);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(craftingInventory);
        when(craftingInventory.getMatrix()).thenReturn(new ItemStack[] {
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        });
        when(craftingInventory.getResult()).thenReturn(new ItemStack(Material.AIR));
        when(player.getInventory()).thenReturn(playerInventory);
        when(playerInventory.getSize()).thenReturn(36);
        when(playerInventory.getItem(5)).thenReturn(shortcut);
        when(player.getItemOnCursor()).thenReturn(shortcut);

        menuView.renderCraftShortcuts(
            astPlayer,
            MenuShortcutSettings.defaults(),
            emptyRenderContext()
        );

        verify(playerInventory).setItem(eq(5), any(ItemStack.class));
        verify(player).setItemOnCursor(any(ItemStack.class));
        verify(player).updateInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 4. プレイヤー一覧・詳細
     * 検証契約: 他プレイヤーの装備画面は対象 UUIDを保持し、参照専用として開く。自分以外を編集可能にしない。
     */
    @Test
    void opensOtherPlayerEquipmentAsReadOnly() {
        MenuView menuView = createMenuView();
        Player viewer = server().addPlayer();
        Player target = server().addPlayer();

        menuView.openEquipmentGui(viewer, target, new ItemStack[0], false);

        Inventory inventory = viewer.getOpenInventory().getTopInventory();
        assertEquals(MenuScreen.EQUIPMENT_GUI, menuView.getMenuScreen(inventory));
        assertTrue(menuView.isEquipmentReadOnly(inventory));
        assertEquals(target.getUniqueId(), menuView.getEquipmentTargetId(inventory));

        viewer.closeInventory();
        menuView.openEquipmentGui(viewer, viewer, new ItemStack[0], false);
        Inventory selfInventory = viewer.getOpenInventory().getTopInventory();
        assertFalse(menuView.isEquipmentReadOnly(selfInventory));
        assertEquals(viewer.getUniqueId(), menuView.getEquipmentTargetId(selfInventory));
    }

    private MenuView createMenuView() {
        return createMenuView(mock(GuideService.class));
    }

    private MenuView createMenuView(GuideService guideService) {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.namespace()).thenReturn("astralrecord");
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        return new MenuView(plugin, guideService);
    }

    private static PlayerGuiRenderContext emptyRenderContext() {
        return new PlayerGuiRenderContext(
            mock(AccountModel.class),
            StatusSnapshot.empty(),
            0,
            0,
            0L,
            0L,
            new PlayerEquipmentSnapshot(
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし")
            )
        );
    }
}
