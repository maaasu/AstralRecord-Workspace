package io.github.maaasu.astralRecord.feature.adventurerecord.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AdventureRecordStateServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 討伐・踏破のローカル加算
     * 検証契約: 捕捉後に増えた差分は先行ACKで消さず、次のsnapshotへ残す。
     */
    @Test
    void acknowledgementSubtractsOnlyCapturedDeltas() {
        UUID accountId = UUID.randomUUID();
        InventoryService inventoryService = mock(InventoryService.class);
        AdventureRecordStateService service = new AdventureRecordStateService(inventoryService);
        service.recordMobDefeat(accountId, "slime", MobCategory.ENEMY);
        service.recordDungeonClear(accountId, "ruins");
        PlayerStateSection first = service.snapshotPlayerState(accountId);
        assertNotNull(first);

        service.recordMobDefeat(accountId, "slime", MobCategory.ENEMY);
        first.acknowledge().accept(ack(first.payload().getAsJsonObject(), 1L, 1L));

        PlayerStateSection remaining = service.snapshotPlayerState(accountId);
        assertNotNull(remaining);
        JsonObject payload = remaining.payload().getAsJsonObject();
        assertEquals(1L, payload.getAsJsonArray("mobDefeatDeltas").get(0)
            .getAsJsonObject().get("delta").getAsLong());
        assertEquals(0, payload.getAsJsonArray("dungeonClearDeltas").size());
        verify(inventoryService, times(3)).queueLocalPlayerSave(accountId);

        remaining.acknowledge().accept(ack(remaining.payload().getAsJsonObject(), 2L, 0L));
        assertNull(service.snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## ACK検証と失敗処理
     * 検証契約: 送信delta未満の累計ACKを拒否し、未保存の冒険記録差分を破棄しない。
     */
    @Test
    void staleCounterAcknowledgementKeepsCapturedDeltas() {
        UUID accountId = UUID.randomUUID();
        InventoryService inventoryService = mock(InventoryService.class);
        AdventureRecordStateService service = new AdventureRecordStateService(inventoryService);
        service.recordMobDefeat(accountId, "slime", MobCategory.ENEMY);
        PlayerStateSection snapshot = service.snapshotPlayerState(accountId);
        assertNotNull(snapshot);

        assertThrows(IllegalStateException.class,
            () -> snapshot.acknowledge().accept(ack(snapshot.payload().getAsJsonObject(), 0L, 0L)));

        PlayerStateSection retained = service.snapshotPlayerState(accountId);
        assertNotNull(retained);
        assertEquals(1L, retained.payload().getAsJsonObject().getAsJsonArray("mobDefeatDeltas")
            .get(0).getAsJsonObject().get("delta").getAsLong());
    }

    private JsonObject ack(JsonObject payload, long mobCount, long dungeonCount) {
        JsonObject ack = new JsonObject();
        ack.add("clientRevision", payload.get("clientRevision"));
        JsonArray mobs = new JsonArray();
        if (mobCount > 0L) {
            JsonObject row = new JsonObject();
            row.addProperty("mobId", "slime");
            row.addProperty("defeatCount", mobCount);
            mobs.add(row);
        }
        ack.add("mobDefeats", mobs);
        JsonArray dungeons = new JsonArray();
        if (dungeonCount > 0L) {
            JsonObject row = new JsonObject();
            row.addProperty("dungeonId", "ruins");
            row.addProperty("clearCount", dungeonCount);
            dungeons.add(row);
        }
        ack.add("dungeonClears", dungeons);
        return ack;
    }
}
