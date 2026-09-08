package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class ItemServiceDurabilityMergeTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 3. 所有インスタンス > ### ローカル装備状態の保存
     * 検証契約: 古いcaptureのACKは後続の装備変更をdirtyに残しつつ、API確定updatedAtだけを現在個体へ反映する。
     */
    @Test
    void staleEquipmentCaptureAckKeepsNewerDirtyStateAndUpdatesBaseTimestamp() {
        ItemService service = new ItemService(mock(ItemRepository.class), mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        EquipmentInstance first = instance(instanceId, accountId, 1, 0, 100, 90);
        EquipmentInstance second = instance(instanceId, accountId, 2, 0, 110, 100);

        assertNotNull(service.applyLocalEquipmentInstance(first));
        List<EquipmentInstance> captured = service.snapshotDirtyEquipmentState(UUID.fromString(accountId));
        assertNotNull(service.applyLocalEquipmentInstance(second));

        service.acknowledgeEquipmentState(
            UUID.fromString(accountId), captured, Map.of(instanceId, "2026-09-06T12:00:00")
        );

        EquipmentInstance current = service.findLoadedEquipmentInstanceById(instanceId);
        assertNotNull(current);
        assertEquals(2, current.getEnhanceLevel());
        assertEquals("2026-09-06T12:00:00", current.getUpdatedAt());
        assertEquals(1, service.snapshotDirtyEquipmentState(UUID.fromString(accountId)).size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 4. 装備耐久値 > ### 耐久値キャッシュ更新
     * 検証契約: ローカル耐久更新は表示用の現在時刻をupdatedAtへ書かず、次のsnapshotがAPI expectedUpdatedAtに使うbase timestampを保持する。
     */
    @Test
    void localDurabilityUpdateKeepsApiBaseTimestampForSnapshotConflictControl() {
        ItemRepository repository = mock(ItemRepository.class);
        ItemService service = new ItemService(repository, mock(SetEffectRepository.class));
        String instanceId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        EquipmentInstance base = new EquipmentInstance(
            instanceId, accountId, "debug_sword", 0, 0, 0, 100, 100,
            "2026-08-10T00:00:00", "2026-08-11T12:00:00", List.of(), List.of(), List.of());
        assertNotNull(service.applyLocalEquipmentInstance(base));
        EquipmentInstance updated = service.updateEquipmentDurability(instanceId, 70, accountId);

        assertNotNull(updated);
        assertEquals("2026-08-11T12:00:00", updated.getUpdatedAt());
        assertEquals("2026-08-11T12:00:00",
            service.snapshotDirtyEquipmentState(UUID.fromString(accountId)).getFirst().getUpdatedAt());
    }

    private static EquipmentInstance instance(
        String instanceId,
        String accountId,
        int enhanceLevel,
        int rank,
        int durabilityMax,
        int durabilityValue
    ) {
        return new EquipmentInstance(
            instanceId,
            accountId,
            "debug_sword",
            enhanceLevel,
            0,
            rank,
            durabilityMax,
            durabilityValue,
            "2026-08-10T00:00:00",
            "2026-08-10T00:00:00",
            List.of(),
            List.of(),
            List.of()
        );
    }
}
