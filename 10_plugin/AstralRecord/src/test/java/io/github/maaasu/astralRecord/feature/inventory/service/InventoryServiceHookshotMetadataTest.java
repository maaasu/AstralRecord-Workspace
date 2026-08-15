package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryServiceHookshotMetadataTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 装填完了はhook 1個の消費と主手equipment metadataのloaded化を同じinventory stateで確定する。
     */
    @Test
    void atomicallyConsumesHookAndStoresLoadedMetadataForCurrentMainHandInstance() {
        ItemService itemService = mock(ItemService.class);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        InventoryPersistence persistence = new InventoryPersistence(inventoryRepository, loadoutRepository, itemService);
        InventoryService service = new InventoryService(
            inventoryRepository,
            loadoutRepository,
            itemService,
            new ItemStackFactory(mock(LootService.class), itemService),
            registry,
            persistence,
            new InventorySaveCoordinator(persistence, registry, Runnable::run)
        );
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        PlayerInventoryState state = new PlayerInventoryState(player.getAccount().getUuid());
        registry.put(state);
        InventoryModel bag = DesignTestFixtures.inventory(state.getAccountId(), InventoryType.BAG);
        InventoryModel hotbar = DesignTestFixtures.inventory(state.getAccountId(), InventoryType.HOTBAR);
        state.putInventory(bag);
        state.putInventory(hotbar);

        UUID instanceId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 0, 0);
        InventoryEntryModel hookshot = new InventoryEntryModel(
            UUID.randomUUID(),
            hotbar.getInventoryId(),
            1,
            ItemCategory.EQUIPMENT.getApiValue(),
            null,
            "EQUIPMENT",
            instanceId,
            1L,
            "{\"other\":true}",
            now,
            now,
            state.getAccountId(),
            state.getAccountId(),
            false
        );
        InventoryEntryModel hook = new InventoryEntryModel(
            UUID.randomUUID(),
            bag.getInventoryId(),
            1,
            ItemCategory.MATERIAL.getApiValue(),
            "hook",
            null,
            null,
            1L,
            null,
            now,
            now,
            state.getAccountId(),
            state.getAccountId(),
            false
        );
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(hookshot));
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(hook));

        boolean updated = service.consumeNormalItemAndUpdateHotbarEquipmentMetadata(
            player,
            EquipmentSlot.HAND,
            instanceId.toString(),
            "{\"other\":true}",
            "{\"other\":true,\"hookshot\":{\"loaded\":true}}",
            "hook",
            1L
        );

        assertTrue(updated);
        assertTrue(state.snapshotEntries(bag.getInventoryId()).isEmpty());
        assertEquals(
            "{\"other\":true,\"hookshot\":{\"loaded\":true}}",
            state.snapshotEntries(hotbar.getInventoryId()).getFirst().getMetadataJson()
        );
        assertTrue(state.isDirty());
        assertFalse(service.consumeNormalItemAndUpdateHotbarEquipmentMetadata(
            player,
            EquipmentSlot.HAND,
            instanceId.toString(),
            "{\"other\":true}",
            "{\"hookshot\":{\"loaded\":false}}",
            "hook",
            1L
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: storageへ格納した装填済みフックショットは、同じinstance metadataを保持して所持inventoryへ戻る。
     */
    @Test
    void storageRoundTripPreservesLoadedMetadataForHookshotInstance() {
        ItemService itemService = mock(ItemService.class);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        InventoryPersistence persistence = new InventoryPersistence(inventoryRepository, loadoutRepository, itemService);
        ItemStackFactory itemStackFactory = mock(ItemStackFactory.class);
        InventoryService service = new InventoryService(
            inventoryRepository,
            loadoutRepository,
            itemService,
            itemStackFactory,
            registry,
            persistence,
            new InventorySaveCoordinator(persistence, registry, Runnable::run)
        );
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        PlayerInventoryState state = new PlayerInventoryState(player.getAccount().getUuid());
        registry.put(state);
        InventoryModel bag = DesignTestFixtures.inventory(state.getAccountId(), InventoryType.BAG);
        InventoryModel storage = DesignTestFixtures.inventory(state.getAccountId(), InventoryType.STORAGE);
        state.putInventory(bag);
        state.putInventory(storage);

        UUID instanceId = UUID.randomUUID();
        ItemModel hookshotModel = DesignTestFixtures.item("hookshot", ItemCategory.EQUIPMENT, 1);
        EquipmentInstance hookshotInstance = DesignTestFixtures.equipmentInstance(
            instanceId,
            state.getAccountId(),
            hookshotModel.getId(),
            "attack",
            "0",
            "0"
        );
        when(itemService.findLoadedEquipmentInstanceById(instanceId.toString())).thenReturn(hookshotInstance);
        when(itemService.findEquipmentInstanceById(instanceId.toString())).thenReturn(hookshotInstance);
        when(itemService.findLoadedById(hookshotModel.getId())).thenReturn(hookshotModel);
        when(itemStackFactory.create(hookshotModel, hookshotInstance, 1))
            .thenAnswer(invocation -> new ItemStack(Material.PAPER));

        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 0, 0);
        InventoryEntryModel hookshot = new InventoryEntryModel(
            UUID.randomUUID(),
            bag.getInventoryId(),
            1,
            ItemCategory.EQUIPMENT.getApiValue(),
            null,
            "EQUIPMENT",
            instanceId,
            1L,
            "{\"other\":true,\"hookshot\":{\"loaded\":true}}",
            now,
            now,
            state.getAccountId(),
            state.getAccountId(),
            false
        );
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(hookshot));

        assertEquals(1, service.moveOwnedItemToStorage(player, 9, 1));
        InventoryEntryModel storedHookshot = state.snapshotEntries(storage.getInventoryId()).getFirst();
        assertTrue(storedHookshot.getMetadataJson().contains("\"hookshot\":{\"loaded\":true}"));

        assertEquals(1, service.withdrawStorageEntry(player, storedHookshot.getInventoryEntryId(), 1));
        InventoryEntryModel returnedHookshot = state.snapshotEntries(bag.getInventoryId()).getFirst();
        assertEquals(storedHookshot.getMetadataJson(), returnedHookshot.getMetadataJson());
        assertTrue(returnedHookshot.getMetadataJson().contains("\"hookshot\":{\"loaded\":true}"));
    }
}
