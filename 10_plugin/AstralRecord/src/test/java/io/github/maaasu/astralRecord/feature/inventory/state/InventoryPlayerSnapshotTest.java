package io.github.maaasu.astralRecord.feature.inventory.state;

import com.google.gson.*;
import io.github.maaasu.astralRecord.feature.inventory.model.*;
import io.github.maaasu.astralRecord.feature.inventory.repository.*;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.repository.PlayerStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryPlayerSnapshotTest {
    @TempDir Path directory;
    private final UUID account = UUID.randomUUID();
    private final UUID inventoryId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final LocalDateTime originalTime = LocalDateTime.of(2026, 9, 6, 1, 0);

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカル状態と完成スナップショット
     * 検証契約: API待機中のローカル消費をACKで巻き戻さず、次便は更新済み版と最新数量を送る。
     */
    @Test void acknowledgesVersionsWithoutRollingBackNewerQuantity() {
        InventoryRepository inventories = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadouts = mock(EquipmentLoadoutRepository.class);
        ItemService items = mock(ItemService.class);
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        when(inventories.findByAccountId(account)).thenReturn(List.of(inventory()));
        when(inventories.findEntries(inventoryId)).thenReturn(List.of(entry(10, originalTime)));
        InventoryPersistence persistence = new InventoryPersistence(inventories, loadouts, items);
        persistence.enablePlayerStateSnapshots(api, directory);
        PlayerInventoryState state = persistence.load(account);
        List<JsonObject> sent = new ArrayList<>();
        when(api.saveSnapshot(anyString())).thenAnswer(call -> {
            JsonObject request = JsonParser.parseString(call.getArgument(0, String.class)).getAsJsonObject();
            sent.add(request);
            if (sent.size() == 1) state.replaceEntries(inventoryId, List.of(entry(7, originalTime)));
            return ack(request, originalTime.plusSeconds(sent.size()));
        });
        state.markDirty();
        assertTrue(persistence.saveNow(state), "Later dirty must not turn an acknowledged save into failure");
        assertEquals(7, state.snapshotEntries(inventoryId).getFirst().getQuantity());
        assertEquals(originalTime.plusSeconds(1), state.snapshotEntries(inventoryId).getFirst().getUpdatedAt());
        assertTrue(persistence.hasPendingChanges(state));
        persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);
        assertFalse(persistence.hasPendingChanges(state));
        assertEquals(2, sent.size());
        JsonObject nextEntry = sent.get(1).getAsJsonArray("inventories").get(0).getAsJsonObject()
            .getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals(7, nextEntry.get("quantity").getAsLong());
        assertEquals(originalTime.plusSeconds(1).toString(), nextEntry.get("expectedUpdatedAt").getAsString());
        assertNotEquals(sent.get(0).get("snapshotId"), sent.get(1).get("snapshotId"));
        verify(inventories, never()).replaceEntries(any(), anyList(), any());
        verify(items, never()).flushDirtyEquipmentDurability(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカル状態と完成スナップショット
     * 検証契約: 捕捉後の変更で送信本文を変えず、旧entry集合と移動後の配置を別々に保持する。
     */
    @Test void freezesPayloadAndCarriesOriginalEntrySetForDeletion() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        state.putInventory(inventory());
        state.replaceEntries(inventoryId, List.of(entry(10, originalTime)));
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(state, List.of(), List.of(),
            Map.of(inventoryId, Set.of(entryId)), Map.of(entryId, originalTime));
        state.replaceEntries(inventoryId, List.of());
        JsonObject first = JsonParser.parseString(snapshot.payload).getAsJsonObject();
        assertEquals(10, first.getAsJsonArray("inventories").get(0).getAsJsonObject()
            .getAsJsonArray("entries").get(0).getAsJsonObject().get("quantity").getAsLong());
        PlayerStateSnapshot next = new PlayerStateSnapshot(state, List.of(), List.of(),
            Map.of(inventoryId, Set.of(entryId)), Map.of(entryId, originalTime));
        JsonObject inventory = JsonParser.parseString(next.payload).getAsJsonObject()
            .getAsJsonArray("inventories").get(0).getAsJsonObject();
        assertTrue(inventory.getAsJsonArray("entries").isEmpty());
        assertEquals(entryId.toString(), inventory.getAsJsonArray("expectedEntries").get(0).getAsJsonObject().get("inventoryEntryId").getAsString());
        assertEquals(originalTime.toString(), inventory.getAsJsonArray("expectedEntries").get(0).getAsJsonObject().get("updatedAt").getAsString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカルファイルと再送
     * 検証契約: 応答不明の本文を保持し、再ロード時も同一snapshotを再送してACK後だけ削除する。
     */
    @Test void replaysFrozenFileBeforeLoadingAfterLostAcknowledgement() {
        InventoryRepository inventories = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadouts = mock(EquipmentLoadoutRepository.class);
        ItemService items = mock(ItemService.class);
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        when(inventories.findByAccountId(account)).thenReturn(List.of(inventory()));
        when(inventories.findEntries(inventoryId)).thenReturn(List.of(entry(10, originalTime)));
        InventoryPersistence persistence = new InventoryPersistence(inventories, loadouts, items);
        persistence.enablePlayerStateSnapshots(api, directory);
        PlayerInventoryState state = persistence.load(account);
        List<String> payloads = new ArrayList<>();
        when(api.saveSnapshot(anyString())).thenAnswer(call -> {
            String payload = call.getArgument(0, String.class);
            payloads.add(payload);
            if (payloads.size() == 1) throw new java.io.UncheckedIOException(new java.io.IOException("lost ACK"));
            return ack(JsonParser.parseString(payload).getAsJsonObject(), originalTime.plusSeconds(1));
        });
        state.markDirty();
        persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);
        assertTrue(persistence.hasPendingChanges(state));
        var disk = new io.github.maaasu.astralRecord.feature.mutation.service.PendingStateStore(directory);
        assertEquals(payloads.getFirst(), disk.read(account));
        InventoryPersistence restarted = new InventoryPersistence(inventories, loadouts, items);
        restarted.enablePlayerStateSnapshots(api, directory);
        restarted.load(account);
        assertEquals(2, payloads.size());
        assertEquals(payloads.get(0), payloads.get(1));
        assertNull(disk.read(account));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカル状態と完成スナップショット
     * 検証契約: 一部のACKが欠落した応答では保存済みと判定しない。
     */
    @Test void rejectsIncompleteAcknowledgementBeforeUpdatingLocalVersions() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        state.putInventory(inventory());
        state.replaceEntries(inventoryId, List.of(entry(10, originalTime)));
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(state, List.of(), List.of(), Map.of(inventoryId, Set.of(entryId)), Map.of(entryId, originalTime));
        JsonObject ack = ack(JsonParser.parseString(snapshot.payload).getAsJsonObject(), originalTime.plusSeconds(1));
        ack.add("inventories", new JsonArray());
        assertThrows(IllegalStateException.class, () -> snapshot.validateAck(ack));
        assertEquals(originalTime, state.snapshotEntries(inventoryId).getFirst().getUpdatedAt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカル状態と完成スナップショット
     * 検証契約: metadataと装備配置のローカル編集はAPI競合判定用の最終受領版を変更しない。
     */
    @Test void preservesApiBaseTimesWhenEditingMetadataAndLoadout() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        state.putInventory(inventory());
        UUID loadoutId = UUID.randomUUID();
        state.putLoadout(new EquipmentLoadoutModel(loadoutId, account, "GAME", "test", 0, true,
            null, List.of(), originalTime, originalTime, account, account, false));
        state.updateInventoryMetadata(inventoryId, "{\"edited\":true}", account);
        state.upsertActiveLoadoutSlot(InventoryProfile.GAME, "HEAD", 0, UUID.randomUUID(), account);
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(state, List.of(), List.of(), Map.of(), Map.of());
        JsonObject body = JsonParser.parseString(snapshot.payload).getAsJsonObject();
        assertEquals(originalTime.toString(), body.getAsJsonArray("inventories").get(0).getAsJsonObject().get("expectedUpdatedAt").getAsString());
        assertEquals(originalTime.toString(), body.getAsJsonArray("loadouts").get(0).getAsJsonObject().get("expectedUpdatedAt").getAsString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカルファイルと再送
     * 検証契約: 再起動の不完全ACKではファイルを保持し、通常ロードも開始しない。
     */
    @Test void recoveryKeepsFrozenFileWhenEntryAcknowledgementIsMissing() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        state.putInventory(inventory());
        state.replaceEntries(inventoryId, List.of(entry(10, originalTime)));
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(state, List.of(), List.of(),
            Map.of(inventoryId, Set.of(entryId)), Map.of(entryId, originalTime));
        var disk = new io.github.maaasu.astralRecord.feature.mutation.service.PendingStateStore(directory);
        disk.write(account, snapshot.payload);
        InventoryRepository inventories = mock(InventoryRepository.class);
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        JsonObject incomplete = ack(JsonParser.parseString(snapshot.payload).getAsJsonObject(), originalTime.plusSeconds(1));
        incomplete.add("entries", new JsonArray());
        when(api.saveSnapshot(snapshot.payload)).thenReturn(incomplete);
        InventoryPersistence persistence = new InventoryPersistence(inventories,
            mock(EquipmentLoadoutRepository.class), mock(ItemService.class));
        persistence.enablePlayerStateSnapshots(api, directory);
        assertThrows(IllegalStateException.class, () -> persistence.load(account));
        assertEquals(snapshot.payload, disk.read(account));
        verifyNoInteractions(inventories);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 1. save メソッド仕様 > ### ローカル状態と完成スナップショット
     * 検証契約: 全sectionの欠落・旧revision・不完全明細ACKを拒否し、完全ACKだけを受領する。
     */
    @Test void validatesAllSectionAcknowledgementsFromFrozenPayload() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(state, List.of(), List.of(), Map.of(), Map.of());
        JsonObject request = JsonParser.parseString(snapshot.payload).getAsJsonObject();
        JsonObject response = ack(request, originalTime.plusSeconds(1));
        UUID skill = UUID.randomUUID();
        UUID removedSkill = UUID.randomUUID();
        request.add("learnedSkills", JsonParser.parseString("""
            {"clientRevision":2,"skills":[{"learnedSkillId":"%s","expectedVersion":4}],
             "deletedSkills":[{"learnedSkillId":"%s","expectedVersion":1}]}
            """.formatted(skill, removedSkill)));
        response.add("learnedSkills", JsonParser.parseString("""
            {"clientRevision":2,"entries":[{"learnedSkillId":"%s","version":5}],"deletedIds":["%s"]}
            """.formatted(skill, removedSkill)));
        request.add("skillBindPresets", JsonParser.parseString("""
            {"clientRevision":3,"presets":[{"presetIndex":0,"expectedVersion":1}]}
            """));
        response.add("skillBindPresets", JsonParser.parseString("""
            {"clientRevision":3,"entries":[{"presetIndex":0,"version":2}]}
            """));
        request.add("skillTree", JsonParser.parseString("{\"clientRevision\":4,\"expectedVersion\":2}"));
        response.add("skillTree", JsonParser.parseString("{\"clientRevision\":4,\"version\":3}"));
        request.add("accountProgress", JsonParser.parseString("{\"clientRevision\":5,\"expectedProgressVersion\":2}"));
        response.add("accountProgress", JsonParser.parseString("{\"clientRevision\":5,\"progressVersion\":3}"));
        request.add("waystones", JsonParser.parseString("{\"clientRevision\":6,\"unlockedWaystoneIds\":[\"ws-first\"]}"));
        response.add("waystones", JsonParser.parseString("{\"clientRevision\":6,\"unlockedWaystoneIds\":[\"ws-first\"]}"));
        assertDoesNotThrow(() -> PlayerStateSnapshot.validatePayloadAck(request.toString(), response));
        for (String name : List.of("learnedSkills", "skillBindPresets", "skillTree", "accountProgress", "waystones")) {
            JsonObject missing = response.deepCopy();
            missing.remove(name);
            assertThrows(IllegalStateException.class, () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), missing));
            JsonObject stale = response.deepCopy();
            stale.getAsJsonObject(name).addProperty("clientRevision", 0);
            assertThrows(IllegalStateException.class, () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), stale));
        }
        JsonObject incomplete = response.deepCopy();
        incomplete.getAsJsonObject("learnedSkills").add("deletedIds", new JsonArray());
        assertThrows(IllegalStateException.class, () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), incomplete));
        JsonObject missingWaystone = response.deepCopy();
        missingWaystone.getAsJsonObject("waystones").add("unlockedWaystoneIds", new JsonArray());
        assertThrows(IllegalStateException.class, () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), missingWaystone));
    }

    private JsonObject ack(JsonObject request, LocalDateTime time) {
        JsonObject ack = new JsonObject();
        ack.add("snapshotId", request.get("snapshotId"));
        ack.add("accountId", request.get("accountId"));
        ack.add("entries", versions("inventoryEntryId", entryId, time));
        ack.add("inventories", versions("inventoryId", inventoryId, time));
        ack.add("loadouts", new JsonArray());
        ack.add("equipment", new JsonArray());
        return ack;
    }
    private JsonArray versions(String field, UUID id, LocalDateTime time) {
        JsonObject row = new JsonObject();
        row.addProperty(field, id.toString());
        row.addProperty("updatedAt", time.toString());
        row.addProperty("isDeleted", false);
        JsonArray array = new JsonArray(); array.add(row); return array;
    }
    private InventoryModel inventory() {
        return new InventoryModel(inventoryId, account, InventoryType.BAG, "GAME", 100,
            true, null, originalTime, originalTime, account, account, false);
    }
    private InventoryEntryModel entry(long amount, LocalDateTime timestamp) {
        return new InventoryEntryModel(entryId, inventoryId, 1, "MATERIAL", "material.test", null, null,
            amount, null, originalTime, timestamp, account, account, false);
    }
}
