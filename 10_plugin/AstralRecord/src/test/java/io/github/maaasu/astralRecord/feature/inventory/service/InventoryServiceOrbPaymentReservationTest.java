package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class InventoryServiceOrbPaymentReservationTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: 起点オーブ1個を予約中に同itemの別stackが1個あればローカル消費は別stackだけを減らし、API tombstoneとの三者マージを負数にしない。
     */
    @Test
    void originOrbReservationConsumesOnlyOtherStackAndTombstoneMergeStaysNonNegative() {
        Harness harness = harness(false);
        UUID originId = UUID.randomUUID();
        UUID otherStackId = UUID.randomUUID();
        InventoryEntryModel origin = entry(
            originId, harness, 1, ItemCategory.ORB, "orb.weapon_tyr", 1L);
        InventoryEntryModel other = entry(
            otherStackId, harness, 2, ItemCategory.ORB, "orb.weapon_tyr", 1L);
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(origin, other));
        UUID operationId = UUID.randomUUID();
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(), Map.of(harness.bag.getInventoryId(), List.of(origin, other)));

        assertTrue(harness.service.reserveOrbOperationPayment(
            harness.accountId(), operationId, originId, Map.of("orb.weapon_tyr", 1L), 0L));
        assertTrue(harness.service.finalizeOrbOperationPaymentReservation(
            harness.accountId(), operationId, baseline));
        assertTrue(harness.service.consumeNormalItem(
            harness.accountId(), "orb.weapon_tyr", 1L));

        List<InventoryEntryModel> afterLocalConsume = harness.state.snapshotEntries(
            harness.bag.getInventoryId());
        assertEquals(1, afterLocalConsume.size());
        assertEquals(originId, afterLocalConsume.getFirst().getInventoryEntryId());
        assertEquals(1L, afterLocalConsume.getFirst().getQuantity());

        when(harness.repository.findEntryById(originId)).thenReturn(null);
        harness.service.reconcileOrbOperationEntries(
            harness.accountId(),
            List.of(originId),
            baseline
        );
        assertTrue(harness.state.snapshotEntries(harness.bag.getInventoryId()).isEmpty());
        harness.service.releaseOrbOperationPayment(harness.accountId(), operationId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: baseline1個を全予約中の追加消費は失敗するが、通常付与とprepared rewardは拒否せず同stackの予約外数量として加算する。
     */
    @Test
    void fullyReservedStackBlocksConsumeButAllowsAddAndPreparedReward() {
        Harness harness = harness(false);
        UUID originId = UUID.randomUUID();
        InventoryEntryModel origin = entry(
            originId, harness, 2, ItemCategory.ORB, "orb.weapon_tyr", 1L);
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(origin));
        UUID operationId = UUID.randomUUID();
        ItemModel orb = DesignTestFixtures.item("orb.weapon_tyr", ItemCategory.ORB, 1);
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(), Map.of(harness.bag.getInventoryId(), List.of(origin)));

        assertTrue(harness.service.reserveOrbOperationPayment(
            harness.accountId(), operationId, originId, Map.of("orb.weapon_tyr", 1L), 0L));
        assertFalse(harness.service.consumeNormalItem(
            harness.accountId(), "orb.weapon_tyr", 1L));
        assertEquals(1, harness.service.addItemToNormalInventory(
            harness.player, orb, 1, "reservation-test"));
        InventoryService.InventoryGrantReceipt receipt =
            harness.service.addPreparedRewardsToNormalInventory(
                harness.player,
                List.of(new InventoryService.PreparedInventoryReward(orb, 1, List.of()))
            );

        assertNotNull(receipt);
        assertEquals(3L, harness.service.getNormalItemAmount(
            harness.accountId(), "orb.weapon_tyr"));
        assertFalse(harness.service.consumeNormalItem(
            harness.accountId(), "orb.weapon_tyr", 1L));
        assertTrue(harness.service.finalizeOrbOperationPaymentReservation(
            harness.accountId(),
            operationId,
            baseline
        ));
        assertTrue(harness.service.consumeNormalItem(
            harness.accountId(), "orb.weapon_tyr", 1L));
        assertEquals(2L, harness.service.getNormalItemAmount(
            harness.accountId(), "orb.weapon_tyr"));
        assertEquals(1L, harness.service.findOwnedEntry(
            harness.accountId(), originId).getQuantity());
        when(harness.repository.findEntryById(originId)).thenReturn(null);
        harness.service.reconcileOrbOperationEntries(
            harness.accountId(), List.of(originId), baseline);
        assertEquals(1L, harness.service.getNormalItemAmount(
            harness.accountId(), "orb.weapon_tyr"));
        assertFalse(harness.state.snapshotEntries(harness.bag.getInventoryId()).stream()
            .anyMatch(entry -> entry.getInventoryEntryId().equals(originId)));
        harness.service.releaseOrbOperationPayment(harness.accountId(), operationId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: 素材予約はslot/entry単位で割り当て、複数stackに余剰がある場合のローカル消費は予約済み数量を避けて未予約分だけを一度減らす。
     */
    @Test
    void materialReservationAcrossStacksConsumesOnlyUnreservedQuantity() {
        Harness harness = harness(false);
        UUID originId = UUID.randomUUID();
        UUID firstMaterialId = UUID.randomUUID();
        UUID secondMaterialId = UUID.randomUUID();
        List<InventoryEntryModel> baselineEntries = List.of(
            entry(originId, harness, 1, ItemCategory.ORB, "orb.transition", 1L),
            entry(firstMaterialId, harness, 2, ItemCategory.MATERIAL, "material.rune", 1L),
            entry(secondMaterialId, harness, 3, ItemCategory.MATERIAL, "material.rune", 2L)
        );
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), baselineEntries);
        UUID operationId = UUID.randomUUID();

        assertTrue(harness.service.reserveOrbOperationPayment(
            harness.accountId(),
            operationId,
            originId,
            Map.of("orb.transition", 1L, "material.rune", 2L),
            0L
        ));
        assertTrue(harness.service.finalizeOrbOperationPaymentReservation(
            harness.accountId(),
            operationId,
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(), Map.of(harness.bag.getInventoryId(), baselineEntries))
        ));
        assertTrue(harness.service.consumeNormalItem(
            harness.accountId(), "material.rune", 1L));

        Map<UUID, Long> quantities = harness.state.snapshotEntries(harness.bag.getInventoryId()).stream()
            .collect(java.util.stream.Collectors.toMap(
                InventoryEntryModel::getInventoryEntryId,
                InventoryEntryModel::getQuantity
            ));
        assertEquals(1L, quantities.get(firstMaterialId));
        assertEquals(1L, quantities.get(secondMaterialId));
        assertFalse(harness.service.consumeNormalItem(
            harness.accountId(), "material.rune", 1L));
        when(harness.repository.findEntryById(firstMaterialId)).thenReturn(null);
        when(harness.repository.findEntryById(secondMaterialId)).thenReturn(entry(
            secondMaterialId,
            harness,
            3,
            ItemCategory.MATERIAL,
            "material.rune",
            1L
        ));
        harness.service.reconcileOrbOperationEntries(
            harness.accountId(),
            List.of(firstMaterialId, secondMaterialId),
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(), Map.of(harness.bag.getInventoryId(), baselineEntries))
        );
        assertEquals(0L, harness.service.getNormalItemAmount(
            harness.accountId(), "material.rune"));
        harness.service.releaseOrbOperationPayment(harness.accountId(), operationId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: baseline3個からAPI予約1個と待機中ローカル消費1個を独立して保持し、authoritative2+(current2-baseline3)=1として合計2個だけを消費する。
     */
    @Test
    void baselineThreePreservesOneLocalConsumeAlongsideOneApiConsume() {
        Harness harness = harness(false);
        UUID originId = UUID.randomUUID();
        InventoryEntryModel baselineOrigin = entry(
            originId, harness, 1, ItemCategory.ORB, "orb.weapon_tyr", 3L);
        harness.state.replaceEntriesFromLoad(
            harness.bag.getInventoryId(), List.of(baselineOrigin));
        UUID operationId = UUID.randomUUID();

        assertTrue(harness.service.reserveOrbOperationPayment(
            harness.accountId(), operationId, originId, Map.of("orb.weapon_tyr", 1L), 0L));
        assertTrue(harness.service.finalizeOrbOperationPaymentReservation(
            harness.accountId(),
            operationId,
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(),
                Map.of(harness.bag.getInventoryId(), List.of(baselineOrigin))
            )
        ));
        assertTrue(harness.service.consumeNormalItem(
            harness.accountId(), "orb.weapon_tyr", 1L));
        when(harness.repository.findEntryById(originId)).thenReturn(entry(
            originId, harness, 1, ItemCategory.ORB, "orb.weapon_tyr", 2L));

        harness.service.reconcileOrbOperationEntries(
            harness.accountId(),
            List.of(originId),
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(),
                Map.of(harness.bag.getInventoryId(), List.of(baselineOrigin))
            )
        );

        assertEquals(1L, harness.service.findOwnedEntry(
            harness.accountId(), originId).getQuantity());
        harness.service.releaseOrbOperationPayment(harness.accountId(), operationId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: gold100のうち60をAPI支払い予約中は追加消費50を拒否し30は許可し、同時gold加算は予約で拒否されず総残高へ反映する。
     */
    @Test
    void goldReservationBlocksOverspendButAllowsSafeConsumeAndAddition() {
        Harness harness = harness(true);
        UUID originId = UUID.randomUUID();
        InventoryEntryModel origin = entry(
            originId, harness, 1, ItemCategory.ORB, "orb.transition", 1L);
        InventoryEntryModel gold = currencyEntry(
            harness, GoldDenomination.GOLD_INGOT.itemId(), 1L);
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(origin));
        harness.state.replaceEntriesFromLoad(harness.currency.getInventoryId(), List.of(gold));
        UUID operationId = UUID.randomUUID();

        assertTrue(harness.service.reserveOrbOperationPayment(
            harness.accountId(), operationId, originId, Map.of("orb.transition", 1L), 60L));
        assertTrue(harness.service.finalizeOrbOperationPaymentReservation(
            harness.accountId(),
            operationId,
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(),
                Map.of(
                    harness.bag.getInventoryId(), List.of(origin),
                    harness.currency.getInventoryId(), List.of(gold)
                )
            )
        ));
        assertFalse(harness.service.consumeCurrency(
            harness.accountId(), GoldDenomination.GOLD_INGOT.itemId(), 1L));
        assertFalse(harness.service.consumeGold(harness.accountId(), 50L));
        assertTrue(harness.service.consumeGold(harness.accountId(), 30L));
        assertEquals(70L, harness.service.getGoldAmount(harness.accountId()));
        assertTrue(harness.service.addGold(harness.player, 20L));
        assertEquals(90L, harness.service.getGoldAmount(harness.accountId()));
        assertFalse(harness.service.consumeCurrency(
            harness.accountId(), GoldDenomination.GOLD_COIN.itemId(), 4L));
        assertTrue(harness.service.consumeCurrency(
            harness.accountId(), GoldDenomination.GOLD_COIN.itemId(), 3L));
        assertEquals(60L, harness.service.getGoldAmount(harness.accountId()));
        harness.service.releaseOrbOperationPayment(harness.accountId(), operationId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: gold total予約は全canonical額面とlegacy ast_goldの直接consumeCurrency経路にも適用し、額面変更で予約価値を迂回できない。
     */
    @Test
    void everyGoldDenominationAndLegacyRespectReservedTotalValue() {
        for (GoldDenomination denomination : GoldDenomination.values()) {
            assertDirectGoldSpendBlockedByReservation(
                denomination.itemId(), denomination.goldValue());
        }
        assertDirectGoldSpendBlockedByReservation(
            ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID, 1L);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 2. 通常インベントリアイテム追加
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: addがused-slot snapshot取得後に停止している間にtombstone reconcileが到達しても、同じstate monitorで直列化し、compact後のslotを重複させない。
     */
    @Test
    void addUsedSlotSnapshotAndReconcileCompactionNeverCreateDuplicateSlots() throws Exception {
        Harness harness = harness(false);
        UUID consumedId = UUID.randomUUID();
        InventoryEntryModel consumed = entry(
            consumedId, harness, 1, ItemCategory.ORB, "orb.weapon_tyr", 1L);
        InventoryEntryModel retained = entry(
            UUID.randomUUID(), harness, 3, ItemCategory.MATERIAL, "material.retained", 1L);
        harness.state.replaceEntriesFromLoad(
            harness.bag.getInventoryId(), List.of(consumed, retained));
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(),
                Map.of(harness.bag.getInventoryId(), List.of(consumed, retained))
            );
        CountDownLatch addSnapshotCaptured = new CountDownLatch(1);
        CountDownLatch releaseAdd = new CountDownLatch(1);
        CountDownLatch reconcileFetchReached = new CountDownLatch(1);
        ItemModel reward = mock(ItemModel.class);
        when(reward.getCategory()).thenReturn(ItemCategory.MATERIAL.getApiValue());
        when(reward.getId()).thenReturn("material.new_reward");
        when(reward.getMaxStack()).thenAnswer(invocation -> {
            addSnapshotCaptured.countDown();
            assertTrue(releaseAdd.await(2, TimeUnit.SECONDS));
            return 1;
        });
        when(harness.repository.findEntryById(consumedId)).thenAnswer(invocation -> {
            reconcileFetchReached.countDown();
            return null;
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            var add = workers.submit(() -> harness.service.addItemToNormalInventory(
                harness.player, reward, 1, "reservation-race"));
            assertTrue(addSnapshotCaptured.await(2, TimeUnit.SECONDS));
            var reconcile = workers.submit(() -> harness.service.reconcileOrbOperationEntries(
                harness.accountId(), List.of(consumedId), baseline));
            assertFalse(reconcileFetchReached.await(150, TimeUnit.MILLISECONDS));

            releaseAdd.countDown();
            assertTrue(reconcileFetchReached.await(2, TimeUnit.SECONDS));
            assertEquals(1, add.get(2, TimeUnit.SECONDS));
            reconcile.get(2, TimeUnit.SECONDS);

            List<InventoryEntryModel> merged = harness.state.snapshotEntries(
                harness.bag.getInventoryId());
            assertEquals(2, merged.size());
            assertEquals(2L, merged.stream()
                .map(InventoryEntryModel::getSlotIndex)
                .distinct()
                .count());
            assertEquals(List.of(1, 2), merged.stream()
                .map(InventoryEntryModel::getSlotIndex)
                .sorted()
                .toList());
        } finally {
            releaseAdd.countDown();
            workers.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: initial予約からstable pre-save確定までの加算は許可し、保存中変更を検出した再保存baselineへ含め、確定前consumeは停止・確定後は予約外rewardだけを消費可能にする。
     */
    @Test
    void rewardDuringPreSaveIsRetriedIntoBaselineBeforeUnreservedConsume() throws Exception {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        UUID accountId = player.getAccount().getUuid();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        state.putInventory(bag);
        UUID originId = UUID.randomUUID();
        InventoryEntryModel origin = standaloneEntry(
            originId,
            accountId,
            bag.getInventoryId(),
            2,
            ItemCategory.ORB,
            "orb.weapon_tyr",
            1L
        );
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(origin));
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        CountDownLatch firstSaveStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        AtomicInteger preSaveCalls = new AtomicInteger();
        when(persistence.saveNowWithBaseline(state)).thenAnswer(invocation -> {
            int call = preSaveCalls.incrementAndGet();
            state.takeAndClearDirty();
            if (call == 1) {
                firstSaveStarted.countDown();
                assertTrue(releaseFirstSave.await(2, TimeUnit.SECONDS));
                return null;
            }
            List<InventoryEntryModel> persisted = state.snapshotEntries(bag.getInventoryId());
            state.takeAndClearDirty();
            return new InventoryPersistence.PersistedInventoryBaseline(
                accountId, Map.of(bag.getInventoryId(), persisted));
        });
        when(persistence.saveNow(state)).thenAnswer(invocation -> {
            state.takeAndClearDirty();
            return true;
        });
        when(persistence.hasPendingChanges(state)).thenAnswer(invocation -> state.isDirty());
        ExecutorService laneExecutor = Executors.newSingleThreadExecutor();
        InventorySaveCoordinator coordinator = new InventorySaveCoordinator(
            persistence, registry, laneExecutor);
        InventoryService service = new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            registry,
            persistence,
            coordinator
        );
        UUID operationId = UUID.randomUUID();
        ItemModel orb = DesignTestFixtures.item("orb.weapon_tyr", ItemCategory.ORB, 1);

        assertTrue(service.reserveOrbOperationPayment(
            accountId, operationId, originId, Map.of("orb.weapon_tyr", 1L), 0L));
        try (MockedStatic<Logger> ignored = mockStatic(Logger.class)) {
            var operation = coordinator.executeExclusiveAfterSave(accountId, baseline -> {
                assertTrue(service.finalizeOrbOperationPaymentReservation(
                    accountId, operationId, baseline));
                assertTrue(service.consumeNormalItem(accountId, "orb.weapon_tyr", 1L));
                service.releaseOrbOperationPayment(accountId, operationId);
                return baseline;
            });
            assertTrue(firstSaveStarted.await(2, TimeUnit.SECONDS));

            assertEquals(1, service.addItemToNormalInventory(
                player, orb, 1, "pre-save-reward"));
            assertFalse(service.consumeNormalItem(accountId, "orb.weapon_tyr", 1L));
            releaseFirstSave.countDown();

            InventoryPersistence.PersistedInventoryBaseline stable =
                operation.get(3, TimeUnit.SECONDS);
            assertEquals(2, preSaveCalls.get());
            assertEquals(2, stable.entriesByInventoryId().get(bag.getInventoryId()).size());
            assertEquals(1L, service.getNormalItemAmount(accountId, "orb.weapon_tyr"));
            assertEquals(originId, state.snapshotEntries(bag.getInventoryId())
                .getFirst().getInventoryEntryId());
        } finally {
            releaseFirstSave.countDown();
            laneExecutor.shutdownNow();
        }
    }

    private Harness harness(boolean withCurrency) {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        UUID accountId = player.getAccount().getUuid();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        state.putInventory(bag);
        InventoryModel currency = null;
        if (withCurrency) {
            currency = DesignTestFixtures.inventory(accountId, InventoryType.CURRENCY, 27);
            state.putInventory(currency);
        }
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        InventoryRepository repository = mock(InventoryRepository.class);
        ItemService itemService = mock(ItemService.class);
        when(itemService.loadItem(anyString())).thenAnswer(invocation -> DesignTestFixtures.item(
            invocation.getArgument(0, String.class), ItemCategory.CURRENCY, 64));
        InventoryService service = new InventoryService(
            repository,
            mock(EquipmentLoadoutRepository.class),
            itemService,
            mock(ItemStackFactory.class),
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );
        return new Harness(accountId, player, state, bag, currency, repository, service);
    }

    private void assertDirectGoldSpendBlockedByReservation(String itemId, long unitValue) {
        Harness harness = harness(true);
        UUID originId = UUID.randomUUID();
        InventoryEntryModel origin = entry(
            originId, harness, 1, ItemCategory.ORB, "orb.transition", 1L);
        InventoryEntryModel gold = currencyEntry(harness, itemId, 1L);
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(origin));
        harness.state.replaceEntriesFromLoad(harness.currency.getInventoryId(), List.of(gold));
        UUID operationId = UUID.randomUUID();
        InventoryPersistence.PersistedInventoryBaseline baseline =
            new InventoryPersistence.PersistedInventoryBaseline(
                harness.accountId(),
                Map.of(
                    harness.bag.getInventoryId(), List.of(origin),
                    harness.currency.getInventoryId(), List.of(gold)
                )
            );

        assertTrue(harness.service.reserveOrbOperationPayment(
            harness.accountId(),
            operationId,
            originId,
            Map.of("orb.transition", 1L),
            unitValue
        ));
        assertTrue(harness.service.finalizeOrbOperationPaymentReservation(
            harness.accountId(), operationId, baseline));
        assertFalse(harness.service.consumeCurrency(harness.accountId(), itemId, 1L), itemId);
        assertEquals(unitValue, harness.service.getGoldAmount(harness.accountId()), itemId);
        harness.service.releaseOrbOperationPayment(harness.accountId(), operationId);
    }

    private static InventoryEntryModel entry(
        UUID entryId,
        Harness harness,
        int slot,
        ItemCategory category,
        String itemId,
        long quantity
    ) {
        return standaloneEntry(
            entryId,
            harness.accountId(),
            harness.bag.getInventoryId(),
            slot,
            category,
            itemId,
            quantity
        );
    }

    private static InventoryEntryModel standaloneEntry(
        UUID entryId,
        UUID accountId,
        UUID inventoryId,
        int slot,
        ItemCategory category,
        String itemId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
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

    private static InventoryEntryModel currencyEntry(
        Harness harness,
        String itemId,
        long quantity
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(),
            harness.currency.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            ItemCategory.CURRENCY.getApiValue(),
            itemId,
            null,
            null,
            quantity,
            null,
            now,
            now,
            harness.accountId(),
            harness.accountId(),
            false
        );
    }

    private record Harness(
        UUID accountId,
        AstPlayer player,
        PlayerInventoryState state,
        InventoryModel bag,
        InventoryModel currency,
        InventoryRepository repository,
        InventoryService service
    ) {
    }
}
