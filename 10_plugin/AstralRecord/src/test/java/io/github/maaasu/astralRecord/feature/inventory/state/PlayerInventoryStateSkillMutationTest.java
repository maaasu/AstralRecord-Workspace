package io.github.maaasu.astralRecord.feature.inventory.state;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInventoryStateSkillMutationTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: APIの素材消費は対象entryだけへ反映し、待機中に生じた無関係なインベントリ変更を置換しない。
     */
    @Test
    void authoritativeReconciliationUpdatesOnlyTheTargetEntry() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID unrelatedId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.replaceEntriesFromLoad(inventoryId, List.of(
            entry(targetId, inventoryId, 1, "cooldown_sigil", 2L, accountId),
            entry(unrelatedId, inventoryId, 2, "new_drop", 7L, accountId)
        ));

        LocalDateTime authoritativeUpdatedAt = LocalDateTime.now().plusSeconds(1);
        state.reconcileAuthoritativeEntry(
            targetId,
            entry(targetId, inventoryId, 1, "cooldown_sigil", 1L, accountId, authoritativeUpdatedAt)
        );

        List<InventoryEntryModel> entries = state.snapshotEntries(inventoryId);
        assertEquals(1L, entries.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(targetId))
            .findFirst().orElseThrow().getQuantity());
        assertEquals(authoritativeUpdatedAt, entries.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(targetId))
            .findFirst().orElseThrow().getUpdatedAt());
        assertEquals(7L, entries.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(unrelatedId))
            .findFirst().orElseThrow().getQuantity());
        assertFalse(state.isDirty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 通信結果再確認でも対象UUIDだけをAPI正本へ合わせる。
     */
    @Test
    void authoritativeReconciliationRemovesOnlyTheRequestedEntry() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID unrelatedId = UUID.randomUUID();
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.replaceEntriesFromLoad(inventoryId, List.of(
            entry(targetId, inventoryId, 1, "00_skill_gem_mage_fireball", 1L, accountId),
            entry(unrelatedId, inventoryId, 2, "new_drop", 1L, accountId)
        ));

        state.reconcileAuthoritativeEntry(targetId, null);

        List<InventoryEntryModel> entries = state.snapshotEntries(inventoryId);
        assertFalse(entries.stream().anyMatch(entry -> entry.getInventoryEntryId().equals(targetId)));
        assertTrue(entries.stream().anyMatch(entry -> entry.getInventoryEntryId().equals(unrelatedId)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 6. アカウント別保存調停
     * 検証契約: 一括保存応答は送信後も同一のentryだけへ反映し、待機中の並行変更を上書きしない。
     */
    @Test
    void persistenceAcknowledgementPreservesConcurrentEntryChanges() {
        UUID accountId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        UUID unchangedId = UUID.randomUUID();
        UUID changedId = UUID.randomUUID();
        LocalDateTime originalVersion = LocalDateTime.now();
        InventoryEntryModel unchanged = entry(
            unchangedId, inventoryId, 1, "unchanged", 1L, accountId, originalVersion);
        InventoryEntryModel beforeChange = entry(
            changedId, inventoryId, 2, "changed", 2L, accountId, originalVersion);
        List<InventoryEntryModel> submitted = List.of(unchanged, beforeChange);
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        state.replaceEntriesFromLoad(inventoryId, submitted);

        LocalDateTime concurrentVersion = originalVersion.plusSeconds(1);
        state.replaceEntries(inventoryId, List.of(
            unchanged,
            entry(changedId, inventoryId, 2, "changed", 8L, accountId, concurrentVersion)
        ));
        LocalDateTime persistedVersion = originalVersion.plusSeconds(2);
        state.acknowledgePersistedEntries(inventoryId, submitted, List.of(
            entry(unchangedId, inventoryId, 1, "unchanged", 1L, accountId, persistedVersion),
            entry(changedId, inventoryId, 2, "changed", 2L, accountId, persistedVersion)
        ));

        List<InventoryEntryModel> entries = state.snapshotEntries(inventoryId);
        assertEquals(persistedVersion, entries.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(unchangedId))
            .findFirst().orElseThrow().getUpdatedAt());
        InventoryEntryModel concurrent = entries.stream()
            .filter(entry -> entry.getInventoryEntryId().equals(changedId))
            .findFirst().orElseThrow();
        assertEquals(8L, concurrent.getQuantity());
        assertEquals(concurrentVersion, concurrent.getUpdatedAt());
        assertTrue(state.isDirty());
    }

    private static InventoryEntryModel entry(
        UUID entryId,
        UUID inventoryId,
        int slot,
        String itemId,
        long quantity,
        UUID accountId
    ) {
        LocalDateTime now = LocalDateTime.now();
        return entry(entryId, inventoryId, slot, itemId, quantity, accountId, now);
    }

    private static InventoryEntryModel entry(
        UUID entryId,
        UUID inventoryId,
        int slot,
        String itemId,
        long quantity,
        UUID accountId,
        LocalDateTime updatedAt
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            entryId,
            inventoryId,
            slot,
            "material",
            itemId,
            null,
            null,
            quantity,
            null,
            now,
            updatedAt,
            accountId,
            accountId,
            false
        );
    }
}
