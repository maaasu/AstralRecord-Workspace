package io.github.maaasu.astralRecord.infrastructure.api;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * health 応答と再読込完了を直列化し、自動再読込の重複と取りこぼしを防ぎます。
 */
final class MasterDataAutoReloadCoordinator {
    private boolean initialized;
    private UUID appliedSeedRunId;
    private MasterDataHealthSnapshot activeSnapshot;
    private MasterDataHealthSnapshot deferredSnapshot;

    /**
     * health 応答を取り込み、呼び出し側が行うべき処理を返します。
     *
     * @param snapshot health 応答
     * @return 判定結果
     */
    synchronized @NotNull Observation observe(@NotNull MasterDataHealthSnapshot snapshot) {
        UUID seedRunId = snapshot.lastSeedRunId();
        if (!initialized) {
            initialized = true;
            if (seedRunId == null) {
                return Observation.MISSING_RUN_ID;
            }
            if (snapshot.isSucceeded()) {
                appliedSeedRunId = seedRunId;
            }
            return snapshot.isSucceededWithoutTimestamp()
                ? Observation.INCOMPLETE_SUCCESS
                : Observation.BASELINE_CAPTURED;
        }

        if (seedRunId == null) {
            return Observation.MISSING_RUN_ID;
        }
        if (snapshot.isSucceededWithoutTimestamp()) {
            return Observation.INCOMPLETE_SUCCESS;
        }
        if (activeSnapshot != null) {
            if (snapshot.isSucceeded() && !seedRunId.equals(activeSnapshot.lastSeedRunId())) {
                deferredSnapshot = snapshot;
            }
            return Observation.DEFERRED;
        }

        MasterDataHealthSnapshot reloadTarget = null;
        if (snapshot.isSucceeded() && !seedRunId.equals(appliedSeedRunId)) {
            reloadTarget = snapshot;
        } else if (deferredSnapshot != null && !"RUNNING".equals(snapshot.lastSeedRunStatus())) {
            reloadTarget = deferredSnapshot;
        }
        if (reloadTarget == null) {
            return Observation.NONE;
        }

        activeSnapshot = reloadTarget;
        deferredSnapshot = null;
        return Observation.RELOAD_REQUIRED;
    }

    /**
     * {@link Observation#RELOAD_REQUIRED} に対応する再読込対象を返します。
     *
     * @return 再読込対象。開始対象がない場合は {@code null}
     */
    synchronized MasterDataHealthSnapshot reloadTarget() {
        return activeSnapshot;
    }

    /**
     * メインスレッドへの再読込開始予約が失敗した場合に予約状態を解放します。
     *
     * @param seedRunId 解放対象の Seeder 実行 ID
     */
    synchronized void schedulingFailed(@NotNull UUID seedRunId) {
        if (activeSnapshot != null && seedRunId.equals(activeSnapshot.lastSeedRunId())) {
            activeSnapshot = null;
        }
    }

    /**
     * 監視サービスが待機していた再読込の完了を反映します。
     *
     * <p>既存の手動再読込を待った場合は、その再読込が対象 Seeder を読み込んだ保証がないため
     * 適用済みとはせず、完了後の health 再確認で追走します。</p>
     *
     * @param seedRunId 待機対象の Seeder 実行 ID
     * @param ownedReload 自動監視が新しく開始した再読込なら {@code true}
     * @param succeeded 再読込が成功した場合は {@code true}
     * @return 完了後に新しい Seeder 実行を確認していた場合は {@code true}
     */
    synchronized boolean reloadCompleted(
        @NotNull UUID seedRunId,
        boolean ownedReload,
        boolean succeeded
    ) {
        if (activeSnapshot == null || !seedRunId.equals(activeSnapshot.lastSeedRunId())) {
            return false;
        }
        if (ownedReload && succeeded) {
            appliedSeedRunId = seedRunId;
        }
        activeSnapshot = null;
        boolean deferred = deferredSnapshot != null;
        return deferred;
    }

    /** health 応答の取り込み結果です。 */
    enum Observation {
        NONE,
        BASELINE_CAPTURED,
        MISSING_RUN_ID,
        INCOMPLETE_SUCCESS,
        RELOAD_REQUIRED,
        DEFERRED
    }
}
