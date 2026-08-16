package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutSlotModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceOrbReconciliationTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_0-概要.md
     * 章・見出し: # 22_0-概要 > ## 責務
     * 検証契約: 受取 capacity 判定の仮差引は全量送付 entry を除去し、部分送付 entry は残量だけを保持する。
     */
    @Test
    void capacityCheckSubtractsFullAndPartialOutgoingReservationsFromAuthoritativeState() {
        Harness harness = harness();
        UUID fullTransferId = UUID.randomUUID();
        UUID partialTransferId = UUID.randomUUID();
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(
            entry(
                fullTransferId,
                harness.accountId,
                harness.bag.getInventoryId(),
                NormalInventoryLayout.DB_SLOT_START,
                "trade.full",
                5L
            ),
            entry(
                partialTransferId,
                harness.accountId,
                harness.bag.getInventoryId(),
                NormalInventoryLayout.DB_SLOT_START + 1,
                "trade.partial",
                5L
            )
        ));
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(harness.accountId);

        boolean subtracted = harness.service.removeOwnedEntryAmountsForCapacityCheck(
            player,
            Map.of(fullTransferId, 5L, partialTransferId, 3L)
        );

        assertTrue(subtracted);
        List<InventoryEntryModel> simulated = harness.state.snapshotEntries(harness.bag.getInventoryId());
        assertFalse(simulated.stream().anyMatch(entry -> entry.getInventoryEntryId().equals(fullTransferId)));
        assertEquals(2L, simulated.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(partialTransferId))
            .findFirst()
            .orElseThrow()
            .getQuantity());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: 保存済み1個をAPIが全消費する正本GET待機中に同stackへ1個追加しても、削除済みIDを復活させず残差1個を新IDで保持し、同時追加した無関係entryも失わない。
     */
    @Test
    void tombstoneFetchLatchKeepsConcurrentStackAdditionAndUnrelatedNewEntry() throws Exception {
        Harness harness = harness();
        InventoryEntryModel baselineOrb = entry(
            harness.orbEntryId,
            harness.accountId,
            harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            "orb.weapon_tyr",
            1L
        );
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(baselineOrb));
        InventoryPersistence.PersistedInventoryBaseline baseline = baseline(
            harness.accountId, harness.bag.getInventoryId(), List.of(baselineOrb));
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        when(harness.repository.findEntryById(harness.orbEntryId)).thenAnswer(invocation -> {
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(2, TimeUnit.SECONDS));
            return null;
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        UUID rewardEntryId = UUID.randomUUID();

        try {
            var reconciliation = worker.submit(() -> harness.service.reconcileOrbOperationEntries(
                harness.accountId,
                List.of(harness.orbEntryId),
                baseline
            ));
            assertTrue(fetchStarted.await(2, TimeUnit.SECONDS));

            harness.state.replaceEntries(harness.bag.getInventoryId(), List.of(
                entry(
                    harness.orbEntryId,
                    harness.accountId,
                    harness.bag.getInventoryId(),
                    NormalInventoryLayout.DB_SLOT_START,
                    "orb.weapon_tyr",
                    2L
                ),
                entry(
                    rewardEntryId,
                    harness.accountId,
                    harness.bag.getInventoryId(),
                    NormalInventoryLayout.DB_SLOT_START + 1,
                    "reward.drop",
                    4L
                )
            ));
            releaseFetch.countDown();
            reconciliation.get(2, TimeUnit.SECONDS);

            List<InventoryEntryModel> merged = harness.state.snapshotEntries(
                harness.bag.getInventoryId());
            assertFalse(merged.stream().anyMatch(entry ->
                entry.getInventoryEntryId().equals(harness.orbEntryId)));
            InventoryEntryModel retainedOrb = merged.stream()
                .filter(entry -> "orb.weapon_tyr".equals(entry.getItemId()))
                .findFirst()
                .orElseThrow();
            assertFalse(retainedOrb.getInventoryEntryId().equals(harness.orbEntryId));
            assertEquals(1L, retainedOrb.getQuantity());
            assertTrue(merged.stream().anyMatch(entry ->
                entry.getInventoryEntryId().equals(rewardEntryId)
                    && entry.getQuantity() == 4L));
        } finally {
            releaseFetch.countDown();
            worker.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: APIが素材5個から3個を消費した正本GET待機中に同stackを1個消費した場合、authoritative 2 + local差分 -1 = 1を一度だけ反映する。
     */
    @Test
    void authoritativeFetchLatchKeepsConcurrentSameStackConsumeExactlyOnce() throws Exception {
        Harness harness = harness();
        UUID materialEntryId = UUID.randomUUID();
        InventoryEntryModel baselineMaterial = entry(
            materialEntryId,
            harness.accountId,
            harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            "material.rune",
            5L
        );
        InventoryEntryModel authoritativeMaterial = entry(
            materialEntryId,
            harness.accountId,
            harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            "material.rune",
            2L
        );
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(baselineMaterial));
        InventoryPersistence.PersistedInventoryBaseline baseline = baseline(
            harness.accountId, harness.bag.getInventoryId(), List.of(baselineMaterial));
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        when(harness.repository.findEntryById(materialEntryId)).thenAnswer(invocation -> {
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(2, TimeUnit.SECONDS));
            return authoritativeMaterial;
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            var reconciliation = worker.submit(() -> harness.service.reconcileOrbOperationEntries(
                harness.accountId,
                List.of(materialEntryId),
                baseline
            ));
            assertTrue(fetchStarted.await(2, TimeUnit.SECONDS));
            harness.state.replaceEntries(harness.bag.getInventoryId(), List.of(entry(
                materialEntryId,
                harness.accountId,
                harness.bag.getInventoryId(),
                NormalInventoryLayout.DB_SLOT_START,
                "material.rune",
                4L
            )));
            releaseFetch.countDown();
            reconciliation.get(2, TimeUnit.SECONDS);

            InventoryEntryModel merged = harness.state.snapshotEntries(
                harness.bag.getInventoryId()).getFirst();
            assertEquals(materialEntryId, merged.getInventoryEntryId());
            assertEquals(1L, merged.getQuantity());
        } finally {
            releaseFetch.countDown();
            worker.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: API正本gold取得待機中の加算30・消費10を額面行ではなく総価値差分+20として保持し、API残高60と合わせた80をcanonical額面へ再構成する。
     */
    @Test
    void currencyFetchLatchPreservesConcurrentGoldAddAndConsumeByTotalValue() throws Exception {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel currency = DesignTestFixtures.inventory(
            accountId, InventoryType.CURRENCY, null);
        state.putInventory(currency);
        UUID baselineGoldId = UUID.randomUUID();
        UUID localGoldId = UUID.randomUUID();
        UUID authoritativeGoldId = UUID.randomUUID();
        InventoryEntryModel baselineGold = categoryEntry(
            baselineGoldId,
            accountId,
            currency.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            ItemCategory.CURRENCY,
            GoldDenomination.GOLD_INGOT.itemId(),
            1L
        );
        InventoryEntryModel authoritativeGold = categoryEntry(
            authoritativeGoldId,
            accountId,
            currency.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            ItemCategory.CURRENCY,
            GoldDenomination.GOLD_COIN.itemId(),
            6L
        );
        state.replaceEntriesFromLoad(currency.getInventoryId(), List.of(baselineGold));
        registry.put(state);
        InventoryRepository repository = mock(InventoryRepository.class);
        InventoryService service = new InventoryService(
            repository,
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        when(repository.findEntries(currency.getInventoryId())).thenAnswer(invocation -> {
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(2, TimeUnit.SECONDS));
            return List.of(authoritativeGold);
        });
        when(repository.findEntryById(baselineGoldId)).thenReturn(null);
        InventoryPersistence.PersistedInventoryBaseline baseline = baseline(
            accountId, currency.getInventoryId(), List.of(baselineGold));
        ExecutorService worker = Executors.newSingleThreadExecutor();

        try {
            var reconciliation = worker.submit(() -> service.reconcileOrbOperationEntries(
                accountId,
                List.of(baselineGoldId),
                baseline
            ));
            assertTrue(fetchStarted.await(2, TimeUnit.SECONDS));

            // API待機中の報酬+30、その直後の購入-10を同じlocal stateへ順に反映する。
            state.replaceEntries(currency.getInventoryId(), List.of(
                baselineGold,
                categoryEntry(
                    localGoldId,
                    accountId,
                    currency.getInventoryId(),
                    NormalInventoryLayout.DB_SLOT_START + 1,
                    ItemCategory.CURRENCY,
                    GoldDenomination.GOLD_COIN.itemId(),
                    3L
                )
            ));
            state.replaceEntries(currency.getInventoryId(), List.of(
                baselineGold,
                categoryEntry(
                    localGoldId,
                    accountId,
                    currency.getInventoryId(),
                    NormalInventoryLayout.DB_SLOT_START + 1,
                    ItemCategory.CURRENCY,
                    GoldDenomination.GOLD_COIN.itemId(),
                    2L
                )
            ));
            releaseFetch.countDown();
            reconciliation.get(2, TimeUnit.SECONDS);

            List<InventoryEntryModel> merged = state.snapshotEntries(currency.getInventoryId());
            assertEquals(80L, totalGoldValue(merged));
            assertEquals(1, merged.size());
            assertEquals(GoldDenomination.GOLD_COIN.itemId(), merged.getFirst().getItemId());
            assertEquals(8L, merged.getFirst().getQuantity());
        } finally {
            releaseFetch.countDown();
            worker.shutdownNow();
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: APIが1個消費を確定するまではローカル数量2を維持し、affected entryの正本照合後だけAPI数量1へ置き換える。
     */
    @Test
    void authoritativeAffectedEntryIsTheOnlySourceOfOrbConsumption() {
        Harness harness = harness();
        InventoryEntryModel authoritative = entry(
            harness.orbEntryId,
            harness.accountId,
            harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            "orb.weapon_tyr",
            1L
        );
        when(harness.repository.findEntryById(harness.orbEntryId)).thenReturn(authoritative);

        assertEquals(2L, harness.service.findOwnedEntry(
            harness.accountId, harness.orbEntryId).getQuantity());

        harness.service.reconcileAuthoritativeEntry(harness.accountId, harness.orbEntryId);

        InventoryEntryModel reconciled = harness.service.findOwnedEntry(
            harness.accountId, harness.orbEntryId);
        assertNotNull(reconciled);
        assertEquals(1L, reconciled.getQuantity());
        verify(harness.repository).findEntryById(harness.orbEntryId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 15.1. オーブ操作の保存laneとAPI正本照合
     * 検証契約: BAG内entryの数量だけが減少した場合、疎なslot配置を前詰めしない。
     */
    @Test
    void partialAffectedQuantityDecreasePreservesSparseBagSlots() {
        Harness harness = harness(InventoryType.BAG);
        UUID unrelatedEntryId = UUID.randomUUID();
        InventoryEntryModel baselineOrb = entry(
            harness.orbEntryId, harness.accountId, harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START + 2, "orb.weapon_tyr", 3L);
        InventoryEntryModel unrelated = entry(
            unrelatedEntryId, harness.accountId, harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START + 8, "unrelated_material", 7L);
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(
            baselineOrb, unrelated));
        when(harness.repository.findEntryById(harness.orbEntryId)).thenReturn(entry(
            harness.orbEntryId, harness.accountId, harness.bag.getInventoryId(),
            baselineOrb.getSlotIndex(), "orb.weapon_tyr", 2L));

        harness.service.reconcileOrbOperationEntries(
            harness.accountId,
            List.of(harness.orbEntryId),
            baseline(harness.accountId, harness.bag.getInventoryId(), List.of(
                baselineOrb, unrelated))
        );

        assertEquals(baselineOrb.getSlotIndex(), harness.service.findOwnedEntry(
            harness.accountId, harness.orbEntryId).getSlotIndex());
        assertEquals(unrelated.getSlotIndex(), harness.service.findOwnedEntry(
            harness.accountId, unrelatedEntryId).getSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_0-概要.md
     * 章・見出し: # 22_0-概要 > ## 責務
     * 検証契約: Trade API が未配置で返す通常 item・装備・ルーンの受取 BAG entry は、再同期後に重複しない有効 slot を持ち、後続保存で表示可能な state になる。
     */
    @Test
    void tradeRecipientEntriesWithoutApiSlotsAreAssignedBagSlotsDuringReconciliation() {
        Harness harness = harness(InventoryType.BAG);
        UUID retainedEntryId = UUID.randomUUID();
        UUID materialEntryId = UUID.randomUUID();
        UUID equipmentEntryId = UUID.randomUUID();
        UUID runeEntryId = UUID.randomUUID();
        InventoryEntryModel retained = entry(
            retainedEntryId,
            harness.accountId,
            harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            "retained_material",
            1L
        );
        InventoryEntryModel material = categoryEntry(
            materialEntryId,
            harness.accountId,
            harness.bag.getInventoryId(),
            null,
            ItemCategory.MATERIAL,
            "trade_material",
            3L
        );
        InventoryEntryModel equipment = instanceEntry(
            equipmentEntryId,
            harness.accountId,
            harness.bag.getInventoryId(),
            ItemCategory.EQUIPMENT,
            "EQUIPMENT"
        );
        InventoryEntryModel rune = instanceEntry(
            runeEntryId,
            harness.accountId,
            harness.bag.getInventoryId(),
            ItemCategory.RUNE,
            "RUNE"
        );
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(retained));
        when(harness.repository.findEntryById(materialEntryId)).thenReturn(material);
        when(harness.repository.findEntryById(equipmentEntryId)).thenReturn(equipment);
        when(harness.repository.findEntryById(runeEntryId)).thenReturn(rune);

        harness.service.reconcileExternalInventoryEntries(
            harness.accountId,
            List.of(materialEntryId, equipmentEntryId, runeEntryId),
            baseline(harness.accountId, harness.bag.getInventoryId(), List.of(retained))
        );

        List<InventoryEntryModel> entries = harness.state.snapshotEntries(harness.bag.getInventoryId());
        AtomicReference<List<InventoryEntryModel>> savedEntries = new AtomicReference<>();
        when(harness.persistence.saveNow(harness.state)).thenAnswer(invocation -> {
            savedEntries.set(List.copyOf(harness.state.snapshotEntries(harness.bag.getInventoryId())));
            return true;
        });
        assertTrue(harness.service.persistReconciledStateNow(harness.accountId));

        assertEquals(4, entries.size());
        assertEquals(4, entries.stream()
            .map(InventoryEntryModel::getSlotIndex)
            .filter(slot -> slot != null && NormalInventoryLayout.isManagedSlot(slot, 27))
            .distinct()
            .count());
        assertNotNull(savedEntries.get());
        assertTrue(savedEntries.get().stream().allMatch(entry ->
            entry.getSlotIndex() != null && NormalInventoryLayout.isManagedSlot(entry.getSlotIndex(), 27)));
        assertEquals(NormalInventoryLayout.DB_SLOT_START, harness.service.findOwnedEntry(
            harness.accountId, retainedEntryId).getSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 5. サーバー内 GUI の出品・購入 > ### 処理要点
     * 検証契約: HOTBAR出品元の元slotが再利用された取り下げで、APIがBAGへ返した未配置entryを有効なBAG slotへ再配置して可視な所持品にする。
     */
    @Test
    void marketCancellationReconcilesConflictedHotbarSourceIntoVisibleBagSlot() {
        UUID accountId = UUID.randomUUID();
        UUID restoredEntryId = UUID.randomUUID();
        UUID hotbarOccupantId = UUID.randomUUID();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        InventoryModel hotbar = DesignTestFixtures.inventory(accountId, InventoryType.HOTBAR, 9);
        state.putInventory(bag);
        state.putInventory(hotbar);
        InventoryEntryModel hotbarOccupant = categoryEntry(
            hotbarOccupantId,
            accountId,
            hotbar.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            ItemCategory.MATERIAL,
            "hotbar_occupant",
            1L
        );
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of());
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(hotbarOccupant));
        registry.put(state);
        InventoryRepository repository = mock(InventoryRepository.class);
        InventoryService service = new InventoryService(
            repository,
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            registry,
            mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );
        InventoryEntryModel restored = categoryEntry(
            restoredEntryId,
            accountId,
            bag.getInventoryId(),
            null,
            ItemCategory.MATERIAL,
            "market_material",
            3L
        );
        when(repository.findEntryById(restoredEntryId)).thenReturn(restored);

        service.reconcileExternalInventoryEntries(
            accountId,
            List.of(restoredEntryId),
            new InventoryPersistence.PersistedInventoryBaseline(
                accountId,
                Map.of(
                    bag.getInventoryId(), List.of(),
                    hotbar.getInventoryId(), List.of(hotbarOccupant)
                )
            )
        );

        InventoryEntryModel visible = service.findOwnedEntry(accountId, restoredEntryId);
        assertNotNull(visible);
        assertEquals(bag.getInventoryId(), visible.getInventoryId());
        assertEquals(NormalInventoryLayout.DB_SLOT_START, visible.getSlotIndex());
        assertEquals(hotbar.getInventoryId(), service.findOwnedEntry(
            accountId, hotbarOccupantId).getInventoryId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 15.1. オーブ操作の保存laneとAPI正本照合
     * 検証契約: HOTBARからentryが全削除されても固定slotを前詰めしない。
     */
    @Test
    void fullAffectedRemovalPreservesFixedHotbarSlots() {
        Harness harness = harness(InventoryType.HOTBAR);
        UUID unrelatedEntryId = UUID.randomUUID();
        InventoryEntryModel consumed = entry(
            harness.orbEntryId, harness.accountId, harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START + 2, "orb.weapon_tyr", 1L);
        InventoryEntryModel unrelated = entry(
            unrelatedEntryId, harness.accountId, harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START + 8, "unrelated_material", 7L);
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(
            consumed, unrelated));
        when(harness.repository.findEntryById(harness.orbEntryId)).thenReturn(null);

        harness.service.reconcileOrbOperationEntries(
            harness.accountId,
            List.of(harness.orbEntryId),
            baseline(harness.accountId, harness.bag.getInventoryId(), List.of(
                consumed, unrelated))
        );

        assertNull(harness.service.findOwnedEntry(harness.accountId, harness.orbEntryId));
        assertEquals(unrelated.getSlotIndex(), harness.service.findOwnedEntry(
            harness.accountId, unrelatedEntryId).getSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 15.1. オーブ操作の保存laneとAPI正本照合
     * 検証契約: BAGからentryが全削除された場合だけ、残存entryを表示順で前詰めする。
     */
    @Test
    void fullAffectedRemovalCompactsBagInDisplayOrder() {
        Harness harness = harness(InventoryType.BAG);
        UUID unrelatedEntryId = UUID.randomUUID();
        InventoryEntryModel consumed = entry(
            harness.orbEntryId, harness.accountId, harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START + 2, "orb.weapon_tyr", 1L);
        InventoryEntryModel unrelated = entry(
            unrelatedEntryId, harness.accountId, harness.bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START + 8, "unrelated_material", 7L);
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(
            consumed, unrelated));
        when(harness.repository.findEntryById(harness.orbEntryId)).thenReturn(null);

        harness.service.reconcileOrbOperationEntries(
            harness.accountId,
            List.of(harness.orbEntryId),
            baseline(harness.accountId, harness.bag.getInventoryId(), List.of(
                consumed, unrelated))
        );

        assertNull(harness.service.findOwnedEntry(harness.accountId, harness.orbEntryId));
        assertEquals(NormalInventoryLayout.DB_SLOT_START, harness.service.findOwnedEntry(
            harness.accountId, unrelatedEntryId).getSlotIndex());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: API正本でオーブentryが消滅した場合はそのentryだけを除去して後続slotを前詰めし、同時に存在する無関係entryを保持する。
     */
    @Test
    void authoritativeRemovalCompactsOnlyTheConsumedOrbEntry() {
        Harness harness = harness();
        UUID unrelatedEntryId = UUID.randomUUID();
        harness.state.replaceEntriesFromLoad(harness.bag.getInventoryId(), List.of(
            entry(
                harness.orbEntryId,
                harness.accountId,
                harness.bag.getInventoryId(),
                NormalInventoryLayout.DB_SLOT_START,
                "orb.weapon_tyr",
                1L
            ),
            entry(
                unrelatedEntryId,
                harness.accountId,
                harness.bag.getInventoryId(),
                NormalInventoryLayout.DB_SLOT_START + 1,
                "unrelated_material",
                7L
            )
        ));
        when(harness.repository.findEntryById(harness.orbEntryId)).thenReturn(null);

        harness.service.reconcileAuthoritativeEntry(harness.accountId, harness.orbEntryId);

        assertNull(harness.service.findOwnedEntry(harness.accountId, harness.orbEntryId));
        InventoryEntryModel unrelated = harness.service.findOwnedEntry(
            harness.accountId, unrelatedEntryId);
        assertNotNull(unrelated);
        assertEquals(NormalInventoryLayout.DB_SLOT_START, unrelated.getSlotIndex());
        assertEquals(7L, unrelated.getQuantity());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 7. プレイヤーがオーブから装備操作を開始する
     * 検証契約: affected entryがAPI上で別accountのinventoryへ移動した場合、旧所有者stateから参照を除去するだけとし、未所有inventoryや移動先entryを生成しない。
     */
    @Test
    void reconciliationCannotInjectEntryMovedToAnotherAccountInventory() {
        Harness harness = harness();
        UUID foreignInventoryId = UUID.randomUUID();
        InventoryEntryModel moved = entry(
            harness.orbEntryId,
            UUID.randomUUID(),
            foreignInventoryId,
            NormalInventoryLayout.DB_SLOT_START,
            "orb.weapon_tyr",
            1L
        );
        when(harness.repository.findEntryById(harness.orbEntryId)).thenReturn(moved);

        harness.service.reconcileAuthoritativeEntry(harness.accountId, harness.orbEntryId);

        assertNull(harness.service.findOwnedEntry(harness.accountId, harness.orbEntryId));
        assertNull(harness.state.findInventoryById(foreignInventoryId));
        assertEquals(List.of(harness.bag), harness.state.snapshotInventories());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 8. 装備・ホットバー・アクセサリのスナップショット保存
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: APIが削除・譲渡・membership不在を確定した装備はinventory entryとactive loadoutから同時に除去してdirty保存し、再起動相当の再load後も旧membershipを復活さず無関係装備を保持する。
     */
    @Test
    void unavailableEquipmentCleanupPersistsInventoryAndLoadoutTombstoneAcrossReload() {
        UUID accountId = UUID.randomUUID();
        UUID unavailableInstanceId = UUID.randomUUID();
        UUID retainedInstanceId = UUID.randomUUID();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, InventoryType.BAG, 27);
        state.putInventory(bag);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(
            equipmentEntry(UUID.randomUUID(), bag.getInventoryId(), accountId,
                unavailableInstanceId, NormalInventoryLayout.DB_SLOT_START),
            equipmentEntry(UUID.randomUUID(), bag.getInventoryId(), accountId,
                retainedInstanceId, NormalInventoryLayout.DB_SLOT_START + 1)
        ));
        UUID loadoutId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        state.putLoadout(new EquipmentLoadoutModel(
            loadoutId,
            accountId,
            InventoryProfile.GAME.getCode(),
            "Default",
            0,
            true,
            null,
            List.of(
                loadoutSlot(loadoutId, accountId, unavailableInstanceId, "HEAD", now),
                loadoutSlot(loadoutId, accountId, retainedInstanceId, "CHEST", now)
            ),
            now,
            now,
            accountId,
            accountId,
            false
        ));
        registry.put(state);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        AtomicReference<List<InventoryEntryModel>> savedEntries = new AtomicReference<>();
        AtomicReference<List<EquipmentLoadoutModel>> savedLoadouts = new AtomicReference<>();
        when(persistence.saveNow(state)).thenAnswer(invocation -> {
            savedEntries.set(List.copyOf(state.snapshotEntries(bag.getInventoryId())));
            savedLoadouts.set(List.copyOf(state.snapshotLoadouts(InventoryProfile.GAME)));
            return true;
        });
        InventoryService service = new InventoryService(
            mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            registry,
            persistence,
            mock(InventorySaveCoordinator.class)
        );

        service.discardUnavailableEquipmentInstance(accountId, unavailableInstanceId);

        assertTrue(state.isDirty());
        assertTrue(service.persistReconciledStateNow(accountId));
        assertNotNull(savedEntries.get());
        assertNotNull(savedLoadouts.get());
        assertFalse(savedEntries.get().stream().anyMatch(
            entry -> unavailableInstanceId.equals(entry.getInstanceId())));
        assertFalse(savedLoadouts.get().stream()
            .flatMap(loadout -> loadout.getSlots().stream())
            .anyMatch(slot -> unavailableInstanceId.equals(slot.getEquipmentInstanceId())));

        PlayerInventoryState reloaded = new PlayerInventoryState(accountId);
        reloaded.putInventory(bag);
        reloaded.replaceEntriesFromLoad(bag.getInventoryId(), savedEntries.get());
        savedLoadouts.get().forEach(reloaded::putLoadout);

        assertEquals(1, reloaded.snapshotEntries(bag.getInventoryId()).size());
        assertEquals(retainedInstanceId,
            reloaded.snapshotEntries(bag.getInventoryId()).getFirst().getInstanceId());
        EquipmentLoadoutModel reloadedLoadout = reloaded.findActiveLoadout(InventoryProfile.GAME);
        assertNotNull(reloadedLoadout);
        assertEquals(1, reloadedLoadout.getSlots().size());
        assertEquals(retainedInstanceId,
            reloadedLoadout.getSlots().getFirst().getEquipmentInstanceId());
    }

    private static Harness harness() {
        return harness(InventoryType.BAG);
    }

    private static Harness harness(InventoryType inventoryType) {
        UUID accountId = UUID.randomUUID();
        UUID orbEntryId = UUID.randomUUID();
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel bag = DesignTestFixtures.inventory(accountId, inventoryType, 27);
        state.putInventory(bag);
        state.replaceEntriesFromLoad(bag.getInventoryId(), List.of(entry(
            orbEntryId,
            accountId,
            bag.getInventoryId(),
            NormalInventoryLayout.DB_SLOT_START,
            "orb.weapon_tyr",
            2L
        )));
        registry.put(state);
        InventoryRepository repository = mock(InventoryRepository.class);
        InventoryPersistence persistence = mock(InventoryPersistence.class);
        InventoryService service = new InventoryService(
            repository,
            mock(EquipmentLoadoutRepository.class),
            mock(ItemService.class),
            mock(ItemStackFactory.class),
            registry,
            persistence,
            mock(InventorySaveCoordinator.class)
        );
        return new Harness(accountId, orbEntryId, bag, state, repository, persistence, service);
    }

    private static InventoryEntryModel entry(
        UUID entryId,
        UUID accountId,
        UUID inventoryId,
        int slot,
        String itemId,
        long quantity
    ) {
        ItemCategory category = itemId.startsWith("orb.")
            ? ItemCategory.ORB
            : ItemCategory.MATERIAL;
        return categoryEntry(entryId, accountId, inventoryId, slot, category, itemId, quantity);
    }

    private static InventoryEntryModel categoryEntry(
        UUID entryId,
        UUID accountId,
        UUID inventoryId,
        Integer slot,
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

    private static InventoryEntryModel instanceEntry(
        UUID entryId,
        UUID accountId,
        UUID inventoryId,
        ItemCategory category,
        String instanceType
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
            inventoryId,
            null,
            category.getApiValue(),
            null,
            instanceType,
            UUID.randomUUID(),
            1L,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private static InventoryPersistence.PersistedInventoryBaseline baseline(
        UUID accountId,
        UUID inventoryId,
        List<InventoryEntryModel> entries
    ) {
        return new InventoryPersistence.PersistedInventoryBaseline(
            accountId,
            Map.of(inventoryId, entries)
        );
    }

    private static long totalGoldValue(List<InventoryEntryModel> entries) {
        long total = 0L;
        for (InventoryEntryModel entry : entries) {
            GoldDenomination denomination = GoldDenomination.findByItemId(entry.getItemId());
            if (denomination != null) {
                total += denomination.goldValue() * entry.getQuantity();
            }
        }
        return total;
    }

    private static InventoryEntryModel equipmentEntry(
        UUID entryId,
        UUID inventoryId,
        UUID accountId,
        UUID instanceId,
        int slotIndex
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
            inventoryId,
            slotIndex,
            ItemCategory.EQUIPMENT.getApiValue(),
            null,
            "equipment",
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

    private static EquipmentLoadoutSlotModel loadoutSlot(
        UUID loadoutId,
        UUID accountId,
        UUID instanceId,
        String slotType,
        LocalDateTime now
    ) {
        return new EquipmentLoadoutSlotModel(
            UUID.randomUUID(),
            loadoutId,
            slotType,
            0,
            instanceId,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private record Harness(
        UUID accountId,
        UUID orbEntryId,
        InventoryModel bag,
        PlayerInventoryState state,
        InventoryRepository repository,
        InventoryPersistence persistence,
        InventoryService service
    ) {
    }
}
