package io.github.maaasu.astralRecord.feature.currency.view;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrencyGuiViewTest extends MockBukkitTestBase {

    @Test
    void rendersLockedAndUnlockedExchangeShortcut() {
        CurrencyGuiView view = new CurrencyGuiView();
        Inventory inventory = Bukkit.createInventory(null, 54);

        view.render(inventory, List.of(), 0, false);
        assertEquals(Material.BARRIER, inventory.getItem(CurrencyGuiView.EXCHANGE_SLOT).getType());

        view.render(inventory, List.of(), 0, true);
        assertEquals(Material.NETHER_STAR, inventory.getItem(CurrencyGuiView.EXCHANGE_SLOT).getType());
    }

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
        assertEquals(Material.GOLD_NUGGET, inventory.getItem(10).getType());
        assertEquals(Material.DIAMOND, inventory.getItem(14).getType());
        assertEquals(Material.DIAMOND_BLOCK, inventory.getItem(15).getType());
        assertEquals(Material.NETHER_STAR, inventory.getItem(16).getType());
        assertEquals(GoldDenomination.YGGDRASIL_STAR_CORE, view.denominationAt(16));
    }
}
