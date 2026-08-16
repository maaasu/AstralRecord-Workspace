package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.view.CurrencyGuiView;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private MenuView createMenuView() {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.namespace()).thenReturn("astralrecord");
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        return new MenuView(plugin, mock(GuideService.class));
    }
}
