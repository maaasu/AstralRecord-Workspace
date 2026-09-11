package io.github.maaasu.astralRecord.feature.inventory.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.repository.PlayerStateRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryPersistenceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 外部取引前の baseline は、BAG だけが dirty でも未変更の通貨残高を含む。
     */
    @Test
    void externalBaselineIncludesUnchangedCurrencyWhenOnlyBagIsDirty() {
        UUID accountId = UUID.randomUUID();
        UUID bagId = UUID.randomUUID();
        UUID currencyId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 9, 6, 1, 0);
        InventoryModel bag = new InventoryModel(bagId, accountId, InventoryType.BAG,
            InventoryProfile.GAME.getCode(), 9, true, null, now, now, accountId, accountId, false);
        InventoryModel currency = new InventoryModel(currencyId, accountId, InventoryType.CURRENCY,
            InventoryProfile.GAME.getCode(), null, true, null, now, now, accountId, accountId, false);
        InventoryEntryModel gold = new InventoryEntryModel(UUID.randomUUID(), currencyId, 1,
            "CURRENCY", "gold", null, null, 100L, null, now, now, accountId, accountId, false);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        when(inventoryRepository.findByAccountId(accountId)).thenReturn(List.of(bag, currency));
        when(inventoryRepository.findEntries(bagId)).thenReturn(List.of());
        when(inventoryRepository.findEntries(currencyId)).thenReturn(List.of(gold));
        when(loadoutRepository.findByAccountId(accountId, InventoryProfile.GAME)).thenReturn(List.of());
        InventoryPersistence persistence = new InventoryPersistence(inventoryRepository, loadoutRepository,
            mock(ItemService.class), snapshotRepository());
        PlayerInventoryState state = persistence.load(accountId);
        state.updateInventoryMetadata(bagId, "{}", accountId);

        InventoryPersistence.PersistedInventoryBaseline baseline = persistence.saveNowWithBaseline(state);

        assertNotNull(baseline);
        assertEquals(100L, baseline.entries(currencyId).stream().mapToLong(InventoryEntryModel::getQuantity).sum());
        assertEquals(gold.getInventoryEntryId(), baseline.entries(currencyId).getFirst().getInventoryEntryId());
        assertFalse(persistence.hasPendingChanges(state));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値
     * 検証契約: dirty耐久値をflushしてもpendingが残る場合、inventoryのsaveNowはfalseを返す。
     */
    @Test
    void saveNowDoesNotFallBackToLegacyEquipmentDurabilityWrite() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        PlayerStateRepository playerStateRepository = mock(PlayerStateRepository.class);
        when(playerStateRepository.saveSnapshot(anyString())).thenThrow(new RuntimeException("unavailable"));
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository,
            loadoutRepository,
            itemService,
            playerStateRepository
        );

        boolean succeeded = persistence.saveNow(state);

        assertFalse(succeeded);
        verify(playerStateRepository).saveSnapshot(anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: 結果不明のHTTP 425は通常保存をblockせず、同じpending snapshotをbackoff再試行できる状態に保つ。
     */
    @Test
    void ambiguousTooEarlyResponseDoesNotBlockNormalSnapshotRetry() {
        UUID accountId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        PlayerStateRepository playerStateRepository = mock(PlayerStateRepository.class);
        when(playerStateRepository.saveSnapshot(anyString())).thenThrow(
            new InventoryApiException("POST", "/api/player-state/snapshots", 425, "too early"));
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository,
            loadoutRepository,
            itemService,
            playerStateRepository
        );

        assertFalse(persistence.saveNow(state));
        assertFalse(persistence.isPlayerStateBlocked(accountId));

        persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);
        verify(playerStateRepository).saveSnapshot(anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 5. 永続化制御
     * 検証契約: API正本だけが更新した古いentry snapshotは、409後に正本へ差し替えて保存laneを継続する。
     */
    @Test
    void staleSnapshotConflictDoesNotFallBackToLegacyEntryReplacement() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDateTime baselineAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        InventoryModel inventory = new InventoryModel(
            inventoryId,
            accountId,
            InventoryType.CURRENCY,
            InventoryProfile.GAME.getCode(),
            9,
            true,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        InventoryEntryModel stale = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            100L,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.putInventory(inventory);
        state.replaceEntriesFromLoad(inventoryId, List.of(stale));
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        PlayerStateRepository playerStateRepository = mock(PlayerStateRepository.class);
        when(playerStateRepository.saveSnapshot(anyString()))
            .thenThrow(new InventoryApiException("POST", "/api/player-state/snapshots", 409, "stale"));
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository,
            loadoutRepository,
            itemService,
            playerStateRepository
        );

        boolean saved = persistence.saveCriticalNow(state);

        assertFalse(saved);
        assertEquals(List.of(stale), state.snapshotEntries(inventoryId));
        verify(inventoryRepository, never()).findEntries(inventoryId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 5. 永続化制御
     * 検証契約: 409の再取得中に同じentryへローカル変更が入った場合は、その変更を正本snapshotで上書きしない。
     */
    @Test
    void staleSnapshotConflictDoesNotOverwriteConcurrentLocalEntryChange() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDateTime baselineAt = LocalDateTime.of(2026, 8, 15, 12, 0);
        InventoryEntryModel submitted = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            100L,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        InventoryEntryModel localChange = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            110L,
            null,
            baselineAt,
            baselineAt,
            accountId,
            accountId,
            false
        );
        InventoryEntryModel authoritative = new InventoryEntryModel(
            entryId,
            inventoryId,
            1,
            "CURRENCY",
            "gold",
            null,
            null,
            125L,
            null,
            baselineAt,
            baselineAt.plusSeconds(1),
            accountId,
            accountId,
            false
        );
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.replaceEntriesFromLoad(inventoryId, List.of(submitted));
        state.replaceEntries(inventoryId, List.of(localChange));

        boolean replaced = state.replaceEntriesFromAuthoritativeSnapshotIfUnchanged(
            inventoryId,
            List.of(submitted),
            List.of(authoritative)
        );

        assertFalse(replaced);
        assertEquals(List.of(localChange), state.snapshotEntries(inventoryId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 5. 永続化制御
     * 検証契約: 撤廃済みスキルジェムのようにアイテムマスタを解決できない通常 entry は、
     * ログインロード時に補償なしで破棄し、API の置換保存へ送らない。
     */
    @Test
    void loadDiscardsLegacySkillGemEntriesAndPersistsTheCleanup() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 19, 0);
        InventoryModel inventory = new InventoryModel(
            inventoryId, accountId, InventoryType.BAG, InventoryProfile.GAME.getCode(), 9,
            true, null, now, now, accountId, accountId, false
        );
        InventoryEntryModel legacySkillGemEntry = new InventoryEntryModel(
            UUID.randomUUID(), inventoryId, 1, "SKILL_GEM", "00_skill_gem_legacy", null, null,
            3L, null, now, now, accountId, accountId, false
        );
        InventoryEntryModel validEntry = new InventoryEntryModel(
            UUID.randomUUID(), inventoryId, 2, "MATERIAL", "still_valid", null, null,
            1L, null, now, now, accountId, accountId, false
        );
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        PlayerStateRepository playerStateRepository = snapshotRepository();
        ItemModel validItem = mock(ItemModel.class);
        when(inventoryRepository.findByAccountId(accountId)).thenReturn(List.of(inventory));
        when(inventoryRepository.findEntries(inventoryId)).thenReturn(List.of(legacySkillGemEntry, validEntry));
        when(loadoutRepository.findByAccountId(accountId, InventoryProfile.GAME)).thenReturn(List.of());
        when(itemService.isMasterDataLoaded()).thenReturn(true);
        when(itemService.findLoadedById("still_valid")).thenReturn(validItem);
        when(itemService.hasDirtyEquipmentDurability(accountId)).thenReturn(false);
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository, loadoutRepository, itemService, playerStateRepository
        );

        PlayerInventoryState loaded = persistence.load(accountId);

        List<InventoryEntryModel> persistedEntries = loaded.snapshotEntries(inventoryId);
        assertEquals(1, persistedEntries.size());
        assertEquals(validEntry.getInventoryEntryId(), persistedEntries.getFirst().getInventoryEntryId());
        assertEquals(LocalDateTime.of(2026, 9, 6, 1, 0, 1), persistedEntries.getFirst().getUpdatedAt());
        verify(playerStateRepository).saveSnapshot(anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 5. 永続化制御
     * 検証契約: 全カテゴリのアイテムマスタが未公開の間は、部分キャッシュだけを根拠に entry を破棄しない。
     */
    @Test
    void loadRetainsEntriesUntilTheCompleteItemMasterSnapshotIsPublished() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 19, 0);
        InventoryModel inventory = new InventoryModel(
            inventoryId, accountId, InventoryType.BAG, InventoryProfile.GAME.getCode(), 9,
            true, null, now, now, accountId, accountId, false
        );
        InventoryEntryModel entry = new InventoryEntryModel(
            UUID.randomUUID(), inventoryId, 1, "MATERIAL", "partially_cached_item", null, null,
            1L, null, now, now, accountId, accountId, false
        );
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        PlayerStateRepository playerStateRepository = snapshotRepository();
        when(inventoryRepository.findByAccountId(accountId)).thenReturn(List.of(inventory));
        when(inventoryRepository.findEntries(inventoryId)).thenReturn(List.of(entry));
        when(loadoutRepository.findByAccountId(accountId, InventoryProfile.GAME)).thenReturn(List.of());
        when(itemService.isMasterDataLoaded()).thenReturn(false);
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository, loadoutRepository, itemService, playerStateRepository
        );

        PlayerInventoryState loaded = persistence.load(accountId);

        assertEquals(List.of(entry), loaded.snapshotEntries(inventoryId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 5. 永続化制御
     * 検証契約: 削除済み装備マスタと通常 item master が同時に存在しても、両方の entry を同一ロードで破棄する。
     */
    @Test
    void loadDiscardsDeletedEquipmentAndNormalItemMastersTogether() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID equipmentInstanceId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 19, 0);
        InventoryModel inventory = new InventoryModel(
            inventoryId, accountId, InventoryType.BAG, InventoryProfile.GAME.getCode(), 9,
            true, null, now, now, accountId, accountId, false
        );
        InventoryEntryModel equipmentEntry = new InventoryEntryModel(
            UUID.randomUUID(), inventoryId, 1, "EQUIPMENT", "deleted_equipment", "EQUIPMENT",
            equipmentInstanceId, 1L, null, now, now, accountId, accountId, false
        );
        InventoryEntryModel normalEntry = new InventoryEntryModel(
            UUID.randomUUID(), inventoryId, 2, "MATERIAL", "deleted_material", null, null,
            1L, null, now, now, accountId, accountId, false
        );
        InventoryRepository inventoryRepository = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadoutRepository = mock(EquipmentLoadoutRepository.class);
        ItemService itemService = mock(ItemService.class);
        PlayerStateRepository playerStateRepository = snapshotRepository();
        EquipmentInstance equipmentInstance = mock(EquipmentInstance.class);
        when(inventoryRepository.findByAccountId(accountId)).thenReturn(List.of(inventory));
        when(inventoryRepository.findEntries(inventoryId)).thenReturn(List.of(equipmentEntry, normalEntry));
        when(loadoutRepository.findByAccountId(accountId, InventoryProfile.GAME)).thenReturn(List.of());
        when(itemService.isMasterDataLoaded()).thenReturn(true);
        when(itemService.preloadEquipmentInstances(org.mockito.ArgumentMatchers.anyCollection()))
            .thenReturn(ItemService.EquipmentPreloadResult.COMPLETE);
        when(itemService.findLoadedEquipmentInstanceById(equipmentInstanceId.toString())).thenReturn(equipmentInstance);
        when(equipmentInstance.getAccountId()).thenReturn(accountId.toString());
        when(equipmentInstance.getItemId()).thenReturn("deleted_equipment");
        when(itemService.hasDirtyEquipmentDurability(accountId)).thenReturn(false);
        InventoryPersistence persistence = new InventoryPersistence(
            inventoryRepository, loadoutRepository, itemService, playerStateRepository
        );

        PlayerInventoryState loaded = persistence.load(accountId);

        assertEquals(List.of(), loaded.snapshotEntries(inventoryId));
        verify(playerStateRepository).saveSnapshot(anyString());
    }

    private static PlayerStateRepository snapshotRepository() {
        PlayerStateRepository repository = mock(PlayerStateRepository.class);
        when(repository.saveSnapshot(anyString())).thenAnswer(invocation ->
            acknowledge(JsonParser.parseString(invocation.getArgument(0, String.class)).getAsJsonObject()));
        return repository;
    }

    private static JsonObject acknowledge(JsonObject request) {
        JsonObject acknowledgement = new JsonObject();
        acknowledgement.add("snapshotId", request.get("snapshotId"));
        acknowledgement.add("accountId", request.get("accountId"));
        JsonArray inventories = new JsonArray();
        JsonArray entries = new JsonArray();
        for (JsonElement inventoryElement : request.getAsJsonArray("inventories")) {
            JsonObject inventory = inventoryElement.getAsJsonObject();
            JsonObject inventoryAck = new JsonObject();
            inventoryAck.add("inventoryId", inventory.get("inventoryId"));
            inventoryAck.addProperty("updatedAt", "2026-09-06T01:00:01");
            inventories.add(inventoryAck);
            java.util.Set<String> activeIds = new java.util.HashSet<>();
            for (JsonElement entryElement : inventory.getAsJsonArray("entries")) {
                String id = entryElement.getAsJsonObject().get("inventoryEntryId").getAsString();
                activeIds.add(id);
                entries.add(entryAck(id, false));
            }
            for (JsonElement expectedElement : inventory.getAsJsonArray("expectedEntries")) {
                String id = expectedElement.getAsJsonObject().get("inventoryEntryId").getAsString();
                if (!activeIds.contains(id)) entries.add(entryAck(id, true));
            }
        }
        acknowledgement.add("inventories", inventories);
        acknowledgement.add("entries", entries);
        acknowledgement.add("loadouts", new JsonArray());
        acknowledgement.add("equipment", new JsonArray());
        return acknowledgement;
    }

    private static JsonObject entryAck(String id, boolean deleted) {
        JsonObject entry = new JsonObject();
        entry.addProperty("inventoryEntryId", id);
        entry.addProperty("updatedAt", "2026-09-06T01:00:01");
        entry.addProperty("isDeleted", deleted);
        return entry;
    }
}
