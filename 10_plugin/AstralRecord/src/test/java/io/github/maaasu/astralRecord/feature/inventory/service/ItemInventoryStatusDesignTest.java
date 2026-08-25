package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
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
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
     * 検証契約: ルーン付与は個体IDを生成せず、materialと同じitemIdの既存stackへ数量を加算する。
     */
    @Test
    void runeGrantUsesSharedItemIdStackPathWithoutInstanceId() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        ItemModel rune = DesignTestFixtures.item("debug_attack_rune", ItemCategory.RUNE, 64);

        assertEquals(2, harness.inventoryService.addItemToNormalInventory(astPlayer, rune, 2, "test"));
        assertEquals(3, harness.inventoryService.addItemToNormalInventory(astPlayer, rune, 3, "test"));

        List<InventoryEntryModel> entries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, entries.size());
        assertEquals(5L, entries.getFirst().getQuantity());
        assertEquals(rune.getId(), entries.getFirst().getItemId());
        assertNull(entries.getFirst().getInstanceType());
        assertNull(entries.getFirst().getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: メール・クエストの準備済み報酬でも、ルーンは個体一覧を要求せず共通stackへ追加する。
     */
    @Test
    void preparedRuneRewardUsesSharedStackPath() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        ItemModel rune = DesignTestFixtures.item("prepared_rune", ItemCategory.RUNE, 64);
        InventoryService.PreparedInventoryReward reward =
            new InventoryService.PreparedInventoryReward(rune, 4, List.of());

        assertNotNull(harness.inventoryService.addPreparedRewardsToNormalInventory(
            astPlayer, List.of(reward)));

        List<InventoryEntryModel> entries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, entries.size());
        assertEquals(4L, entries.getFirst().getQuantity());
        assertNull(entries.getFirst().getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 付与結果は新規BAG slot使用数と付与後空きslot数をstate lock内で確定し、既存stack加算は新規slotを消費しない。
     */
    @Test
    void capacityResultDistinguishesNewBagSlotFromExistingStack() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        state.setBagSlotCapacity(4);
        harness.addInventory(state, InventoryType.BAG);
        ItemModel material = DesignTestFixtures.item("capacity_result_test", ItemCategory.MATERIAL, 64);

        InventoryService.NormalInventoryGrantResult first =
            harness.inventoryService.addItemToNormalInventoryWithCapacityResult(astPlayer, material, 1, "test");
        InventoryService.NormalInventoryGrantResult stacked =
            harness.inventoryService.addItemToNormalInventoryWithCapacityResult(astPlayer, material, 1, "test");

        assertEquals(1, first.grantedAmount());
        assertEquals(1, first.newlyOccupiedBagSlots());
        assertEquals(3, first.remainingBagSlots());
        assertEquals(1, stacked.grantedAmount());
        assertEquals(0, stacked.newlyOccupiedBagSlots());
        assertEquals(3, stacked.remainingBagSlots());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 非同期個体生成前のBAG slot予約は同一空きslotの二重予約を拒否し、予約成功時だけ生成済みinstanceを確定追加する。
     */
    @Test
    void preparedInstanceReservationPreventsDoubleBookingOfLastBagSlot() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        state.setBagSlotCapacity(1);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        ItemModel rune = DesignTestFixtures.item("reservation_equipment", ItemCategory.EQUIPMENT, 1);

        InventoryService.PreparedInstanceSlotReservationResult first =
            harness.inventoryService.reserveBagSlotForPreparedInstance(astPlayer, rune);
        InventoryService.PreparedInstanceSlotReservationResult second =
            harness.inventoryService.reserveBagSlotForPreparedInstance(astPlayer, rune);
        UUID instanceId = UUID.randomUUID();

        assertTrue(first.reserved());
        assertNotNull(first.reservation());
        assertEquals(0, first.remainingBagSlots());
        assertFalse(second.reserved());
        assertNull(second.reservation());
        assertEquals(0, second.remainingBagSlots());
        InventoryService.PreparedInstanceReservationCompletion completion =
            harness.inventoryService.completePreparedInstanceReservation(
            astPlayer,
            rune,
            InventoryInstanceType.EQUIPMENT,
            instanceId,
            first.reservation()
        );
        assertTrue(completion.completed());
        assertEquals(0, completion.remainingBagSlots());
        List<InventoryEntryModel> entries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, entries.size());
        assertEquals(1, entries.getFirst().getSlotIndex());
        assertEquals(instanceId, entries.getFirst().getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 非同期個体生成中の予約slotはBAG削除後の前詰め処理でも他entryに使わせない。
     */
    @Test
    void bagCompactionSkipsPreparedInstanceReservationSlot() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        state.setBagSlotCapacity(4);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        harness.addInventory(state, InventoryType.STORAGE);
        ItemModel first = DesignTestFixtures.item("reservation_compact_first", ItemCategory.MATERIAL, 64);
        ItemModel middle = DesignTestFixtures.item("reservation_compact_middle", ItemCategory.MATERIAL, 64);
        ItemModel last = DesignTestFixtures.item("reservation_compact_last", ItemCategory.MATERIAL, 64);
        ItemModel rune = DesignTestFixtures.item("reservation_compact_equipment", ItemCategory.EQUIPMENT, 1);
        when(harness.itemService.findLoadedById(last.getId())).thenReturn(last);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            bagEntry(state.getAccountId(), bag.getInventoryId(), 1, first.getId(), 1L),
            bagEntry(state.getAccountId(), bag.getInventoryId(), 3, middle.getId(), 1L),
            bagEntry(state.getAccountId(), bag.getInventoryId(), 4, last.getId(), 1L)
        ));

        InventoryService.PreparedInstanceSlotReservation reservation = harness.inventoryService
            .reserveBagSlotForPreparedInstance(astPlayer, rune)
            .reservation();

        assertNotNull(reservation);
        assertFalse(harness.inventoryService.canAddItemToNormalInventory(
            astPlayer,
            DesignTestFixtures.item("reservation_compact_other", ItemCategory.MATERIAL, 1),
            1
        ));
        assertEquals(1, harness.inventoryService.moveOwnedItemToStorage(astPlayer, 11, 1));
        List<InventoryEntryModel> afterCompaction = state.snapshotEntries(bag.getInventoryId());
        assertEquals(2, afterCompaction.size());
        assertEquals(first.getId(), afterCompaction.get(0).getItemId());
        assertEquals(1, afterCompaction.get(0).getSlotIndex());
        assertEquals(middle.getId(), afterCompaction.get(1).getItemId());
        assertEquals(3, afterCompaction.get(1).getSlotIndex());

        UUID instanceId = UUID.randomUUID();
        InventoryService.PreparedInstanceReservationCompletion completion = harness.inventoryService
            .completePreparedInstanceReservation(
                astPlayer,
                rune,
                InventoryInstanceType.EQUIPMENT,
                instanceId,
                reservation
            );

        assertTrue(completion.completed());
        assertEquals(3, state.snapshotEntries(bag.getInventoryId()).size());
        assertTrue(state.snapshotEntries(bag.getInventoryId()).stream()
            .anyMatch(entry -> entry.getSlotIndex() == 2 && instanceId.equals(entry.getInstanceId())));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 予約中にBAG実効容量が下がっても、予約済みinstanceは確保済みslotへ確定し永続孤児を作らない。
     */
    @Test
    void preparedInstanceReservationSurvivesBagCapacityReduction() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        state.setBagSlotCapacity(2);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        ItemModel rune = DesignTestFixtures.item(
            "reservation_capacity_reduction_equipment", ItemCategory.EQUIPMENT, 1);

        InventoryService.PreparedInstanceSlotReservation reservation = harness.inventoryService
            .reserveBagSlotForPreparedInstance(astPlayer, rune)
            .reservation();
        assertNotNull(reservation);

        harness.inventoryService.applyBagSlotCapacity(astPlayer, 0.0D);
        UUID instanceId = UUID.randomUUID();
        InventoryService.PreparedInstanceReservationCompletion completion = harness.inventoryService
            .completePreparedInstanceReservation(
                astPlayer,
                rune,
                InventoryInstanceType.EQUIPMENT,
                instanceId,
                reservation
            );

        assertTrue(completion.completed());
        assertEquals(0, completion.remainingBagSlots());
        assertTrue(state.snapshotEntries(bag.getInventoryId()).stream()
            .anyMatch(entry -> entry.getSlotIndex() == 1 && instanceId.equals(entry.getInstanceId())));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: 容量縮小で容量外になった既存予約は、現在の実効容量内で新たに空いたslotを塞がない。
     */
    @Test
    void reservationOutsideReducedCapacityDoesNotBlockNewManagedSlot() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        state.setBagSlotCapacity(4);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        harness.addInventory(state, InventoryType.STORAGE);
        ItemModel first = DesignTestFixtures.item("reservation_reduce_first", ItemCategory.MATERIAL, 64);
        ItemModel second = DesignTestFixtures.item("reservation_reduce_second", ItemCategory.MATERIAL, 64);
        ItemModel third = DesignTestFixtures.item("reservation_reduce_third", ItemCategory.MATERIAL, 64);
        ItemModel rune = DesignTestFixtures.item("reservation_reduce_equipment", ItemCategory.EQUIPMENT, 1);
        when(harness.itemService.findLoadedById(first.getId())).thenReturn(first);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            bagEntry(state.getAccountId(), bag.getInventoryId(), 1, first.getId(), 1L),
            bagEntry(state.getAccountId(), bag.getInventoryId(), 2, second.getId(), 1L),
            bagEntry(state.getAccountId(), bag.getInventoryId(), 3, third.getId(), 1L)
        ));

        InventoryService.PreparedInstanceSlotReservation firstReservation = harness.inventoryService
            .reserveBagSlotForPreparedInstance(astPlayer, rune)
            .reservation();
        assertNotNull(firstReservation);

        harness.inventoryService.applyBagSlotCapacity(astPlayer, 3.0D);
        assertEquals(1, harness.inventoryService.moveOwnedItemToStorage(astPlayer, 9, 1));
        InventoryService.PreparedInstanceSlotReservationResult secondReservation = harness.inventoryService
            .reserveBagSlotForPreparedInstance(astPlayer, rune);

        assertTrue(secondReservation.reserved());
        assertEquals(0, secondReservation.remainingBagSlots());
        harness.inventoryService.releasePreparedInstanceReservation(firstReservation);
        harness.inventoryService.releasePreparedInstanceReservation(secondReservation.reservation());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 9. インベントリエントリ下書き
     * 検証契約: 既存stackのローカル数量変更は、API が採番した expectedUpdatedAt を保存成功まで維持する。
     */
    @Test
    void itemGetFlowPreservesApiVersionWhenIncreasingExistingStack() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel normalInventory = harness.addInventory(state, InventoryType.BAG);
        LocalDateTime apiVersion = LocalDateTime.of(2026, 8, 2, 11, 11, 54, 438_365_700);
        state.replaceEntriesFromLoad(normalInventory.getInventoryId(), List.of(new InventoryEntryModel(
            UUID.randomUUID(),
            normalInventory.getInventoryId(),
            1,
            ItemCategory.MATERIAL.getApiValue(),
            "iron_ingot",
            null,
            null,
            2L,
            null,
            apiVersion.minusMinutes(1),
            apiVersion,
            astPlayer.getAccount().getUuid(),
            astPlayer.getAccount().getUuid(),
            false
        )));

        int granted = harness.inventoryService.addItemToNormalInventory(
            astPlayer,
            DesignTestFixtures.item("iron_ingot", ItemCategory.MATERIAL, 64),
            3,
            "command"
        );

        InventoryEntryModel updated = state.snapshotEntries(normalInventory.getInventoryId()).getFirst();
        assertEquals(3, granted);
        assertEquals(5L, updated.getQuantity());
        assertEquals(apiVersion, updated.getUpdatedAt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加 > ### 通常アイテムの消費・支払い順
     * 検証契約: 同一アイテムを消費するときは、通常インベントリ内の後方slotから先に減算し、前方の満杯stackを維持する。
     */
    @Test
    void normalItemConsumptionStartsAtTheHighestSlot() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            bagEntry(state.getAccountId(), bag.getInventoryId(), 1, "consume_order_test", 64L),
            bagEntry(state.getAccountId(), bag.getInventoryId(), 2, "consume_order_test", 30L)
        ));

        assertTrue(harness.inventoryService.consumeNormalItem(
            state.getAccountId(), "consume_order_test", 1L));

        List<InventoryEntryModel> entries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(2, entries.size());
        assertEquals(1, entries.get(0).getSlotIndex());
        assertEquals(64L, entries.get(0).getQuantity());
        assertEquals(2, entries.get(1).getSlotIndex());
        assertEquals(29L, entries.get(1).getQuantity());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加 > ### 通常アイテムの消費・支払い順
     * 検証契約: itemIdから消費対象entryを再解決する場合も、BAG内の後方slotをHOTBARより先に選ぶ。
     */
    @Test
    void normalItemConsumptionResolverUsesHighestBagSlotBeforeHotbar() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        InventoryEntryModel bagFront = bagEntry(
            state.getAccountId(), bag.getInventoryId(), 1, "consume_resolver_order_test", 64L);
        InventoryEntryModel bagBack = bagEntry(
            state.getAccountId(), bag.getInventoryId(), 2, "consume_resolver_order_test", 30L);
        InventoryEntryModel hotbarBack = bagEntry(
            state.getAccountId(), hotbar.getInventoryId(), 8, "consume_resolver_order_test", 30L);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(bagBack, bagFront));
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(hotbarBack));

        InventoryEntryModel selected = harness.inventoryService
            .findOwnedNormalItemEntryForConsumption(state.getAccountId(), "consume_resolver_order_test");

        assertNotNull(selected);
        assertEquals(bagBack.getInventoryEntryId(), selected.getInventoryEntryId());
        assertTrue(harness.inventoryService.consumeNormalItem(
            state.getAccountId(), "consume_resolver_order_test", 1L));
        List<InventoryEntryModel> remaining = state.snapshotEntries(bag.getInventoryId());
        assertEquals(64L, remaining.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(bagFront.getInventoryEntryId()))
            .findFirst()
            .orElseThrow()
            .getQuantity());
        assertEquals(29L, remaining.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(bagBack.getInventoryEntryId()))
            .findFirst()
            .orElseThrow()
            .getQuantity());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加 > ### 通常アイテムの消費・支払い順
     * 検証契約: 未指定slotは指定slotの後に扱い、同一slotはinventoryEntryId昇順で安定して選ぶ。
     */
    @Test
    void normalItemConsumptionOrderPlacesUnspecifiedSlotsLastAndBreaksTiesByEntryId() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        String itemId = "consume_resolver_tie_break_test";
        UUID lowerEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherEntryId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        InventoryEntryModel unspecifiedSlot = inventoryEntryWithId(
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            state.getAccountId(),
            bag.getInventoryId(),
            null,
            itemId,
            10L
        );
        InventoryEntryModel higherId = inventoryEntryWithId(
            higherEntryId, state.getAccountId(), bag.getInventoryId(), 5, itemId, 10L);
        InventoryEntryModel lowerId = inventoryEntryWithId(
            lowerEntryId, state.getAccountId(), bag.getInventoryId(), 5, itemId, 10L);
        state.replaceEntriesFromLoad(
            bag.getInventoryId(), List.of(unspecifiedSlot, higherId, lowerId));

        InventoryEntryModel selected = harness.inventoryService
            .findOwnedNormalItemEntryForConsumption(state.getAccountId(), itemId);

        assertNotNull(selected);
        assertEquals(lowerEntryId, selected.getInventoryEntryId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 検証契約: equipmentはinstance ID付き、runeはinstanceを持たない通常stack entryとして統合BAGへ格納する。
     */
    @Test
    void itemGetFlowStoresEquipmentAndRuneTogetherInBag() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bagInventory = harness.addInventory(state, InventoryType.BAG);
        UUID equipmentInstanceId = UUID.randomUUID();
        when(harness.itemService.createEquipmentInstance("bronze_sword", astPlayer.getAccount().getUuid().toString(), "command", astPlayer.getAccount().getUuid().toString()))
            .thenReturn(DesignTestFixtures.equipmentInstance(
                equipmentInstanceId,
                astPlayer.getAccount().getUuid(),
                "bronze_sword",
                "ATTACK",
                "1",
                "1"
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
        assertEquals("bronze_sword", equipmentEntry.getItemId());
        assertEquals(InventoryInstanceType.EQUIPMENT.getCode(), equipmentEntry.getInstanceType());
        assertEquals(equipmentInstanceId, equipmentEntry.getInstanceId());
        assertEquals(1, grantedRune);
        assertEquals("minor_rune", runeEntry.getItemId());
        assertEquals(1L, runeEntry.getQuantity());
        assertNull(runeEntry.getInstanceType());
        assertNull(runeEntry.getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 3. インベントリ種別
     * 検証契約: 基礎36にplayer level 5ごと1slotを加算する。
     */
    @Test
    void inventorySlotsIncreaseOncePerFivePlayerLevels() {
        InventoryService inventoryService = mock(InventoryService.class);
        StatusService statusService = new StatusService(null, inventoryService);
        AstPlayer levelFour = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER, 0, 4);
        AstPlayer levelFive = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER, 0, 5);
        AstPlayer levelTen = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER, 0, 10);

        assertEquals(36.0D, statusService.refreshStatus(levelFour)
            .getMaxValue(StatusType.INVENTORY_SLOTS), 0.0001D);
        assertEquals(37.0D, statusService.refreshStatus(levelFive)
            .getMaxValue(StatusType.INVENTORY_SLOTS), 0.0001D);
        assertEquals(38.0D, statusService.refreshStatus(levelTen)
            .getMaxValue(StatusType.INVENTORY_SLOTS), 0.0001D);
        verify(inventoryService).applyBagSlotCapacity(levelFour, 36.0D);
        verify(inventoryService).applyBagSlotCapacity(levelFive, 37.0D);
        verify(inventoryService).applyBagSlotCapacity(levelTen, 38.0D);
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
     * 章・見出し: # 08_3-サービス > ## 17. feature 境界
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
     * 章・見出し: # 08_3-サービス > ## 17. feature 境界
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
     * 章・見出し: # 08_3-サービス > ## 17. feature 境界
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
            new ItemReference("bronze_sword", ItemCategory.EQUIPMENT.getApiValue(), equipmentInstanceId.toString())
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
     * 検証契約: 選択スロットがない場合、同一通常アイテムの最初のHOTBAR stackへ数量を加算する。
     */
    @Test
    void assigningItemMergesIntoFirstMatchingHotbarStack() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        ItemModel consumable = DesignTestFixtures.item("hotbar_merge_test", ItemCategory.CONSUMABLE, 64);
        when(harness.itemService.findLoadedById(consumable.getId())).thenReturn(consumable);

        assertEquals(12, harness.inventoryService.addItemToNormalInventory(astPlayer, consumable, 12, "test"));
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(
            inventoryEntry(state.getAccountId(), hotbar.getInventoryId(), 1,
                ItemCategory.CONSUMABLE, consumable.getId(), 30L),
            inventoryEntry(state.getAccountId(), hotbar.getInventoryId(), 3,
                ItemCategory.CONSUMABLE, consumable.getId(), 5L)
        ));

        assertTrue(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        List<InventoryEntryModel> hotbarEntries = state.snapshotEntries(hotbar.getInventoryId());
        assertEquals(42L, entryAt(hotbarEntries, 1).getQuantity());
        assertEquals(5L, entryAt(hotbarEntries, 3).getQuantity());
        assertTrue(state.snapshotEntries(bag.getInventoryId()).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: 同一stackへの加算で最大値を超える場合、超過分をクリック元slotへ残す。
     */
    @Test
    void assigningItemLeavesOverflowInClickedBagSlot() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        ItemModel consumable = DesignTestFixtures.item("hotbar_overflow_test", ItemCategory.CONSUMABLE, 64);
        when(harness.itemService.findLoadedById(consumable.getId())).thenReturn(consumable);

        assertEquals(10, harness.inventoryService.addItemToNormalInventory(astPlayer, consumable, 10, "test"));
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(
            inventoryEntry(state.getAccountId(), hotbar.getInventoryId(), 1,
                ItemCategory.CONSUMABLE, consumable.getId(), 60L)
        ));

        assertTrue(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(64L, entryAt(state.snapshotEntries(hotbar.getInventoryId()), 1).getQuantity());
        List<InventoryEntryModel> bagEntries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, bagEntries.size());
        assertEquals(1, bagEntries.getFirst().getSlotIndex());
        assertEquals(6L, bagEntries.getFirst().getQuantity());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: 既存stackが満杯の場合、1〜9、オフハンド順の最初の空きslotへ移動する。
     */
    @Test
    void assigningItemUsesNextFreeHotbarSlotWhenExistingStackIsFull() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        ItemModel consumable = DesignTestFixtures.item("hotbar_full_stack_test", ItemCategory.CONSUMABLE, 64);
        when(harness.itemService.findLoadedById(consumable.getId())).thenReturn(consumable);

        assertEquals(12, harness.inventoryService.addItemToNormalInventory(astPlayer, consumable, 12, "test"));
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(
            inventoryEntry(state.getAccountId(), hotbar.getInventoryId(), 1,
                ItemCategory.CONSUMABLE, consumable.getId(), 64L)
        ));

        assertTrue(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(64L, entryAt(state.snapshotEntries(hotbar.getInventoryId()), 1).getQuantity());
        assertEquals(12L, entryAt(state.snapshotEntries(hotbar.getInventoryId()), 2).getQuantity());
        assertTrue(state.snapshotEntries(bag.getInventoryId()).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: メインホットバー1〜9が満杯の場合、オフハンドを自動割当先にせず、クリック元とホットバーを変更しない。
     */
    @Test
    void assigningItemDoesNotChangeWhenHotbarIsFull() {
        InventoryHarness harness = inventoryHarness();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        ItemModel consumable = DesignTestFixtures.item("hotbar_no_space_test", ItemCategory.CONSUMABLE, 64);
        when(harness.itemService.findLoadedById(consumable.getId())).thenReturn(consumable);

        assertEquals(12, harness.inventoryService.addItemToNormalInventory(astPlayer, consumable, 12, "test"));
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), java.util.stream.IntStream.rangeClosed(1, 9)
            .mapToObj(slot -> inventoryEntry(state.getAccountId(), hotbar.getInventoryId(), slot,
                ItemCategory.CONSUMABLE, consumable.getId(), 64L))
            .toList());

        assertFalse(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(12L, state.snapshotEntries(bag.getInventoryId()).getFirst().getQuantity());
        assertEquals(9, state.snapshotEntries(hotbar.getInventoryId()).size());
        assertFalse(harness.inventoryService.hasHotbarEntry(astPlayer, HotbarLayout.DB_SLOT_OFFHAND));
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
        assertTrue(HotbarRenderer.isHotbarDummy(player.getInventory().getItemInOffHand()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成
     * 検証契約: 現在保持中の主手だけセット効果の動的表示を解決し、GUI選択中でも非保持HOTBARは静的表示にする。
     */
    @Test
    void hotbarRenderingUsesDynamicSetEffectLoreOnlyForHeldSlot() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        InventoryItemStackResolver resolver = mock(InventoryItemStackResolver.class);
        HotbarRenderer renderer = new HotbarRenderer(resolver);
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID inventoryId = UUID.randomUUID();
        InventoryEntryModel heldEntry = inventoryEntry(
            accountId, inventoryId, HotbarLayout.DB_SLOT_START, ItemCategory.EQUIPMENT, "held_equipment", 1L);
        InventoryEntryModel selectedEntry = inventoryEntry(
            accountId, inventoryId, HotbarLayout.DB_SLOT_START + 1, ItemCategory.EQUIPMENT,
            "selected_equipment", 1L);
        Map<String, Integer> equippedSetCounts = Map.of("debug_armor_set", 2);
        when(resolver.resolveForEquippedDisplay(heldEntry, accountId, equippedSetCounts))
            .thenReturn(new ItemStack(Material.DIAMOND_SWORD));
        when(resolver.resolve(selectedEntry, accountId))
            .thenReturn(new ItemStack(Material.IRON_SWORD));

        renderer.renderHotbarInventory(
            astPlayer,
            Map.of(HotbarLayout.DB_SLOT_START, heldEntry, HotbarLayout.DB_SLOT_START + 1, selectedEntry),
            HotbarLayout.DB_SLOT_START + 1,
            equippedSetCounts
        );

        verify(resolver).resolveForEquippedDisplay(heldEntry, accountId, equippedSetCounts);
        verify(resolver).resolve(selectedEntry, accountId);
        verify(resolver, never()).resolveForEquippedDisplay(selectedEntry, accountId, equippedSetCounts);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: HOTBARオフハンドentryがない場合も、既に装備中のオフハンドアイテムをダミーで上書きしない。
     */
    @Test
    void hotbarRenderingPreservesActualOffhandWhenHotbarEntryIsAbsent() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        HotbarRenderer renderer = new HotbarRenderer(mock(InventoryItemStackResolver.class));
        ItemStack equipped = new ItemStack(Material.SHIELD);
        player.getInventory().setItemInOffHand(equipped);

        renderer.renderHotbarInventory(astPlayer, Map.of(), null);

        assertEquals(equipped, player.getInventory().getItemInOffHand());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 11. 装備移動とアクセサリ移動
     * 検証契約: オフハンド未選択でも、空を表すオフハンドダミーを補助装備の交換対象にせず装備する。
     */
    @Test
    void auxiliaryWeaponEquipsIntoEmptyOffhandWithoutSelectingOffhandHotbarSlot() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        harness.addInventory(state, InventoryType.EQUIP_SLOT);
        ItemModel auxiliaryWeapon = auxiliaryWeapon("auxiliary_weapon_test");
        when(harness.itemService.findLoadedById(auxiliaryWeapon.getId())).thenReturn(auxiliaryWeapon);
        InventoryEntryModel auxiliaryEntry = inventoryEntry(
            state.getAccountId(),
            bag.getInventoryId(),
            1,
            ItemCategory.EQUIPMENT,
            auxiliaryWeapon.getId(),
            1L
        );
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(auxiliaryEntry));
        assertTrue(harness.inventoryService.canPlaceInEquipmentGuiSlot(
            auxiliaryEntry, EquipmentType.OFF_HAND, null));
        assertFalse(harness.inventoryService.canPlaceInEquipmentGuiSlot(
            auxiliaryEntry, EquipmentType.MAIN_HAND, null));
        assertFalse(harness.inventoryService.canPlaceInEquipmentGuiSlot(
            auxiliaryEntry, EquipmentType.HEAD, null));
        assertFalse(harness.inventoryService.canPlaceInEquipmentGuiSlot(
            auxiliaryEntry, EquipmentType.CHEST, null));
        assertFalse(harness.inventoryService.canPlaceInEquipmentGuiSlot(
            auxiliaryEntry, EquipmentType.LEGS, null));
        assertFalse(harness.inventoryService.canPlaceInEquipmentGuiSlot(
            auxiliaryEntry, EquipmentType.FEET, null));

        HotbarRenderer renderer = new HotbarRenderer(mock(InventoryItemStackResolver.class));
        renderer.renderHotbarInventory(astPlayer, Map.of(), null);
        assertTrue(HotbarRenderer.isHotbarDummy(player.getInventory().getItemInOffHand()));
        assertNull(state.getSelectedHotbarSlot());

        assertTrue(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(ItemEquipmentSlot.SUBWEAPON,
            ItemEquipmentSlot.fromApiValue(ItemStackFactory.getEquipmentSlot(
                player.getInventory().getItemInOffHand())));
        assertTrue(state.snapshotEntries(bag.getInventoryId()).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: HOTBAR entry がないオフハンドクリックでも、装備中の補助装備個体を一度だけBAGへ戻し、オフハンドを空表示にする。
     */
    @Test
    void clickingEmptyOffhandHotbarSlotReturnsEquippedAuxiliaryToBagOnce() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        ItemModel auxiliary = auxiliaryWeapon("offhand_return_test");
        UUID instanceId = UUID.randomUUID();
        EquipmentInstance instance = DesignTestFixtures.equipmentInstance(
            instanceId, state.getAccountId(), auxiliary.getId(), "ATTACK", "1", "1");
        when(harness.itemService.findLoadedById(auxiliary.getId())).thenReturn(auxiliary);
        when(harness.itemService.findLoadedEquipmentInstanceById(instanceId.toString())).thenReturn(instance);
        ItemStack equipped = new ItemStackFactory(mock(LootService.class), harness.itemService)
            .create(auxiliary, instance, 1);
        player.getInventory().setItemInOffHand(equipped);

        assertTrue(harness.inventoryService.handleHotbarSlotClick(
            astPlayer, HotbarLayout.DB_SLOT_OFFHAND));

        assertTrue(HotbarRenderer.isHotbarDummy(player.getInventory().getItemInOffHand()));
        assertTrue(state.snapshotEntries(hotbar.getInventoryId()).isEmpty());
        List<InventoryEntryModel> returned = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, returned.size());
        assertEquals(instanceId, returned.getFirst().getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: 補助装備をBAGへ返却できない場合、オフハンドと選択状態を変更しない。
     */
    @Test
    void clickingEquippedOffhandWhenBagCannotReceiveLeavesStateUnchanged() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        state.setBagSlotCapacity(0);
        ItemModel auxiliary = auxiliaryWeapon("offhand_return_full_bag_test");
        UUID instanceId = UUID.randomUUID();
        EquipmentInstance instance = DesignTestFixtures.equipmentInstance(
            instanceId, state.getAccountId(), auxiliary.getId(), "ATTACK", "1", "1");
        when(harness.itemService.findLoadedById(auxiliary.getId())).thenReturn(auxiliary);
        when(harness.itemService.findLoadedEquipmentInstanceById(instanceId.toString())).thenReturn(instance);
        ItemStack equipped = new ItemStackFactory(mock(LootService.class), harness.itemService)
            .create(auxiliary, instance, 1);
        player.getInventory().setItemInOffHand(equipped);

        assertFalse(harness.inventoryService.handleHotbarSlotClick(
            astPlayer, HotbarLayout.DB_SLOT_OFFHAND));

        assertEquals(equipped, player.getInventory().getItemInOffHand());
        assertNull(state.getSelectedHotbarSlot());
        assertTrue(state.snapshotEntries(bag.getInventoryId()).isEmpty());
        assertTrue(state.snapshotEntries(hotbar.getInventoryId()).isEmpty());
        assertFalse(state.isDirty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: オフハンドの equipment HOTBAR entry を返却すると、実オフハンドも消去し、BAGに同一個体を一件だけ残す。
     */
    @Test
    void clickingAuxiliaryHotbarEntryReturnsEntryAndClearsMatchingOffhand() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        ItemModel auxiliary = auxiliaryWeapon("offhand_hotbar_return_test");
        UUID instanceId = UUID.randomUUID();
        EquipmentInstance instance = DesignTestFixtures.equipmentInstance(
            instanceId, state.getAccountId(), auxiliary.getId(), "ATTACK", "1", "1");
        when(harness.itemService.findLoadedById(auxiliary.getId())).thenReturn(auxiliary);
        when(harness.itemService.findLoadedEquipmentInstanceById(instanceId.toString())).thenReturn(instance);
        ItemStack equipped = new ItemStackFactory(mock(LootService.class), harness.itemService)
            .create(auxiliary, instance, 1);
        player.getInventory().setItemInOffHand(equipped);
        InventoryEntryModel hotbarEntry = equipmentEntry(
            state.getAccountId(), hotbar.getInventoryId(), HotbarLayout.DB_SLOT_OFFHAND,
            auxiliary.getId(), instanceId);
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(hotbarEntry));

        assertTrue(harness.inventoryService.handleHotbarSlotClick(
            astPlayer, HotbarLayout.DB_SLOT_OFFHAND));

        assertTrue(HotbarRenderer.isHotbarDummy(player.getInventory().getItemInOffHand()));
        assertTrue(state.snapshotEntries(hotbar.getInventoryId()).isEmpty());
        List<InventoryEntryModel> returned = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, returned.size());
        assertEquals(instanceId, returned.getFirst().getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: 旧不正状態の通常item slot 10を返却した場合も、物理オフハンドを残さず二重化を防ぐ。
     */
    @Test
    void clickingLegacyNormalItemInOffhandHotbarEntryDoesNotDuplicateIt() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        ItemModel consumable = DesignTestFixtures.item(
            "legacy_offhand_normal_item_test", ItemCategory.CONSUMABLE, 64);
        when(harness.itemService.findLoadedById(consumable.getId())).thenReturn(consumable);
        ItemStack equipped = new ItemStackFactory(mock(LootService.class), harness.itemService)
            .create(consumable, 1);
        player.getInventory().setItemInOffHand(equipped);
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(
            inventoryEntry(
                state.getAccountId(), hotbar.getInventoryId(), HotbarLayout.DB_SLOT_OFFHAND,
                ItemCategory.CONSUMABLE, consumable.getId(), 1L)
        ));

        assertTrue(harness.inventoryService.handleHotbarSlotClick(
            astPlayer, HotbarLayout.DB_SLOT_OFFHAND));

        assertTrue(HotbarRenderer.isHotbarDummy(player.getInventory().getItemInOffHand()));
        assertTrue(state.snapshotEntries(hotbar.getInventoryId()).isEmpty());
        List<InventoryEntryModel> returned = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, returned.size());
        assertEquals(consumable.getId(), returned.getFirst().getItemId());
        assertEquals(1L, returned.getFirst().getQuantity());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: オフハンド選択中に補助装備以外の道具をクリックしても、旧オフハンド・BAG・選択状態を変更しない。
     */
    @Test
    void selectingOffhandDoesNotAssignToolOrChangeExistingOffhand() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        ItemModel tool = toolEquipment("offhand_tool_rejection_test");
        when(harness.itemService.findLoadedById(tool.getId())).thenReturn(tool);
        InventoryEntryModel toolEntry = inventoryEntry(
            state.getAccountId(), bag.getInventoryId(), 1,
            ItemCategory.EQUIPMENT, tool.getId(), 1L);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(toolEntry));
        state.setSelectedHotbarSlot(HotbarLayout.DB_SLOT_OFFHAND);
        ItemStack existingOffhand = new ItemStack(Material.SHIELD);
        player.getInventory().setItemInOffHand(existingOffhand);

        assertFalse(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(existingOffhand, player.getInventory().getItemInOffHand());
        assertEquals(HotbarLayout.DB_SLOT_OFFHAND, state.getSelectedHotbarSlot());
        assertEquals(1, state.snapshotEntries(bag.getInventoryId()).size());
        assertTrue(state.snapshotEntries(hotbar.getInventoryId()).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 10. ホットバー操作
     * 検証契約: 補助装備ではない武器と通常アイテムも、オフハンド選択中に割り当てず、既存状態を維持する。
     */
    @Test
    void selectingOffhandRejectsWeaponAndNormalItemWithoutMutation() {
        assertOffhandAssignmentRejected(
            weaponEquipment("offhand_weapon_rejection_test"), ItemCategory.EQUIPMENT);
        assertOffhandAssignmentRejected(
            DesignTestFixtures.item("offhand_consumable_rejection_test", ItemCategory.CONSUMABLE, 64),
            ItemCategory.CONSUMABLE);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 11. 装備移動とアクセサリ移動
     * 検証契約: 補助装備交換では旧個体を先にBAGへ返却し、新個体だけをオフハンドへ置く。返却個体・新個体を重複させない。
     */
    @Test
    void replacingAuxiliaryInOffhandReturnsPreviousInstanceBeforeEquippingNewOne() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        harness.addInventory(state, InventoryType.EQUIP_SLOT);
        ItemModel previousAuxiliary = auxiliaryWeapon("offhand_previous_test");
        ItemModel nextAuxiliary = auxiliaryWeapon("offhand_next_test");
        UUID previousInstanceId = UUID.randomUUID();
        UUID nextInstanceId = UUID.randomUUID();
        EquipmentInstance previousInstance = DesignTestFixtures.equipmentInstance(
            previousInstanceId, state.getAccountId(), previousAuxiliary.getId(), "ATTACK", "1", "1");
        EquipmentInstance nextInstance = DesignTestFixtures.equipmentInstance(
            nextInstanceId, state.getAccountId(), nextAuxiliary.getId(), "ATTACK", "1", "1");
        when(harness.itemService.findLoadedById(previousAuxiliary.getId())).thenReturn(previousAuxiliary);
        when(harness.itemService.findLoadedById(nextAuxiliary.getId())).thenReturn(nextAuxiliary);
        when(harness.itemService.findLoadedEquipmentInstanceById(previousInstanceId.toString()))
            .thenReturn(previousInstance);
        when(harness.itemService.findLoadedEquipmentInstanceById(nextInstanceId.toString()))
            .thenReturn(nextInstance);
        ItemStack previousStack = new ItemStackFactory(mock(LootService.class), harness.itemService)
            .create(previousAuxiliary, previousInstance, 1);
        player.getInventory().setItemInOffHand(previousStack);
        InventoryEntryModel nextEntry = equipmentEntry(
            state.getAccountId(), bag.getInventoryId(), 1, nextAuxiliary.getId(), nextInstanceId);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(nextEntry));
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(
            equipmentEntry(
                state.getAccountId(), hotbar.getInventoryId(), HotbarLayout.DB_SLOT_OFFHAND,
                previousAuxiliary.getId(), previousInstanceId)
        ));

        assertTrue(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(nextInstanceId.toString(), ItemStackFactory.getEquipmentInstanceId(
            player.getInventory().getItemInOffHand()));
        List<InventoryEntryModel> bagEntries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, bagEntries.size());
        assertEquals(previousInstanceId, bagEntries.getFirst().getInstanceId());
        assertTrue(bagEntries.stream().noneMatch(entry -> nextInstanceId.equals(entry.getInstanceId())));
        assertTrue(state.snapshotEntries(hotbar.getInventoryId()).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 11. 装備移動とアクセサリ移動
     * 検証契約: BAGが満杯でも、交換元entryを先に解放して旧補助装備を同じslotへ戻し、交換を成功させる。
     */
    @Test
    void replacingAuxiliaryUsesFreedBagSlotWhenBagIsFull() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        harness.addInventory(state, InventoryType.HOTBAR);
        harness.addInventory(state, InventoryType.EQUIP_SLOT);
        state.setBagSlotCapacity(1);
        ItemModel previousAuxiliary = auxiliaryWeapon("offhand_previous_full_bag_test");
        ItemModel nextAuxiliary = auxiliaryWeapon("offhand_next_full_bag_test");
        UUID previousInstanceId = UUID.randomUUID();
        UUID nextInstanceId = UUID.randomUUID();
        EquipmentInstance previousInstance = DesignTestFixtures.equipmentInstance(
            previousInstanceId, state.getAccountId(), previousAuxiliary.getId(), "ATTACK", "1", "1");
        EquipmentInstance nextInstance = DesignTestFixtures.equipmentInstance(
            nextInstanceId, state.getAccountId(), nextAuxiliary.getId(), "ATTACK", "1", "1");
        when(harness.itemService.findLoadedById(previousAuxiliary.getId())).thenReturn(previousAuxiliary);
        when(harness.itemService.findLoadedById(nextAuxiliary.getId())).thenReturn(nextAuxiliary);
        when(harness.itemService.findLoadedEquipmentInstanceById(previousInstanceId.toString()))
            .thenReturn(previousInstance);
        when(harness.itemService.findLoadedEquipmentInstanceById(nextInstanceId.toString()))
            .thenReturn(nextInstance);
        player.getInventory().setItemInOffHand(new ItemStackFactory(mock(LootService.class), harness.itemService)
            .create(previousAuxiliary, previousInstance, 1));
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            equipmentEntry(state.getAccountId(), bag.getInventoryId(), 1, nextAuxiliary.getId(), nextInstanceId)
        ));

        assertTrue(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(nextInstanceId.toString(), ItemStackFactory.getEquipmentInstanceId(
            player.getInventory().getItemInOffHand()));
        List<InventoryEntryModel> bagEntries = state.snapshotEntries(bag.getInventoryId());
        assertEquals(1, bagEntries.size());
        assertEquals(previousInstanceId, bagEntries.getFirst().getInstanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 11. 装備移動とアクセサリ移動
     * 検証契約: 装備元entryを除去した後の旧オフハンド参照返却で失敗しても、交換前の全状態へロールバックする。
     */
    @Test
    void replacingAuxiliaryRollsBackWhenStaleOffhandEntryConsumesFreedBagSlot() {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        harness.addInventory(state, InventoryType.EQUIP_SLOT);
        state.setBagSlotCapacity(1);
        ItemModel previousAuxiliary = auxiliaryWeapon("offhand_previous_rollback_test");
        ItemModel nextAuxiliary = auxiliaryWeapon("offhand_next_rollback_test");
        ItemModel staleAuxiliary = auxiliaryWeapon("offhand_stale_rollback_test");
        UUID previousInstanceId = UUID.randomUUID();
        UUID nextInstanceId = UUID.randomUUID();
        UUID staleInstanceId = UUID.randomUUID();
        EquipmentInstance previousInstance = DesignTestFixtures.equipmentInstance(
            previousInstanceId, state.getAccountId(), previousAuxiliary.getId(), "ATTACK", "1", "1");
        EquipmentInstance nextInstance = DesignTestFixtures.equipmentInstance(
            nextInstanceId, state.getAccountId(), nextAuxiliary.getId(), "ATTACK", "1", "1");
        EquipmentInstance staleInstance = DesignTestFixtures.equipmentInstance(
            staleInstanceId, state.getAccountId(), staleAuxiliary.getId(), "ATTACK", "1", "1");
        when(harness.itemService.findLoadedById(previousAuxiliary.getId())).thenReturn(previousAuxiliary);
        when(harness.itemService.findLoadedById(nextAuxiliary.getId())).thenReturn(nextAuxiliary);
        when(harness.itemService.findLoadedById(staleAuxiliary.getId())).thenReturn(staleAuxiliary);
        when(harness.itemService.findLoadedEquipmentInstanceById(previousInstanceId.toString()))
            .thenReturn(previousInstance);
        when(harness.itemService.findLoadedEquipmentInstanceById(nextInstanceId.toString()))
            .thenReturn(nextInstance);
        when(harness.itemService.findLoadedEquipmentInstanceById(staleInstanceId.toString()))
            .thenReturn(staleInstance);
        ItemStack previousStack = new ItemStackFactory(mock(LootService.class), harness.itemService)
            .create(previousAuxiliary, previousInstance, 1);
        player.getInventory().setItemInOffHand(previousStack);
        InventoryEntryModel nextEntry = equipmentEntry(
            state.getAccountId(), bag.getInventoryId(), 1, nextAuxiliary.getId(), nextInstanceId);
        InventoryEntryModel staleOffhandEntry = equipmentEntry(
            state.getAccountId(), hotbar.getInventoryId(), HotbarLayout.DB_SLOT_OFFHAND,
            staleAuxiliary.getId(), staleInstanceId);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(nextEntry));
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(staleOffhandEntry));
        state.setSelectedHotbarSlot(3);
        assertFalse(state.isDirty());

        assertFalse(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(previousStack, player.getInventory().getItemInOffHand());
        assertEquals(3, state.getSelectedHotbarSlot());
        assertFalse(state.isDirty());
        assertEquals(List.of(nextEntry), state.snapshotEntries(bag.getInventoryId()));
        assertEquals(List.of(staleOffhandEntry), state.snapshotEntries(hotbar.getInventoryId()));
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
        return inventoryEntry(accountId, inventoryId, slot, ItemCategory.MATERIAL, itemId, quantity);
    }

    private static ItemModel auxiliaryWeapon(String id) {
        return equipmentItem(id, ItemEquipmentSlot.SUBWEAPON, "TORCH");
    }

    private static ItemModel toolEquipment(String id) {
        return equipmentItem(id, ItemEquipmentSlot.TOOL, "IRON_PICKAXE");
    }

    private static ItemModel weaponEquipment(String id) {
        return equipmentItem(id, ItemEquipmentSlot.WEAPON, "IRON_SWORD");
    }

    private static ItemModel equipmentItem(String id, ItemEquipmentSlot slot, String material) {
        ItemEquipment equipment = new ItemEquipment(
            slot,
            ItemEquipmentHandType.ONE,
            null,
            0,
            List.of(),
            null,
            List.of(),
            null,
            null,
            null,
            null,
            List.of()
        );
        return new ItemModel(
            1,
            id,
            ItemCategory.EQUIPMENT.getApiValue(),
            id,
            material,
            "common",
            1,
            0,
            null,
            null,
            List.of(),
            false,
            false,
            null,
            null,
            equipment,
            null,
            null,
            null,
            null,
            null
        );
    }

    private void assertOffhandAssignmentRejected(ItemModel sourceModel, ItemCategory category) {
        InventoryHarness harness = inventoryHarness();
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        PlayerInventoryState state = harness.registerState(astPlayer);
        InventoryModel bag = harness.addInventory(state, InventoryType.BAG);
        InventoryModel hotbar = harness.addInventory(state, InventoryType.HOTBAR);
        when(harness.itemService.findLoadedById(sourceModel.getId())).thenReturn(sourceModel);
        InventoryEntryModel sourceEntry = inventoryEntry(
            state.getAccountId(), bag.getInventoryId(), 1, category, sourceModel.getId(), 1L);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(sourceEntry));
        state.setSelectedHotbarSlot(HotbarLayout.DB_SLOT_OFFHAND);
        ItemStack existingOffhand = new ItemStack(Material.SHIELD);
        player.getInventory().setItemInOffHand(existingOffhand);

        assertFalse(harness.inventoryService.equipOrAssignClickedItem(astPlayer, 9));

        assertEquals(existingOffhand, player.getInventory().getItemInOffHand());
        assertEquals(HotbarLayout.DB_SLOT_OFFHAND, state.getSelectedHotbarSlot());
        assertEquals(1, state.snapshotEntries(bag.getInventoryId()).size());
        assertTrue(state.snapshotEntries(hotbar.getInventoryId()).isEmpty());
    }

    private static InventoryEntryModel equipmentEntry(
        UUID accountId,
        UUID inventoryId,
        int slot,
        String itemId,
        UUID instanceId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            inventoryId,
            slot,
            ItemCategory.EQUIPMENT.getApiValue(),
            itemId,
            InventoryInstanceType.EQUIPMENT.getCode(),
            instanceId,
            1L,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private static InventoryEntryModel inventoryEntry(
        UUID accountId,
        UUID inventoryId,
        int slot,
        ItemCategory category,
        String itemId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            inventoryId,
            slot,
            category.getApiValue(),
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

    private static InventoryEntryModel inventoryEntryWithId(
        UUID entryId,
        UUID accountId,
        UUID inventoryId,
        Integer slot,
        String itemId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
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

    private static InventoryEntryModel entryAt(List<InventoryEntryModel> entries, int slot) {
        return entries.stream()
            .filter(entry -> Integer.valueOf(slot).equals(entry.getSlotIndex()))
            .findFirst()
            .orElseThrow();
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
