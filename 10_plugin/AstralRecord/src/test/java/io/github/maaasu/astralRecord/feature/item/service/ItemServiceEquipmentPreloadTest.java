package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemServiceEquipmentPreloadTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 候補収集用cache-only参照は未ロード装備で同期APIへfallbackせず、事前ロードが正規化重複を1回だけ取得した後は同じ参照から返す。
     */
    @Test
    void cacheOnlyCandidateLookupNeverFallsBackAndPreloadDeduplicatesIo() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        EquipmentInstance instance = instance(instanceId);

        assertNull(service.findLoadedEquipmentInstanceById(instanceId));
        verify(repository, never()).findEquipmentInstanceById(instanceId);

        when(repository.findEquipmentInstanceById(instanceId)).thenReturn(instance);
        ItemService.EquipmentPreloadResult result = service.preloadEquipmentInstances(List.of(
            instanceId,
            instanceId,
            instanceId.toUpperCase(Locale.ROOT)
        ));

        assertEquals(ItemService.EquipmentPreloadResult.COMPLETE, result);
        assertSame(instance, service.findLoadedEquipmentInstanceById(instanceId));
        assertEquals(
            ItemService.EquipmentPreloadResult.COMPLETE,
            service.preloadEquipmentInstances(List.of(instanceId))
        );
        verify(repository, times(1)).findEquipmentInstanceById(instanceId);
    }

    private static EquipmentInstance instance(String instanceId) {
        return new EquipmentInstance(
            instanceId,
            UUID.randomUUID().toString(),
            "debug_sword",
            0,
            0,
            0,
            100,
            80,
            "2026-08-10T00:00:00",
            "2026-08-10T00:00:00",
            List.of(),
            List.of(),
            List.of()
        );
    }
}
