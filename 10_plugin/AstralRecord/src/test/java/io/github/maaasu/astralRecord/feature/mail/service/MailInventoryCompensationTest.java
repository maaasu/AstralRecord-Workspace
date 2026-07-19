package io.github.maaasu.astralRecord.feature.mail.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MailInventoryCompensationTest extends MockBukkitTestBase {

    @Test
    void rollbackKeepsInventoryMutationAddedAfterMailGrant() {
        ItemService itemService = mock(ItemService.class);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        PlayerInventoryStateRegistry stateRegistry = new PlayerInventoryStateRegistry();
        InventoryPersistence persistence = new InventoryPersistence(inventoryRepository, loadoutRepository, itemService);
        InventorySaveCoordinator saveCoordinator = new InventorySaveCoordinator(
            persistence,
            stateRegistry,
            Runnable::run
        );
        InventoryService inventoryService = new InventoryService(
            inventoryRepository,
            loadoutRepository,
            itemService,
            new ItemStackFactory(mock(LootService.class), itemService),
            stateRegistry,
            persistence,
            saveCoordinator
        );
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.ADMIN);
        PlayerInventoryState state = new PlayerInventoryState(astPlayer.getAccount().getUuid());
        InventoryModel bag = DesignTestFixtures.inventory(state.getAccountId(), InventoryType.BAG);
        state.putInventory(bag);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of());
        stateRegistry.put(state);
        ItemModel mailReward = DesignTestFixtures.item("mail_reward", ItemCategory.MATERIAL, 64);
        ItemModel concurrentReward = DesignTestFixtures.item("concurrent_reward", ItemCategory.MATERIAL, 64);

        InventoryService.InventoryGrantReceipt receipt = inventoryService.addPreparedRewardsToNormalInventory(
            astPlayer,
            List.of(new InventoryService.PreparedInventoryReward(mailReward, 3, List.of()))
        );
        assertNotNull(receipt);
        assertEquals(2, inventoryService.addItemToNormalInventory(
            astPlayer,
            concurrentReward,
            2,
            "test"
        ));

        assertTrue(inventoryService.rollbackPreparedRewards(receipt));

        List<InventoryEntryModel> entries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(0L, amount(entries, "mail_reward"));
        assertEquals(2L, amount(entries, "concurrent_reward"));
    }

    private long amount(List<InventoryEntryModel> entries, String itemId) {
        return entries.stream()
            .filter(entry -> itemId.equals(entry.getItemId()))
            .mapToLong(InventoryEntryModel::getQuantity)
            .sum();
    }
}
