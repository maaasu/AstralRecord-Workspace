package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemInventoryStatusDesignTest extends MockBukkitTestBase {

    @Test
    void itemGetFlowAddsStackableItemToNormalInventoryState() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel normalInventory = harness.addInventory(state, InventoryType.NORMAL);
        ItemModel iron = DesignTestFixtures.item("iron_ingot", ItemCategory.MATERIAL, 64);

        int granted = harness.inventoryService.addItemToNormalInventory(astPlayer, iron, 3, "command");

        List<InventoryEntryModel> entries = state.snapshotEntries(normalInventory.getInventoryId());
        assertEquals(3, granted);
        assertEquals(1, entries.size());
        assertEquals(1, entries.get(0).getSlotIndex());
        assertEquals("iron_ingot", entries.get(0).getItemId());
        assertEquals(ItemCategory.MATERIAL.getApiValue(), entries.get(0).getItemCategory());
        assertEquals(3L, entries.get(0).getQuantity());
        assertTrue(state.isDirty());
    }

    @Test
    void itemGetFlowRoutesEquipmentAndRuneToInstanceBackedInventories() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel equipmentInventory = harness.addInventory(state, InventoryType.EQUIPMENT);
        InventoryModel runeInventory = harness.addInventory(state, InventoryType.RUNE);
        UUID equipmentInstanceId = UUID.randomUUID();
        UUID runeInstanceId = UUID.randomUUID();
        when(harness.itemService.createEquipmentInstance("bronze_sword", astPlayer.getAccount().getUuid().toString(), "command", astPlayer.getAccount().getUuid().toString()))
            .thenReturn(DesignTestFixtures.equipmentInstance(
                equipmentInstanceId,
                astPlayer.getAccount().getUuid(),
                "bronze_sword",
                "ATTACK",
                "1",
                "1"
            ));
        when(harness.itemService.createRuneInstance("minor_rune", astPlayer.getAccount().getUuid().toString(), "command", astPlayer.getAccount().getUuid().toString()))
            .thenReturn(DesignTestFixtures.runeInstance(
                runeInstanceId,
                astPlayer.getAccount().getUuid(),
                "minor_rune"
            ));

        int grantedEquipment = harness.inventoryService.addItemToNormalInventory(
            astPlayer,
            DesignTestFixtures.item("bronze_sword", ItemCategory.EQUIPMENT, 1),
            1,
            "command"
        );
        int grantedRune = harness.inventoryService.addItemToNormalInventory(
            astPlayer,
            DesignTestFixtures.item("minor_rune", ItemCategory.RUNE, 1),
            1,
            "command"
        );

        InventoryEntryModel equipmentEntry = state.snapshotEntries(equipmentInventory.getInventoryId()).get(0);
        InventoryEntryModel runeEntry = state.snapshotEntries(runeInventory.getInventoryId()).get(0);
        assertEquals(1, grantedEquipment);
        assertEquals(InventoryInstanceType.EQUIPMENT.getCode(), equipmentEntry.getInstanceType());
        assertEquals(equipmentInstanceId, equipmentEntry.getInstanceId());
        assertEquals(1, grantedRune);
        assertEquals(InventoryInstanceType.RUNE.getCode(), runeEntry.getInstanceType());
        assertEquals(runeInstanceId, runeEntry.getInstanceId());
    }

    @Test
    void statusRefreshUsesInventoryEquippedReferencesForEquipmentBonus() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        UUID equipmentInstanceId = UUID.randomUUID();
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        EquipmentInstance equipmentInstance = DesignTestFixtures.equipmentInstance(
            equipmentInstanceId,
            astPlayer.getAccount().getUuid(),
            "bronze_sword",
            "ATTACK",
            "4",
            "6"
        );
        when(inventoryService.getEquippedItemReferences(astPlayer)).thenReturn(List.of(
            new ItemReference("bronze_sword", ItemCategory.EQUIPMENT.getApiValue(), equipmentInstanceId.toString(), null)
        ));
        when(itemService.findEquipmentInstanceById(equipmentInstanceId.toString())).thenReturn(equipmentInstance);
        when(itemService.findLoadedById("bronze_sword")).thenReturn(
            DesignTestFixtures.equipmentItem("bronze_sword", "ATTACK", ItemEquipmentStatType.FLAT)
        );

        StatusSnapshot snapshot = new StatusService(itemService, inventoryService).refreshStatus(astPlayer);

        assertEquals(13.0D, snapshot.getMaxValue(StatusType.ATTACK), 0.0001D);
        assertEquals(snapshot.getMaxValue(StatusType.MAX_HEALTH), snapshot.getCurrentHp(), 0.0001D);
        assertEquals(snapshot.getMaxValue(StatusType.MAX_MANA), snapshot.getCurrentMp(), 0.0001D);
        assertEquals(snapshot.getMaxValue(StatusType.MAX_ENERGY), snapshot.getCurrentEnergy(), 0.0001D);
    }

    @Test
    void hotbarShortcutRenderingKeepsSlotsZeroToSevenForActionsAndSlotEightForInventoryCycle() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        HotbarRenderer renderer = new HotbarRenderer(mock(InventoryItemStackResolver.class));

        renderer.renderShortcutIcons(astPlayer, InventoryType.NORMAL, Map.of(InventoryType.NORMAL, 3L), 7);

        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, player.getInventory().getItem(0).getType());
        assertEquals(Material.BARRIER, player.getInventory().getItem(HotbarRenderer.SHORTCUT_CLOSE_SLOT).getType());
        assertEquals(Material.CHEST, player.getInventory().getItem(HotbarRenderer.SHORTCUT_INVENTORY_CYCLE_SLOT).getType());
        assertEquals(8, HotbarRenderer.SHORTCUT_INVENTORY_CYCLE_SLOT);
        assertEquals(7, player.getInventory().getItem(HotbarRenderer.SHORTCUT_INVENTORY_CYCLE_SLOT).getAmount());
    }

    private static InventoryHarness inventoryHarness() {
        ItemService itemService = mock(ItemService.class);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository equipmentLoadoutRepository = mock(EquipmentLoadoutRepository.class);
        PlayerInventoryStateRegistry stateRegistry = new PlayerInventoryStateRegistry();
        return new InventoryHarness(
            itemService,
            stateRegistry,
            new InventoryService(
                inventoryRepository,
                equipmentLoadoutRepository,
                itemService,
                new ItemStackFactory(mock(LootService.class), itemService),
                stateRegistry,
                new InventoryPersistence(inventoryRepository, equipmentLoadoutRepository)
            )
        );
    }

    private record InventoryHarness(
        ItemService itemService,
        PlayerInventoryStateRegistry stateRegistry,
        InventoryService inventoryService
    ) {
        private PlayerInventoryState registerState(AstPlayer astPlayer) {
            PlayerInventoryState state = new PlayerInventoryState(astPlayer.getAccount().getUuid());
            stateRegistry.put(state);
            return state;
        }

        private InventoryModel addInventory(PlayerInventoryState state, InventoryType type) {
            InventoryModel inventory = DesignTestFixtures.inventory(state.getAccountId(), type);
            state.putInventory(inventory);
            state.replaceEntriesFromLoad(inventory.getInventoryId(), List.of());
            return inventory;
        }
    }
}
