package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuestServicePersistenceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 9. 永続化世代
     * 検証契約: 最新ロード要求だけをセッションへ適用し、古い非同期結果を捨てる。
     */
    @Test
    void appliesOnlyTheLatestLoadToken() {
        UUID accountId = UUID.randomUUID();
        QuestStatePersistenceCoordinator coordinator = new QuestStatePersistenceCoordinator(
            ignored -> emptyState(accountId));

        QuestStatePersistenceCoordinator.LoadedState first = coordinator.load(accountId);
        QuestStatePersistenceCoordinator.LoadedState second = coordinator.load(accountId);

        assertNull(coordinator.apply(first));
        assertNotNull(coordinator.apply(second));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 9. 永続化世代
     * 検証契約: SQL ACK待ちの退出直後の再ログインでDBの古い状態をruntimeへ上書きしない。
     */
    @Test
    void quickRelogUsesRetainedUnacknowledgedState() {
        UUID accountId = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        QuestStatePersistenceCoordinator coordinator = new QuestStatePersistenceCoordinator(ignored -> {
            loads.incrementAndGet();
            return emptyState(accountId);
        });
        QuestPlayerState runtime = emptyState(accountId);
        runtime.activeQuests().put("alpha", new QuestProgress("alpha", 1L, null, Map.of(), false));
        coordinator.recordLatest(runtime);
        coordinator.markReleased(accountId);

        QuestPlayerState reloaded = coordinator.apply(coordinator.load(accountId));

        assertNotNull(reloaded);
        assertEquals(0, loads.get());
        assertNotNull(reloaded.activeQuests().get("alpha"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/29-quest/29_1-モデル定義.md
     * 章・見出し: # 29_1-モデル定義 > ## 9. 永続化世代
     * 検証契約: SQL ACK確認後に解放された次回ログインはDBから状態を読み直す。
     */
    @Test
    void acknowledgedReleasedStateIsEvicted() {
        UUID accountId = UUID.randomUUID();
        AtomicReference<QuestPlayerState> database = new AtomicReference<>(emptyState(accountId));
        AtomicInteger loads = new AtomicInteger();
        QuestStatePersistenceCoordinator coordinator = new QuestStatePersistenceCoordinator(ignored -> {
            loads.incrementAndGet();
            return database.get();
        });
        QuestPlayerState runtime = emptyState(accountId);
        runtime.activeQuests().put("alpha", new QuestProgress("alpha", 1L, null, Map.of(), false));
        coordinator.recordLatest(runtime);
        coordinator.markReleased(accountId);
        coordinator.evictReleased(accountId);

        QuestPlayerState loaded = coordinator.apply(coordinator.load(accountId));

        assertNotNull(loaded);
        assertEquals(1, loads.get());
        assertNull(loaded.activeQuests().get("alpha"));
    }

    private static QuestPlayerState emptyState(UUID accountId) {
        return new QuestPlayerState(accountId, Map.of(), Map.of(), Map.of());
    }
}
