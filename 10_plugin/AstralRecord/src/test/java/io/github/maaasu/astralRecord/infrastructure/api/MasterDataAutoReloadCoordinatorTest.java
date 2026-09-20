package io.github.maaasu.astralRecord.infrastructure.api;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.maaasu.astralRecord.infrastructure.api.MasterDataAutoReloadCoordinator.Observation.BASELINE_CAPTURED;
import static io.github.maaasu.astralRecord.infrastructure.api.MasterDataAutoReloadCoordinator.Observation.DEFERRED;
import static io.github.maaasu.astralRecord.infrastructure.api.MasterDataAutoReloadCoordinator.Observation.INCOMPLETE_SUCCESS;
import static io.github.maaasu.astralRecord.infrastructure.api.MasterDataAutoReloadCoordinator.Observation.MISSING_RUN_ID;
import static io.github.maaasu.astralRecord.infrastructure.api.MasterDataAutoReloadCoordinator.Observation.NONE;
import static io.github.maaasu.astralRecord.infrastructure.api.MasterDataAutoReloadCoordinator.Observation.RELOAD_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MasterDataAutoReloadCoordinatorTest {
    private static final String FIRST_SUCCESS = "2026-09-20T01:00:00Z";
    private static final String SECOND_SUCCESS = "2026-09-20T01:01:00Z";

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## マスターデータ自動再読込
     * 検証契約: 初回の成功応答は比較基準にだけ使用し、同じ Seeder 実行では再読込しない。
     */
    @Test
    void capturesInitialSuccessfulRunWithoutReloading() {
        MasterDataAutoReloadCoordinator coordinator = new MasterDataAutoReloadCoordinator();
        UUID initialRunId = UUID.randomUUID();

        assertEquals(BASELINE_CAPTURED, coordinator.observe(success(initialRunId, FIRST_SUCCESS)));
        assertEquals(NONE, coordinator.observe(success(initialRunId, FIRST_SUCCESS)));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## マスターデータ自動再読込
     * 検証契約: null ID と成功日時のない成功応答は再読込せず、次回取得を継続する。
     */
    @Test
    void rejectsIncompleteHealthSnapshotsWithoutReloading() {
        MasterDataAutoReloadCoordinator coordinator = new MasterDataAutoReloadCoordinator();
        UUID runId = UUID.randomUUID();

        assertEquals(MISSING_RUN_ID, coordinator.observe(new MasterDataHealthSnapshot(null, null, null)));
        assertEquals(INCOMPLETE_SUCCESS, coordinator.observe(new MasterDataHealthSnapshot(runId, "SUCCEEDED", null)));
        assertEquals(RELOAD_REQUIRED, coordinator.observe(success(runId, FIRST_SUCCESS)));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## マスターデータ自動再読込
     * 検証契約: FAILED/RUNNING は開始せず、同じ実行 ID が SUCCEEDED へ遷移した後だけ開始する。
     */
    @Test
    void waitsForSucceededStatusBeforeReloading() {
        MasterDataAutoReloadCoordinator coordinator = new MasterDataAutoReloadCoordinator();
        UUID baselineId = UUID.randomUUID();
        UUID nextRunId = UUID.randomUUID();

        coordinator.observe(success(baselineId, FIRST_SUCCESS));

        assertEquals(NONE, coordinator.observe(new MasterDataHealthSnapshot(nextRunId, "RUNNING", FIRST_SUCCESS)));
        assertEquals(NONE, coordinator.observe(new MasterDataHealthSnapshot(nextRunId, "FAILED", FIRST_SUCCESS)));
        assertEquals(RELOAD_REQUIRED, coordinator.observe(success(nextRunId, SECOND_SUCCESS)));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## マスターデータ自動再読込
     * 検証契約: 再読込中の新しい成功 ID は保留し、完了後の再確認で1回だけ追走する。
     */
    @Test
    void defersNewRunUntilActiveReloadCompletes() {
        MasterDataAutoReloadCoordinator coordinator = new MasterDataAutoReloadCoordinator();
        UUID baselineId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID deferredId = UUID.randomUUID();
        coordinator.observe(success(baselineId, FIRST_SUCCESS));

        assertEquals(RELOAD_REQUIRED, coordinator.observe(success(activeId, SECOND_SUCCESS)));
        assertEquals(DEFERRED, coordinator.observe(success(activeId, SECOND_SUCCESS)));
        assertEquals(DEFERRED, coordinator.observe(success(deferredId, "2026-09-20T01:02:00Z")));
        assertTrue(coordinator.reloadCompleted(activeId, true, true));
        assertEquals(RELOAD_REQUIRED, coordinator.observe(success(deferredId, "2026-09-20T01:02:00Z")));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## マスターデータ自動再読込
     * 検証契約: 再読込中に確認済みの成功 ID は、直後の Seeder が失敗しても完了後の追走対象として保持する。
     */
    @Test
    void preservesDeferredSuccessWhenLatestRunFailed() {
        MasterDataAutoReloadCoordinator coordinator = new MasterDataAutoReloadCoordinator();
        UUID baselineId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID deferredId = UUID.randomUUID();
        UUID failedLatestId = UUID.randomUUID();
        coordinator.observe(success(baselineId, FIRST_SUCCESS));
        coordinator.observe(success(activeId, SECOND_SUCCESS));
        coordinator.observe(success(deferredId, "2026-09-20T01:02:00Z"));
        coordinator.reloadCompleted(activeId, true, true);

        assertEquals(
            RELOAD_REQUIRED,
            coordinator.observe(new MasterDataHealthSnapshot(failedLatestId, "FAILED", "2026-09-20T01:02:00Z"))
        );
        assertEquals(deferredId, coordinator.reloadTarget().lastSeedRunId());
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## マスターデータ自動再読込
     * 検証契約: 自動再読込の失敗は適用済みにせず、次回取得で同じ成功 ID を再試行できる。
     */
    @Test
    void retriesRunAfterOwnedReloadFailure() {
        MasterDataAutoReloadCoordinator coordinator = new MasterDataAutoReloadCoordinator();
        UUID baselineId = UUID.randomUUID();
        UUID failedId = UUID.randomUUID();
        coordinator.observe(success(baselineId, FIRST_SUCCESS));
        coordinator.observe(success(failedId, SECOND_SUCCESS));

        assertFalse(coordinator.reloadCompleted(failedId, true, false));
        assertEquals(RELOAD_REQUIRED, coordinator.observe(success(failedId, SECOND_SUCCESS)));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## マスターデータ自動再読込
     * 検証契約: 手動再読込を待った場合は対象 ID を適用済みにせず、完了後に安全な追走を行う。
     */
    @Test
    void rechecksSameRunAfterWaitingForManualReload() {
        MasterDataAutoReloadCoordinator coordinator = new MasterDataAutoReloadCoordinator();
        UUID baselineId = UUID.randomUUID();
        UUID detectedId = UUID.randomUUID();
        coordinator.observe(success(baselineId, FIRST_SUCCESS));
        coordinator.observe(success(detectedId, SECOND_SUCCESS));

        assertFalse(coordinator.reloadCompleted(detectedId, false, true));
        assertEquals(RELOAD_REQUIRED, coordinator.observe(success(detectedId, SECOND_SUCCESS)));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## マスターデータ自動再読込
     * 検証契約: ISO 8601でない成功日時を含むhealth応答は解析失敗として再読込判定へ渡さない。
     */
    @Test
    void rejectsNonIsoLastSucceededAt() {
        String response = """
            {
              "lastSeedRunId": "%s",
              "lastSeedRunStatus": "SUCCEEDED",
              "lastSucceededAt": "not-a-date"
            }
            """.formatted(UUID.randomUUID());

        assertThrows(JsonParseException.class, () -> MasterDataAutoReloadService.parseSnapshot(response));
    }

    private static MasterDataHealthSnapshot success(UUID seedRunId, String succeededAt) {
        return new MasterDataHealthSnapshot(seedRunId, "SUCCEEDED", succeededAt);
    }
}
