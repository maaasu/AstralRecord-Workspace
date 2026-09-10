package io.github.maaasu.astralRecord.feature.inventory.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateOutcomeUnknownException;
import io.github.maaasu.astralRecord.feature.mutation.repository.PlayerStateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryUnknownOutcomeTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 重要操作のACKと補償
     * 検証契約: 重要操作の結果不明ではsnapshotを破棄せず、backoff後も同一IDと本文を再送してACKを確定する。
     */
    @Test
    void retainsIdenticalCriticalPayloadUntilRecovered() throws Exception {
        PlayerStateRepository api = mock(PlayerStateRepository.class);
        InventoryPersistence persistence = new InventoryPersistence(mock(InventoryRepository.class),
            mock(EquipmentLoadoutRepository.class), mock(ItemService.class), api);
        PlayerInventoryState state = new PlayerInventoryState(UUID.randomUUID());
        when(api.saveSnapshot(anyString()))
            .thenThrow(new PlayerStateOutcomeUnknownException(null))
            .thenAnswer(call -> emptyAck(call.getArgument(0, String.class)));

        assertThrows(PlayerStateOutcomeUnknownException.class, () -> persistence.saveCriticalNow(state));
        assertTrue(persistence.hasPendingChanges(state));
        assertFalse(persistence.isPlayerStateBlocked(state.getAccountId()));
        assertThrows(PlayerStateOutcomeUnknownException.class, () -> persistence.saveCriticalNow(state));
        verify(api, times(1)).saveSnapshot(anyString());

        Thread.sleep(1_100L);
        assertTrue(persistence.saveCriticalNow(state));
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(api, times(2)).saveSnapshot(payloads.capture());
        assertEquals(payloads.getAllValues().get(0), payloads.getAllValues().get(1));
        assertFalse(persistence.hasPendingChanges(state));
    }

    private static JsonObject emptyAck(String payload) {
        JsonObject request = JsonParser.parseString(payload).getAsJsonObject();
        JsonObject ack = new JsonObject();
        ack.add("snapshotId", request.get("snapshotId"));
        ack.add("accountId", request.get("accountId"));
        for (String field : new String[]{"inventories", "entries", "loadouts", "equipment"}) {
            ack.add(field, new JsonArray());
        }
        return ack;
    }
}
