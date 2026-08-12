package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonArchiveGui;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DungeonArchiveMergeTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 非同期保存の応答順が前後しても、踏破回数を古い永続化結果で減らさない。
     */
    @Test
    void keepsNewerPersistedClearWhenResponsesArriveOutOfOrder() {
        DungeonArchiveGui.ArchiveDungeon newer = archive(2, "2026-08-12T02:00:00Z");
        DungeonArchiveGui.ArchiveDungeon stale = archive(1, "2026-08-12T01:00:00Z");

        List<DungeonArchiveGui.ArchiveDungeon> merged = DungeonService.mergeArchive(
                List.of(stale), List.of(newer));

        assertEquals(1, merged.size());
        assertEquals(2, merged.getFirst().clearCount());
        assertEquals(newer.lastClearedAt(), merged.getFirst().lastClearedAt());
    }

    private DungeonArchiveGui.ArchiveDungeon archive(long clearCount, String lastClearedAt) {
        return new DungeonArchiveGui.ArchiveDungeon(
                "dungeon", "ダンジョン", clearCount, Instant.parse(lastClearedAt), List.of());
    }
}
