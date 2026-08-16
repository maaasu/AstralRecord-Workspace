package io.github.maaasu.astralRecord.feature.currency.view;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrencyGuiViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_0-概要.md
     * 章・見出し: # 16_0-概要 > ## 4. GUI
     * 検証契約: 通貨一覧slot 51は両替条件を満たす場合だけ両替アイコンを描画し、条件未達時はダミーpaneを描画する。
     */
    @Test
    void rendersExchangeShortcutOnlyWhenUnlocked() {
        CurrencyGuiView view = new CurrencyGuiView();
        Inventory inventory = Bukkit.createInventory(null, 54);

        view.render(inventory, List.of(), 0, false);
        assertEquals(51, CurrencyGuiView.EXCHANGE_SLOT);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, inventory.getItem(50).getType());
        assertEquals(inventory.getItem(0), inventory.getItem(CurrencyGuiView.EXCHANGE_SLOT));

        view.render(inventory, List.of(), 0, true);
        assertEquals(Material.EMERALD, inventory.getItem(CurrencyGuiView.EXCHANGE_SLOT).getType());
        var unlockedMeta = inventory.getItem(CurrencyGuiView.EXCHANGE_SLOT).getItemMeta();
        assertEquals(
            "ゴールド両替所",
            PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(unlockedMeta.displayName()))
        );
        assertEquals(NamedTextColor.GOLD, unlockedMeta.displayName().color());
        assertEquals(
            "クリックして両替GUIを開きます",
            PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(unlockedMeta.lore()).getFirst())
        );

        view.render(inventory, List.of(), 0, false);
        assertEquals(inventory.getItem(0), inventory.getItem(CurrencyGuiView.EXCHANGE_SLOT));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_0-概要.md
     * 章・見出し: # 16_0-概要 > ## 4. GUI
     * 検証契約: 27slotの両替GUIへ組み込み7額面を所定Materialとslotで配置し、各slotから対応額面を逆引きできる。
     */
    @Test
    void rendersAllSevenBuiltInDenominationsInExchangeGui() {
        CurrencyService currencyService = mock(CurrencyService.class);
        UUID accountId = UUID.randomUUID();
        when(currencyService.getGoldAmount(accountId)).thenReturn(1_111_111L);
        CurrencyExchangeGuiView view = new CurrencyExchangeGuiView();
        var player = server().addPlayer();

        view.open(player, accountId, currencyService);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertEquals(27, inventory.getSize());
        assertInstanceOf(HotbarShortcutGuiHolder.class, inventory.getHolder());
        GoldDenomination[] denominations = {
            GoldDenomination.GOLD,
            GoldDenomination.GOLD_COIN,
            GoldDenomination.GOLD_INGOT,
            GoldDenomination.GOLD_BLOCK,
            GoldDenomination.GOLD_DIAMOND,
            GoldDenomination.GOLD_DIAMOND_BLOCK,
            GoldDenomination.YGGDRASIL_STAR_CORE,
        };
        Material[] materials = {
            Material.GOLD_NUGGET,
            Material.RAW_GOLD,
            Material.GOLD_INGOT,
            Material.GOLD_BLOCK,
            Material.DIAMOND,
            Material.DIAMOND_BLOCK,
            Material.NETHER_STAR,
        };
        for (int index = 0; index < denominations.length; index++) {
            int slot = 10 + index;
            assertEquals(materials[index], inventory.getItem(slot).getType());
            assertEquals(denominations[index], view.denominationAt(slot));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_0-概要.md
     * 章・見出し: # 16_0-概要 > ## 4. GUI
     * 検証契約: 通貨一覧itemへ残高表示専用loreを付け、通常BAGへ取り出せない表示であることを示す。
     */
    @Test
    void marksCurrencyEntriesAsBalanceOnly() {
        CurrencyGuiView view = new CurrencyGuiView();
        Inventory inventory = Bukkit.createInventory(null, 54);

        view.render(inventory, List.of(new org.bukkit.inventory.ItemStack(Material.GOLD_NUGGET)), 0, true);

        var lore = inventory.getItem(0).getItemMeta().lore();
        assertTrue(lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(CurrencyGuiView.BALANCE_ONLY_LORE::equals));
    }
}
