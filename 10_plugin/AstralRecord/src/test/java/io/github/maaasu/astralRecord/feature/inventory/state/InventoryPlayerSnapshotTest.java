package io.github.maaasu.astralRecord.feature.inventory.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentLoadoutModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentStatRoll;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateAcknowledgementException;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.mutation.repository.PlayerStateRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryPlayerSnapshotTest {
    private final UUID account = UUID.randomUUID();
    private final UUID inventoryId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final LocalDateTime originalTime = LocalDateTime.of(2026, 9, 6, 1, 0);

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 通常変更の統合スナップショット
     * 検証契約: API待機中のローカル消費をACKで巻き戻さず、次便は更新済み版と最新数量を送る。
     */
    @Test
    void acknowledgesVersionsWithoutRollingBackNewerQuantity() {
        InventoryRepository inventories = mock(InventoryRepository.class);
        EquipmentLoadoutRepository loadouts = mock(EquipmentLoadoutRepository.class);
        ItemService items = mock(ItemService.class);
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        when(inventories.findByAccountId(account)).thenReturn(List.of(inventory()));
        when(inventories.findEntries(inventoryId)).thenReturn(List.of(entry(10, originalTime)));
        InventoryPersistence persistence = new InventoryPersistence(inventories, loadouts, items, api);
        PlayerInventoryState state = persistence.load(account);
        List<JsonObject> sent = new ArrayList<>();
        when(api.saveSnapshot(anyString())).thenAnswer(call -> {
            JsonObject request = JsonParser.parseString(call.getArgument(0, String.class)).getAsJsonObject();
            sent.add(request);
            if (sent.size() == 1) {
                state.replaceEntries(inventoryId, List.of(entry(7, originalTime)));
            }
            return ack(request, originalTime.plusSeconds(sent.size()));
        });

        state.markDirty();
        assertTrue(persistence.saveNow(state));
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
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 通常変更の統合スナップショット
     * 検証契約: 捕捉後の削除は初回payloadへ混ぜず、次便の明示削除とbaselineで保存する。
     */
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 9. 完成状態スナップショット
     * 検証契約: 未保存の装備作成を完成スナップショットへ直列化する。
     */
    @Test
    void freezesPayloadAndCarriesDeletionBaselineToNextSnapshot() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        state.putInventory(inventory());
        state.replaceEntries(inventoryId, List.of(entry(10, originalTime)));
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
            state, List.of(), List.of(), Map.of(inventoryId, Set.of(entryId)), Map.of(entryId, originalTime));

        state.replaceEntries(inventoryId, List.of());

        JsonObject first = JsonParser.parseString(snapshot.payload).getAsJsonObject();
        assertEquals(10, first.getAsJsonArray("inventories").get(0).getAsJsonObject()
            .getAsJsonArray("entries").get(0).getAsJsonObject().get("quantity").getAsLong());
        PlayerStateSnapshot next = new PlayerStateSnapshot(
            state, List.of(), List.of(), Map.of(inventoryId, Set.of(entryId)), Map.of(entryId, originalTime));
        JsonObject inventory = JsonParser.parseString(next.payload).getAsJsonObject()
            .getAsJsonArray("inventories").get(0).getAsJsonObject();
        assertTrue(inventory.getAsJsonArray("entries").isEmpty());
        assertEquals(entryId.toString(), inventory.getAsJsonArray("expectedEntries").get(0)
            .getAsJsonObject().get("inventoryEntryId").getAsString());
        assertEquals(entryId.toString(), inventory.getAsJsonArray("deletedEntryIds").get(0).getAsString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: ACKの行が欠落または余分な応答では保存済みと判定しない。
     */
    @Test
    void rejectsNonExactAcknowledgementBeforeUpdatingLocalVersions() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        state.putInventory(inventory());
        state.replaceEntries(inventoryId, List.of(entry(10, originalTime)));
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
            state, List.of(), List.of(), Map.of(inventoryId, Set.of(entryId)), Map.of(entryId, originalTime));
        JsonObject response = ack(JsonParser.parseString(snapshot.payload).getAsJsonObject(), originalTime.plusSeconds(1));
        response.add("inventories", new JsonArray());

        assertThrows(IllegalStateException.class, () -> snapshot.validateAck(response));
        assertEquals(originalTime, state.snapshotEntries(inventoryId).getFirst().getUpdatedAt());

        JsonObject extraResponse = ack(
            JsonParser.parseString(snapshot.payload).getAsJsonObject(), originalTime.plusSeconds(1));
        JsonObject extraEntry = new JsonObject();
        extraEntry.addProperty("inventoryEntryId", UUID.randomUUID().toString());
        extraEntry.addProperty("updatedAt", originalTime.plusSeconds(1).toString());
        extraEntry.addProperty("isDeleted", false);
        extraResponse.getAsJsonArray("entries").add(extraEntry);
        assertThrows(IllegalStateException.class, () -> snapshot.validateAck(extraResponse));
        assertEquals(originalTime, state.snapshotEntries(inventoryId).getFirst().getUpdatedAt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 通常変更の統合スナップショット
     * 検証契約: metadataと装備配置のローカル編集はAPI競合判定用の最終受領版を変更しない。
     */
    @Test
    void preservesApiBaseTimesWhenEditingMetadataAndLoadout() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        state.putInventory(inventory());
        UUID loadoutId = UUID.randomUUID();
        state.putLoadout(new EquipmentLoadoutModel(
            loadoutId, account, "GAME", "test", 0, true, null, List.of(),
            originalTime, originalTime, account, account, false));
        state.updateInventoryMetadata(inventoryId, "{\"edited\":true}", account);
        state.upsertActiveLoadoutSlot(InventoryProfile.GAME, "HEAD", 0, UUID.randomUUID(), account);

        JsonObject body = JsonParser.parseString(new PlayerStateSnapshot(
            state, List.of(), List.of(), Map.of(), Map.of()).payload).getAsJsonObject();

        assertEquals(originalTime.toString(), body.getAsJsonArray("inventories").get(0)
            .getAsJsonObject().get("expectedUpdatedAt").getAsString());
        assertEquals(originalTime.toString(), body.getAsJsonArray("loadouts").get(0)
            .getAsJsonObject().get("expectedUpdatedAt").getAsString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 9. 完成状態スナップショット
     * 検証契約: 未保存の装備作成を完成スナップショットへ直列化する。
     */
    @Test
    void serializesPendingEquipmentCreationForAtomicSnapshotInsert() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        String equipmentId = UUID.randomUUID().toString();
        String statRollId = UUID.randomUUID().toString();
        EquipmentInstance equipment = new EquipmentInstance(
            equipmentId, account.toString(), "iron_sword", 0, 2, 0, 100, 100,
            originalTime.toString(), originalTime.toString(),
            List.of(new EquipmentStatRoll(statRollId, "PHYSICAL_ATTACK", "12.5", "18", 0)),
            List.of(), List.of());

        JsonObject body = JsonParser.parseString(new PlayerStateSnapshot(
            state, List.of(equipment), List.of(), Map.of(), Map.of(),
            Set.of(equipmentId.toLowerCase(java.util.Locale.ROOT)), false).payload).getAsJsonObject();
        JsonObject serialized = body.getAsJsonArray("equipment").get(0).getAsJsonObject();

        assertTrue(serialized.get("isNew").getAsBoolean());
        assertEquals("iron_sword", serialized.get("itemId").getAsString());
        assertTrue(serialized.get("expectedUpdatedAt").isJsonNull());
        JsonObject roll = serialized.getAsJsonArray("statRolls").get(0).getAsJsonObject();
        assertEquals(statRollId, roll.get("statRollId").getAsString());
        assertEquals("12.5", roll.get("min").getAsString());
        assertEquals("18", roll.get("max").getAsString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 9. 完成状態スナップショット
     * 検証契約: 未保存のinventory/loadoutは作成属性とisNewを含み、期待更新時刻なしで同一snapshotへ載せる。
     */
    @Test
    void serializesLocallyCreatedInventoryAndLoadoutForAtomicInsert() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        InventoryModel inventory = inventory();
        UUID loadoutId = UUID.randomUUID();
        state.putInventory(inventory);
        state.replaceEntriesFromLoad(inventoryId, List.of());
        state.putLoadout(new EquipmentLoadoutModel(
            loadoutId, account, "GAME", "Default", 0, true, null, List.of(),
            originalTime, originalTime, account, account, false));
        state.markDirty();

        JsonObject body = JsonParser.parseString(new PlayerStateSnapshot(
            state, List.of(), List.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), false
        ).payload).getAsJsonObject();
        JsonObject inventoryJson = body.getAsJsonArray("inventories").get(0).getAsJsonObject();
        JsonObject loadoutJson = body.getAsJsonArray("loadouts").get(0).getAsJsonObject();

        assertTrue(inventoryJson.get("isNew").getAsBoolean());
        assertTrue(inventoryJson.get("expectedUpdatedAt").isJsonNull());
        assertEquals("BAG", inventoryJson.get("inventoryType").getAsString());
        assertEquals("GAME", inventoryJson.get("inventoryProfile").getAsString());
        assertFalse(inventoryJson.get("metadataDirty").getAsBoolean());
        assertTrue(loadoutJson.get("isNew").getAsBoolean());
        assertTrue(loadoutJson.get("expectedUpdatedAt").isJsonNull());
        assertEquals("Default", loadoutJson.get("loadoutName").getAsString());
        assertTrue(loadoutJson.get("isActive").getAsBoolean());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: 全sectionの欠落・旧revision・不完全明細ACKを拒否し、完全ACKだけを受領する。
     */
    @Test
    void validatesAllSectionAcknowledgementsFromFrozenPayload() {
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
            {"clientRevision":2,"entries":[{"learnedSkillId":"%s","version":5,
             "updatedAt":"2026-09-08T17:12:53.537Z"}],"deletedIds":["%s"]}
            """.formatted(skill, removedSkill)));
        request.add("skillBindPresets", JsonParser.parseString(
            "{\"clientRevision\":3,\"presets\":[{\"presetIndex\":0,\"expectedVersion\":1}]}"));
        response.add("skillBindPresets", JsonParser.parseString(
            "{\"clientRevision\":3,\"entries\":[{\"presetIndex\":0,\"version\":2}]}"));
        request.add("skillTree", JsonParser.parseString("{\"clientRevision\":4,\"expectedVersion\":2}"));
        response.add("skillTree", JsonParser.parseString("{\"clientRevision\":4,\"version\":3}"));
        request.add("accountProgress", JsonParser.parseString(
            "{\"clientRevision\":5,\"expectedProgressVersion\":2}"));
        response.add("accountProgress", JsonParser.parseString(
            "{\"clientRevision\":5,\"progressVersion\":3}"));
        request.add("waystones", JsonParser.parseString(
            "{\"clientRevision\":6,\"unlockedWaystoneIds\":[\"ws-first\"]}"));
        response.add("waystones", JsonParser.parseString(
            "{\"clientRevision\":6,\"unlockedWaystoneIds\":[\"ws-first\"]}"));
        request.add("guideProgress", JsonParser.parseString("""
            {"clientRevision":7,"completedStepKeys":[{"guideId":"guide","stepId":"step"}]}
            """));
        response.add("guideProgress", JsonParser.parseString("""
            {"clientRevision":7,"completedStepKeys":[{"guideId":"guide","stepId":"step"}]}
            """));
        request.add("adventureRecords", JsonParser.parseString("""
            {"clientRevision":8,"mobDefeatDeltas":[{"mobId":"slime","delta":1}],
             "dungeonClearDeltas":[{"dungeonId":"ruins","delta":1}]}
            """));
        response.add("adventureRecords", JsonParser.parseString("""
            {"clientRevision":8,"mobDefeats":[{"mobId":"slime","defeatCount":10}],
             "dungeonClears":[{"dungeonId":"ruins","clearCount":2}]}
            """));
        UUID setting = UUID.randomUUID();
        request.add("playerSettings", JsonParser.parseString("""
            {"clientRevision":9,"settings":[{"userSettingId":"%s","settingKey":"DROP_LOG_DISPLAY","expectedVersion":2}]}
            """.formatted(setting)));
        response.add("playerSettings", JsonParser.parseString("""
            {"clientRevision":9,"settings":[{"userSettingId":"%s","settingKey":"DROP_LOG_DISPLAY","version":3}]}
            """.formatted(setting)));

        assertDoesNotThrow(() -> PlayerStateSnapshot.validatePayloadAck(request.toString(), response));

        JsonObject invalidTimestamp = response.deepCopy();
        invalidTimestamp.getAsJsonObject("learnedSkills").getAsJsonArray("entries").get(0)
            .getAsJsonObject().addProperty("updatedAt", "invalid");
        assertThrows(IllegalStateException.class,
            () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), invalidTimestamp));

        JsonObject extraLearnedSkill = response.deepCopy();
        JsonObject learnedSkill = new JsonObject();
        learnedSkill.addProperty("learnedSkillId", UUID.randomUUID().toString());
        learnedSkill.addProperty("version", 1);
        learnedSkill.addProperty("updatedAt", "2026-09-08T17:12:53.537Z");
        extraLearnedSkill.getAsJsonObject("learnedSkills").getAsJsonArray("entries").add(learnedSkill);
        assertThrows(IllegalStateException.class,
            () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), extraLearnedSkill));

        JsonObject extraDeletedSkill = response.deepCopy();
        extraDeletedSkill.getAsJsonObject("learnedSkills").getAsJsonArray("deletedIds")
            .add(UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class,
            () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), extraDeletedSkill));

        JsonObject extraPreset = response.deepCopy();
        JsonObject preset = new JsonObject();
        preset.addProperty("presetIndex", 99);
        preset.addProperty("version", 1);
        extraPreset.getAsJsonObject("skillBindPresets").getAsJsonArray("entries").add(preset);
        assertThrows(IllegalStateException.class,
            () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), extraPreset));

        JsonObject staleAdventureCounter = response.deepCopy();
        staleAdventureCounter.getAsJsonObject("adventureRecords").getAsJsonArray("mobDefeats")
            .get(0).getAsJsonObject().addProperty("defeatCount", 0);
        assertThrows(IllegalStateException.class,
            () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), staleAdventureCounter));

        for (String name : List.of(
            "learnedSkills", "skillBindPresets", "skillTree", "accountProgress", "waystones",
            "guideProgress", "adventureRecords", "playerSettings"
        )) {
            JsonObject missing = response.deepCopy();
            missing.remove(name);
            assertThrows(IllegalStateException.class,
                () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), missing));
            JsonObject stale = response.deepCopy();
            stale.getAsJsonObject(name).addProperty("clientRevision", 0);
            assertThrows(IllegalStateException.class,
                () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), stale));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: 不正な200 ACKは対象snapshotをブロックし、自動再送し続けない。
     */
    @Test
    void invalidAcknowledgementBlocksFurtherMutations() {
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        InventoryPersistence persistence = newPersistence(api);
        PlayerInventoryState state = persistence.load(account);
        when(api.saveSnapshot(anyString())).thenReturn(new JsonObject());

        state.markDirty();
        assertFalse(persistence.saveNow(state));
        assertTrue(persistence.isPlayerStateBlocked(account));
        persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);

        verify(api, times(1)).saveSnapshot(anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: POSTの200 ACKを検証できない場合は固定ACKを照会し、完全なら保存成功として確定する。
     */
    @Test
    void recoversInvalidPostAcknowledgementFromCompletedSnapshotLookup() {
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        InventoryPersistence persistence = newPersistence(api);
        PlayerInventoryState state = persistence.load(account);
        AtomicReference<String> sentPayload = new AtomicReference<>();
        when(api.saveSnapshot(anyString())).thenAnswer(call -> {
            sentPayload.set(call.getArgument(0, String.class));
            return new JsonObject();
        });
        when(api.findCompletedSnapshot(any(UUID.class), any(UUID.class))).thenAnswer(call -> ack(
            JsonParser.parseString(sentPayload.get()).getAsJsonObject(),
            originalTime.plusSeconds(1)));

        state.markDirty();

        assertTrue(persistence.saveNow(state));
        assertFalse(persistence.isPlayerStateBlocked(account));
        verify(api).findCompletedSnapshot(any(UUID.class), any(UUID.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: section固有のACK適用失敗を保存成功にせず、対象snapshotをブロックして再送しない。
     */
    @Test
    void participantAcknowledgementFailureBlocksSnapshot() {
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        InventoryPersistence persistence = newPersistence(api);
        PlayerInventoryState state = persistence.load(account);
        JsonObject sectionPayload = JsonParser.parseString(
            "{\"clientRevision\":1,\"unlockedWaystoneIds\":[\"ws-first\"]}").getAsJsonObject();
        persistence.registerStateParticipant(ignored -> new PlayerStateSection(
            "waystones", sectionPayload, ignoredAck -> {
                throw new IllegalStateException("participant rejected acknowledgement");
            }));
        when(api.saveSnapshot(anyString())).thenAnswer(call -> {
            JsonObject request = JsonParser.parseString(call.getArgument(0, String.class)).getAsJsonObject();
            JsonObject response = ack(request, originalTime.plusSeconds(1));
            response.add("waystones", request.get("waystones").deepCopy());
            return response;
        });

        assertFalse(persistence.saveNow(state));
        assertTrue(persistence.isPlayerStateBlocked(account));
        persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);

        verify(api, times(1)).saveSnapshot(anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 重要操作のACKと補償
     * 検証契約: 重要操作の保存失敗本文は後送せず、補償後の次回snapshotを新規要求として送れる。
     */
    @Test
    void discardsFailedCriticalSnapshotBeforeNextSave() {
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        InventoryPersistence persistence = newPersistence(api);
        PlayerInventoryState state = persistence.load(account);
        when(api.saveSnapshot(anyString()))
            .thenThrow(new IllegalStateException("transport failed"))
            .thenAnswer(call -> ack(
                JsonParser.parseString(call.getArgument(0, String.class)).getAsJsonObject(),
                originalTime.plusSeconds(1)));

        assertFalse(persistence.saveCriticalNow(state));
        state.replaceEntries(inventoryId, List.of(entry(9, originalTime)));
        persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);

        assertFalse(persistence.hasPendingChanges(state));
        verify(api, times(2)).saveSnapshot(anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 重要操作のACKと補償
     * 検証契約: 重要操作の保存済み結果をACKで確定できない場合はsnapshotを保持し、ローカル補償を許可しない。
     */
    @Test
    void keepsCriticalSnapshotBlockedWhenAcknowledgementCannotBeResolved() {
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        InventoryPersistence persistence = newPersistence(api);
        PlayerInventoryState state = persistence.load(account);
        when(api.saveSnapshot(anyString())).thenReturn(new JsonObject());

        assertThrows(PlayerStateAcknowledgementException.class, () -> persistence.saveCriticalNow(state));
        assertTrue(persistence.isPlayerStateBlocked(account));

        persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);
        verify(api, times(1)).saveSnapshot(anyString());
        verify(api).findCompletedSnapshot(any(UUID.class), any(UUID.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: waystone ACKの順序・重複・余分・非文字列を拒否する。
     */
    @Test
    void rejectsNonExactWaystoneAcknowledgements() {
        PlayerInventoryState state = new PlayerInventoryState(account);
        JsonObject request = JsonParser.parseString(new PlayerStateSnapshot(
            state, List.of(), List.of(), Map.of(), Map.of()).payload).getAsJsonObject();
        request.add("waystones", JsonParser.parseString(
            "{\"clientRevision\":1,\"unlockedWaystoneIds\":[\"a\",\"b\"]}"));
        for (String values : List.of("[\"b\",\"a\"]", "[\"a\",\"b\",\"b\"]", "[\"a\",\"b\",\"c\"]", "[1,2]")) {
            JsonObject response = ack(request, originalTime.plusSeconds(1));
            response.add("waystones", JsonParser.parseString(
                "{\"clientRevision\":1,\"unlockedWaystoneIds\":" + values + "}"));
            assertThrows(IllegalStateException.class,
                () -> PlayerStateSnapshot.validatePayloadAck(request.toString(), response));
        }
    }

    private InventoryPersistence newPersistence(PlayerStateRepository api) {
        InventoryRepository inventories = mock(InventoryRepository.class);
        when(inventories.findByAccountId(account)).thenReturn(List.of(inventory()));
        when(inventories.findEntries(inventoryId)).thenReturn(List.of(entry(10, originalTime)));
        return new InventoryPersistence(
            inventories, mock(EquipmentLoadoutRepository.class), mock(ItemService.class), api);
    }

    private JsonObject ack(JsonObject request, LocalDateTime time) {
        JsonObject response = new JsonObject();
        response.add("snapshotId", request.get("snapshotId"));
        response.add("accountId", request.get("accountId"));
        JsonArray entries = new JsonArray();
        JsonArray inventories = new JsonArray();
        for (var inventoryElement : request.getAsJsonArray("inventories")) {
            JsonObject inventory = inventoryElement.getAsJsonObject();
            inventories.add(version("inventoryId", inventory.get("inventoryId"), time, null));
            Set<String> activeIds = new java.util.HashSet<>();
            for (var entryElement : inventory.getAsJsonArray("entries")) {
                JsonObject entry = entryElement.getAsJsonObject();
                String id = entry.get("inventoryEntryId").getAsString();
                activeIds.add(id);
                entries.add(version("inventoryEntryId", entry.get("inventoryEntryId"), time, false));
            }
            for (var expectedElement : inventory.getAsJsonArray("expectedEntries")) {
                JsonObject expected = expectedElement.getAsJsonObject();
                if (!activeIds.contains(expected.get("inventoryEntryId").getAsString())) {
                    entries.add(version("inventoryEntryId", expected.get("inventoryEntryId"), time, true));
                }
            }
        }
        JsonArray loadouts = new JsonArray();
        for (var loadoutElement : request.getAsJsonArray("loadouts")) {
            JsonObject loadout = loadoutElement.getAsJsonObject();
            loadouts.add(version("equipmentLoadoutId", loadout.get("equipmentLoadoutId"), time, null));
        }
        JsonArray equipment = new JsonArray();
        for (var equipmentElement : request.getAsJsonArray("equipment")) {
            JsonObject item = equipmentElement.getAsJsonObject();
            equipment.add(version("equipmentInstanceId", item.get("equipmentInstanceId"), time, null));
        }
        response.add("entries", entries);
        response.add("inventories", inventories);
        response.add("loadouts", loadouts);
        response.add("equipment", equipment);
        return response;
    }

    private JsonObject version(String field, com.google.gson.JsonElement id, LocalDateTime time, Boolean deleted) {
        JsonObject row = new JsonObject();
        row.add(field, id.deepCopy());
        row.addProperty("updatedAt", time.toString());
        if (deleted != null) row.addProperty("isDeleted", deleted);
        return row;
    }

    private InventoryModel inventory() {
        return new InventoryModel(
            inventoryId, account, InventoryType.BAG, "GAME", 100, true, null,
            originalTime, originalTime, account, account, false);
    }

    private InventoryEntryModel entry(long amount, LocalDateTime timestamp) {
        return new InventoryEntryModel(
            entryId, inventoryId, 1, "MATERIAL", "material.test", null, null,
            amount, null, originalTime, timestamp, account, account, false);
    }
}
