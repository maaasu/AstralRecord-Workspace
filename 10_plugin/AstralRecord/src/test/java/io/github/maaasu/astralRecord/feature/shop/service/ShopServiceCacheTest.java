package io.github.maaasu.astralRecord.feature.shop.service;

import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopServiceCacheTest {

    @Test
    void cacheReloadKeepsCurrentCachesUntilPreparedSnapshotIsPublished() {
        ShopRepository shopRepository = mock(ShopRepository.class);
        ShopRecipeRepository recipeRepository = mock(ShopRecipeRepository.class);
        when(shopRepository.loadSnapshot()).thenReturn(List.of());
        when(recipeRepository.loadSnapshot()).thenReturn(Map.of());
        ShopService service = new ShopService(
            shopRepository,
            recipeRepository,
            mock(ItemService.class),
            mock(InventoryService.class),
            mock(CurrencyService.class)
        );

        ShopService.CacheSnapshot snapshot = service.loadCacheSnapshot();

        assertEquals(0, snapshot.size());
        verify(shopRepository).loadSnapshot();
        verify(recipeRepository).loadSnapshot();

        service.replaceCacheSnapshot(snapshot);
        verify(shopRepository).replaceCache(List.of());
        verify(recipeRepository).replaceCache(Map.of());
    }
}
