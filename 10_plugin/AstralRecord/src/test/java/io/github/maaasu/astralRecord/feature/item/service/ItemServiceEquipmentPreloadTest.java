package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit
     * 検証契約: トレード後の owner 変更を反映するため、強制再読込は既存の送信側 cache を受取側の API 正本で置換する。
     */
    @Test
    void forcedReloadReplacesTransferredEquipmentOwnerInCache() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        EquipmentInstance senderOwned = instance(instanceId);
        UUID recipientAccountId = UUID.randomUUID();
        EquipmentInstance recipientOwned = new EquipmentInstance(
            instanceId,
            recipientAccountId.toString(),
            senderOwned.getItemId(),
            senderOwned.getEnhanceLevel(),
            senderOwned.getRuneMaxSlots(),
            senderOwned.getTranscendenceRank(),
            senderOwned.getDurabilityMax(),
            senderOwned.getDurabilityValue(),
            senderOwned.getCreatedAt(),
            senderOwned.getUpdatedAt(),
            senderOwned.getStatRolls(),
            senderOwned.getEnchants(),
            senderOwned.getRunes()
        );

        when(repository.findEquipmentInstanceById(instanceId))
            .thenReturn(senderOwned)
            .thenReturn(recipientOwned);
        when(repository.updateEquipmentDurability(
            instanceId,
            75,
            recipientAccountId.toString()
        )).thenReturn(recipientOwned);
        service.preloadEquipmentInstances(List.of(instanceId));
        service.updateEquipmentDurability(instanceId, 75, "updated-by");

        assertEquals(ItemService.EquipmentPreloadResult.COMPLETE,
            service.reloadEquipmentInstances(List.of(instanceId)));

        assertEquals(75, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
        assertEquals(recipientAccountId, UUID.fromString(
            service.findLoadedEquipmentInstanceById(instanceId).getAccountId()));
        assertTrue(service.hasDirtyEquipmentDurability(recipientAccountId));
        assertTrue(service.flushDirtyEquipmentDurability(recipientAccountId));
        verify(repository).updateEquipmentDurability(instanceId, 75, recipientAccountId.toString());
        verify(repository, times(2)).findEquipmentInstanceById(instanceId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit
     * 検証契約: API正本が既に dirty durability を含む場合、reload は未保存差分を二重適用しない。
     */
    @Test
    void forcedReloadDoesNotDoubleApplyAlreadyPersistedDurability() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        EquipmentInstance senderOwned = instance(instanceId);
        EquipmentInstance recipientOwned = new EquipmentInstance(
            instanceId,
            UUID.randomUUID().toString(),
            senderOwned.getItemId(),
            senderOwned.getEnhanceLevel(),
            senderOwned.getRuneMaxSlots(),
            senderOwned.getTranscendenceRank(),
            senderOwned.getDurabilityMax(),
            75,
            senderOwned.getCreatedAt(),
            senderOwned.getUpdatedAt(),
            senderOwned.getStatRolls(),
            senderOwned.getEnchants(),
            senderOwned.getRunes()
        );
        when(repository.findEquipmentInstanceById(instanceId))
            .thenReturn(senderOwned)
            .thenReturn(recipientOwned);
        service.preloadEquipmentInstances(List.of(instanceId));
        service.updateEquipmentDurability(instanceId, 75, "updated-by");

        assertEquals(ItemService.EquipmentPreloadResult.COMPLETE,
            service.reloadEquipmentInstances(List.of(instanceId)));

        assertEquals(75, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit
     * 検証契約: API耐久値が dirty の base/current と一致しない場合、推測で cache を置換せず recovery へ返す。
     */
    @Test
    void forcedReloadRejectsUnmatchedDurabilityGeneration() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        EquipmentInstance senderOwned = instance(instanceId);
        EquipmentInstance conflictingApiValue = new EquipmentInstance(
            instanceId,
            UUID.randomUUID().toString(),
            senderOwned.getItemId(),
            senderOwned.getEnhanceLevel(),
            senderOwned.getRuneMaxSlots(),
            senderOwned.getTranscendenceRank(),
            senderOwned.getDurabilityMax(),
            60,
            senderOwned.getCreatedAt(),
            senderOwned.getUpdatedAt(),
            senderOwned.getStatRolls(),
            senderOwned.getEnchants(),
            senderOwned.getRunes()
        );
        when(repository.findEquipmentInstanceById(instanceId))
            .thenReturn(senderOwned)
            .thenReturn(conflictingApiValue);
        service.preloadEquipmentInstances(List.of(instanceId));
        service.updateEquipmentDurability(instanceId, 75, "updated-by");

        assertEquals(ItemService.EquipmentPreloadResult.UNAVAILABLE,
            service.reloadEquipmentInstances(List.of(instanceId)));

        assertEquals(75, service.findLoadedEquipmentInstanceById(instanceId).getDurabilityValue());
        assertEquals(senderOwned.getAccountId(),
            service.findLoadedEquipmentInstanceById(instanceId).getAccountId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit
     * 検証契約: reload 中に cache が更新された場合、古い API 応答を公開せず recovery 結果を返す。
     */
    @Test
    void forcedReloadRejectsResponseAfterCacheChangedDuringFetch() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        EquipmentInstance senderOwned = instance(instanceId);
        UUID recipientAccountId = UUID.randomUUID();
        EquipmentInstance recipientOwned = new EquipmentInstance(
            instanceId,
            recipientAccountId.toString(),
            senderOwned.getItemId(),
            senderOwned.getEnhanceLevel(),
            senderOwned.getRuneMaxSlots(),
            senderOwned.getTranscendenceRank(),
            senderOwned.getDurabilityMax(),
            senderOwned.getDurabilityValue(),
            senderOwned.getCreatedAt(),
            senderOwned.getUpdatedAt(),
            senderOwned.getStatRolls(),
            senderOwned.getEnchants(),
            senderOwned.getRunes()
        );
        when(repository.findEquipmentInstanceById(instanceId))
            .thenReturn(senderOwned)
            .thenAnswer(invocation -> {
                service.updateEquipmentDurability(instanceId, 70, "updated-by");
                return recipientOwned;
            });
        service.preloadEquipmentInstances(List.of(instanceId));

        assertEquals(ItemService.EquipmentPreloadResult.UNAVAILABLE,
            service.reloadEquipmentInstances(List.of(instanceId)));

        EquipmentInstance current = service.findLoadedEquipmentInstanceById(instanceId);
        assertEquals(senderOwned.getAccountId(), current.getAccountId());
        assertEquals(70, current.getDurabilityValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 3. Commit
     * 検証契約: トレード後のルーン所有者変更も、既存 cache を API 正本で置換する。
     */
    @Test
    void forcedReloadReplacesTransferredRuneOwnerInCache() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        RuneInstance senderOwned = rune(instanceId);
        UUID recipientAccountId = UUID.randomUUID();
        RuneInstance recipientOwned = new RuneInstance(
            instanceId,
            recipientAccountId.toString(),
            senderOwned.getItemId(),
            senderOwned.getCreatedAt(),
            senderOwned.getUpdatedAt(),
            senderOwned.getStatRolls()
        );

        when(repository.findRuneInstanceById(instanceId))
            .thenReturn(senderOwned)
            .thenReturn(recipientOwned);
        service.findRuneInstanceById(instanceId);

        assertEquals(ItemService.EquipmentPreloadResult.COMPLETE,
            service.reloadRuneInstances(List.of(instanceId)));

        assertEquals(recipientAccountId, UUID.fromString(
            service.findRuneInstanceById(instanceId).getAccountId()));
        verify(repository, times(2)).findRuneInstanceById(instanceId);
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

    private static RuneInstance rune(String instanceId) {
        return new RuneInstance(
            instanceId,
            UUID.randomUUID().toString(),
            "minor_rune",
            "2026-08-10T00:00:00",
            "2026-08-10T00:00:00",
            List.of()
        );
    }
}
