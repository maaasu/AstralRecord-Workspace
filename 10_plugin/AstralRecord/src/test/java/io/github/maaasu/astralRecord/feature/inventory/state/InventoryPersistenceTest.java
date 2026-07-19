package io.github.maaasu.astralRecord.feature.inventory.state;

import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryPersistenceTest {

    @Test
    void saveNowFailsWhileEquipmentDurabilityRemainsPending() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        when(itemService.hasDirtyEquipmentDurability(accountId)).thenReturn(true, true);
        when(itemService.flushDirtyEquipmentDurability(accountId)).thenReturn(true);
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository,
            loadoutRepository,
            itemService
        );

        boolean succeeded = persistence.saveNow(state);

        assertFalse(succeeded);
        assertFalse(state.isDirty());
        verify(itemService, times(2)).hasDirtyEquipmentDurability(accountId);
        verify(itemService).flushDirtyEquipmentDurability(accountId);
    }
}
