package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.shop.model.ShopCostItem;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopServiceTest {

    @Test
    void resolvesShopEntryDisplayNameFromItemMaster() {
        ItemService itemService = mock(ItemService.class);
        ShopService service = service(itemService);
        ShopEntry entry = new ShopEntry(
            "starter_sword",
            "starter_sword",
            "equipment",
            1,
            1,
            null,
            null,
            null,
            100,
            List.of(),
            null
        );
        when(itemService.findLoadedById("starter_sword")).thenReturn(item("starter_sword", "equipment", "&aStarter Sword"));

        assertEquals("Starter Sword", service.resolveItemDisplayName(entry));
    }

    @Test
    void resolvesRequiredMaterialDisplayNameFromItemMaster() {
        ItemService itemService = mock(ItemService.class);
        ShopService service = service(itemService);
        ShopCostItem cost = new ShopCostItem("iron_ingot", "material", 3);
        when(itemService.findLoadedById("iron_ingot")).thenReturn(null);
        when(itemService.loadItem("iron_ingot", "material")).thenReturn(item("iron_ingot", "material", "&fIron Ingot"));

        assertEquals("Iron Ingot", service.resolveItemDisplayName(cost));
    }

    private static ShopService service(ItemService itemService) {
        return new ShopService(
            mock(ShopRepository.class),
            mock(ShopRecipeRepository.class),
            itemService,
            mock(InventoryService.class),
            mock(CurrencyService.class)
        );
    }

    private static ItemModel item(String id, String category, String name) {
        return new ItemModel(
            1,
            id,
            category,
            name,
            "PAPER",
            "common",
            64,
            0,
            null,
            List.of(),
            false,
            false,
            null,
            null,
            null,
            null,
            null
        );
    }
}
