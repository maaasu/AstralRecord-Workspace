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
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemInventoryStatusDesignTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: stackable item取得をBAG既存stackまたは空slotへ追加しdirtyにする。
     */
    @Test
    void itemGetFlowAddsStackableItemToNormalInventoryState() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel normalInventory = harness.addInventory(state, InventoryType.BAG);
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: equipment/runeをinstance ID付きで統合BAGへ格納する。
     */
    @Test
    void itemGetFlowStoresEquipmentAndRuneTogetherInBag() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bagInventory = harness.addInventory(state, InventoryType.BAG);
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

        List<InventoryEntryModel> bagEntries = state.snapshotEntries(bagInventory.getInventoryId());
        InventoryEntryModel equipmentEntry = bagEntries.get(0);
        InventoryEntryModel runeEntry = bagEntries.get(1);
        assertEquals(1, grantedEquipment);
        assertEquals(InventoryInstanceType.EQUIPMENT.getCode(), equipmentEntry.getInstanceType());
        assertEquals(equipmentInstanceId, equipmentEntry.getInstanceId());
        assertEquals(1, grantedRune);
        assertEquals(InventoryInstanceType.RUNE.getCode(), runeEntry.getInstanceType());
        assertEquals(runeInstanceId, runeEntry.getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 3. インベントリ種別
     * 検証契約: 基礎24にplayer level 5ごと1slotを加算する。
     */
    @Test
    void inventorySlotsIncreaseOncePerFivePlayerLevels() {
        InventoryService inventoryService = mock(InventoryService.class);
        StatusService statusService = new StatusService(null, inventoryService);
        AstPlayer levelFour = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER, 0, 4);
        AstPlayer levelFive = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER, 0, 5);
        AstPlayer levelTen = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER, 0, 10);

        assertEquals(24.0D, statusService.refreshStatus(levelFour)
            .getMaxValue(StatusType.INVENTORY_SLOTS), 0.0001D);
        assertEquals(25.0D, statusService.refreshStatus(levelFive)
            .getMaxValue(StatusType.INVENTORY_SLOTS), 0.0001D);
        assertEquals(26.0D, statusService.refreshStatus(levelTen)
            .getMaxValue(StatusType.INVENTORY_SLOTS), 0.0001D);
        verify(inventoryService).applyBagSlotCapacity(levelFour, 24.0D);
        verify(inventoryService).applyBagSlotCapacity(levelFive, 25.0D);
        verify(inventoryService).applyBagSlotCapacity(levelTen, 26.0D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 3. インベントリ種別
     * 検証契約: 容量外既存entryを保持するが新規取得/stack先には使わない。
     */
    @Test
    void overflowEntryIsPreservedAndCannotReceiveNewItems() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        state.setBagSlotCapacity(2);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG, 99);
        InventoryEntryModel overflow = bagEntry(
            state.getAccountId(), bag.getInventoryId(), 3, "overflow_test", 1L);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(overflow));
        ItemModel model = DesignTestFixtures.item("overflow_test", ItemCategory.MATERIAL, 64);

        assertEquals(1, harness.inventoryService.addItemToNormalInventory(astPlayer, model, 1, "test"));
        harness.inventoryService.compactInventoryEntries(bag.getInventoryId(), state.getAccountId());

        List<InventoryEntryModel> entries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(2, entries.size());
        assertEquals(1L, entries.stream()
            .filter(entry -> entry.getSlotIndex() == 3)
            .findFirst()
            .orElseThrow()
            .getQuantity());
        assertEquals(1, entries.stream()
            .filter(entry -> entry.getSlotIndex() == 1)
            .count());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 11. 装備移動とアクセサリ移動
     * 検証契約: BAG中間entry全量移動後に後続entryを表示順のまま前詰めする。
     */
    @Test
    void fullTransferOfMiddleBagEntryCompactsFollowingEntries() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        harness.addInventory(state, InventoryType.STORAGE);
        ItemModel first = DesignTestFixtures.item("compact_first", ItemCategory.MATERIAL, 64);
        ItemModel middle = DesignTestFixtures.item("compact_middle", ItemCategory.MATERIAL, 64);
        ItemModel last = DesignTestFixtures.item("compact_last", ItemCategory.MATERIAL, 64);
        when(harness.itemService.findLoadedById(middle.getId())).thenReturn(middle);

        assertEquals(1, harness.inventoryService.addItemToNormalInventory(astPlayer, first, 1, "test"));
        assertEquals(1, harness.inventoryService.addItemToNormalInventory(astPlayer, middle, 1, "test"));
        assertEquals(1, harness.inventoryService.addItemToNormalInventory(astPlayer, last, 1, "test"));

        assertEquals(1, harness.inventoryService.moveOwnedItemToStorage(astPlayer, 10, 1));

        List<InventoryEntryModel> remaining = state.snapshotEntries(bag.getInventoryId()).stream()
            .sorted(java.util.Comparator.comparing(InventoryEntryModel::getSlotIndex))
            .toList();
        assertEquals(2, remaining.size());
        assertEquals("compact_first", remaining.get(0).getItemId());
        assertEquals(1, remaining.get(0).getSlotIndex());
        assertEquals("compact_last", remaining.get(1).getItemId());
        assertEquals(2, remaining.get(1).getSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 4. 表示インベントリ GUI 反映
     * 検証契約: 容量外entryを表示し、空の容量外枠を新規配置不可の黒枠にする。
     */
    @Test
    void overflowEntryRemainsVisibleWhileEmptyOverflowSlotsAreLocked() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        state.setBagSlotCapacity(0);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG, 99);
        harness.addInventory(state, InventoryType.HOTBAR, 9);
        ItemModel model = DesignTestFixtures.item("visible_overflow_test", ItemCategory.MATERIAL, 64);
        when(harness.itemService.findLoadedById(model.getId())).thenReturn(model);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            bagEntry(state.getAccountId(), bag.getInventoryId(), 1, model.getId(), 1L)
        ));

        harness.inventoryService.applyInventoriesToGuiOnJoin(astPlayer);

        assertEquals(Material.PAPER, astPlayer.getBukkit().getInventory().getItem(9).getType());
        assertEquals(Material.BLACK_STAINED_GLASS_PANE,
            astPlayer.getBukkit().getInventory().getItem(10).getType());
        assertFalse(astPlayer.getBukkit().getInventory().getItem(9).getItemMeta().lore().isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 17. 一時保持・補償・feature 境界
     * 検証契約: 同itemがhotbar割当済みでもstorage transferを優先し対応entryを移動する。
     */
    @Test
    void storageTransferTakesPriorityForItemAssignedToHotbar() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        harness.addInventory(state, InventoryType.BAG);
        harness.addInventory(state, InventoryType.HOTBAR);
        InventoryModel storage = harness.addInventory(state, InventoryType.STORAGE);
        ItemModel consumable = DesignTestFixtures.item("storage_priority_test", ItemCategory.CONSUMABLE, 16);
        when(harness.itemService.findLoadedById(consumable.getId())).thenReturn(consumable);

        assertEquals(1, harness.inventoryService.addItemToNormalInventory(astPlayer, consumable, 1, "test"));
        assertTrue(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));
        assertTrue(harness.inventoryService.hasHotbarEntry(astPlayer, 1));

        assertEquals(1, harness.inventoryService.moveOwnedItemToStorage(astPlayer, 0, 1));

        assertFalse(harness.inventoryService.hasHotbarEntry(astPlayer, 1));
        assertEquals(1, state.snapshotEntries(storage.getInventoryId()).size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 14. ストレージ操作
     * 検証契約: BAG/hotbar全体の同一通常itemを一括収納し全量取出し時は複数stackへ分割する。
     */
    @Test
    void storageBulkTransferMovesAndWithdrawsAllMatchingStacks() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        harness.addInventory(state, InventoryType.HOTBAR);
        InventoryModel storage = harness.addInventory(state, InventoryType.STORAGE);
        ItemModel material = DesignTestFixtures.item("bulk_storage_test", ItemCategory.CONSUMABLE, 64);
        when(harness.itemService.findLoadedById(material.getId())).thenReturn(material);
        when(harness.itemService.loadItem(material.getId())).thenReturn(material);

        assertEquals(130, harness.inventoryService.addItemToNormalInventory(astPlayer, material, 130, "test"));
        assertTrue(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));
        assertTrue(harness.inventoryService.hasHotbarEntry(astPlayer, 1));
        assertEquals(2, state.snapshotEntries(bag.getInventoryId()).size());

        assertEquals(130, harness.inventoryService.moveAllOwnedMatchingItemsToStorage(astPlayer, 0));

        assertTrue(state.snapshotEntries(bag.getInventoryId()).isEmpty());
        assertFalse(harness.inventoryService.hasHotbarEntry(astPlayer, 1));
        List<InventoryEntryModel> stored = state.snapshotEntries(storage.getInventoryId());
        assertEquals(1, stored.size());
        assertEquals(130L, stored.get(0).getQuantity());

        assertEquals(130, harness.inventoryService.withdrawStorageEntry(
            astPlayer,
            stored.get(0).getInventoryEntryId(),
            130
        ));

        assertTrue(state.snapshotEntries(storage.getInventoryId()).isEmpty());
        assertEquals(130L, state.snapshotEntries(bag.getInventoryId()).stream()
            .mapToLong(InventoryEntryModel::getQuantity)
            .sum());
        assertEquals(3, state.snapshotEntries(bag.getInventoryId()).size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 17. 一時保持・補償・feature 境界
     * 検証契約: legacy BAG内currency stackをCURRENCY inventoryへ一括移動する。
     */
    @Test
    void currencyBulkTransferMovesLegacyBagStacksBackToCurrency() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        harness.addInventory(state, InventoryType.HOTBAR);
        harness.addInventory(state, InventoryType.CURRENCY);
        ItemModel currency = DesignTestFixtures.item("bulk_currency_test", ItemCategory.CURRENCY, 64);
        when(harness.itemService.findLoadedById(currency.getId())).thenReturn(currency);
        when(harness.itemService.loadItem(currency.getId())).thenReturn(currency);

        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(new InventoryEntryModel(
            UUID.randomUUID(),
            bag.getInventoryId(),
            1,
            ItemCategory.CURRENCY.getApiValue(),
            currency.getId(),
            null,
            null,
            130L,
            null,
            LocalDateTime.now(),
            LocalDateTime.now(),
            state.getAccountId(),
            state.getAccountId(),
            false
        )));

        assertEquals(130, harness.inventoryService.moveAllOwnedMatchingCurrencyToCurrency(astPlayer, 9));

        assertTrue(state.snapshotEntries(bag.getInventoryId()).isEmpty());
        assertEquals(130L, harness.inventoryService.getCurrencyAmount(
            astPlayer.getAccount().getUuid(),
            currency.getId()
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 17. 一時保持・補償・feature 境界
     * 検証契約: status refreshがinventory/loadout上の装備instance参照をbonus計算へ渡す。
     */
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

        assertEquals(12.0D, snapshot.getValue(StatusType.ATTACK).getMinValue(), 0.0001D);
        assertEquals(14.0D, snapshot.getValue(StatusType.ATTACK).getMaxValue(), 0.0001D);
        assertEquals(snapshot.getMaxValue(StatusType.MAX_HEALTH), snapshot.getCurrentHp(), 0.0001D);
        assertEquals(snapshot.getMaxValue(StatusType.MAX_MANA), snapshot.getCurrentMp(), 0.0001D);
        assertEquals(snapshot.getMaxValue(StatusType.MAX_ENERGY), snapshot.getCurrentEnergy(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: Bukkit hotbar 0〜8の全9slotを通常item割当として描画する。
     */
    @Test
    void hotbarRenderingKeepsAllNineSlotsAsNormalHotbarSlots() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        HotbarRenderer renderer = new HotbarRenderer(mock(InventoryItemStackResolver.class));

        renderer.renderHotbarInventory(astPlayer, Map.of(), null);

        assertEquals(Material.GRAY_STAINED_GLASS_PANE, player.getInventory().getItem(0).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, player.getInventory().getItem(4).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, player.getInventory().getItem(8).getType());
    }

    private static InventoryHarness inventoryHarness() {
        ItemService itemService = mock(ItemService.class);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository equipmentLoadoutRepository = mock(EquipmentLoadoutRepository.class);
        PlayerInventoryStateRegistry stateRegistry = new PlayerInventoryStateRegistry();
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository,
            equipmentLoadoutRepository,
            itemService
        );
        InventorySaveCoordinator saveCoordinator = new InventorySaveCoordinator(
            persistence,
            stateRegistry,
            Runnable::run
        );
        return new InventoryHarness(
            itemService,
            stateRegistry,
            new InventoryService(
                inventoryRepository,
                equipmentLoadoutRepository,
                itemService,
                new ItemStackFactory(mock(LootService.class), itemService),
                stateRegistry,
                persistence,
                saveCoordinator
            )
        );
    }

    private static InventoryEntryModel bagEntry(
        UUID accountId,
        UUID inventoryId,
        int slot,
        String itemId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            inventoryId,
            slot,
            ItemCategory.MATERIAL.getApiValue(),
            itemId,
            null,
            null,
            quantity,
            null,
            now,
            now,
            accountId,
            accountId,
            false
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
            return addInventory(state, inventory);
        }

        private InventoryModel addInventory(PlayerInventoryState state, InventoryType type, Integer slotCapacity) {
            InventoryModel inventory = DesignTestFixtures.inventory(state.getAccountId(), type, slotCapacity);
            return addInventory(state, inventory);
        }

        private InventoryModel addInventory(PlayerInventoryState state, InventoryModel inventory) {
            state.putInventory(inventory);
            state.replaceEntriesFromLoad(inventory.getInventoryId(), List.of());
            return inventory;
        }
    }
}
