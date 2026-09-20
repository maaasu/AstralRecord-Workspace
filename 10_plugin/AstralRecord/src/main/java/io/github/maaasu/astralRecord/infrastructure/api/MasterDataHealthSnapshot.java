package io.github.maaasu.astralRecord.infrastructure.api;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * MasterDataDB health API から自動再読込の判定に必要な値だけを保持します。
 *
 * @param lastSeedRunId 直近 Seeder 実行 ID。未実行時は {@code null}
 * @param lastSeedRunStatus 直近 Seeder 実行状態。未実行時は {@code null}
 * @param lastSucceededAt 直近成功日時。成功履歴がない場合は {@code null}
 */
record MasterDataHealthSnapshot(
    @Nullable UUID lastSeedRunId,
    @Nullable String lastSeedRunStatus,
    @Nullable String lastSucceededAt
) {
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /**
     * 直近 Seeder 実行が完了済みの成功状態かを返します。
     *
     * @return 成功状態かつ成功日時が存在する場合は {@code true}
     */
    boolean isSucceeded() {
        return STATUS_SUCCEEDED.equals(lastSeedRunStatus)
            && lastSucceededAt != null
            && !lastSucceededAt.isBlank();
    }

    /**
     * API が成功状態を返した一方で成功日時が欠落しているかを返します。
     *
     * @return 不整合な成功応答の場合は {@code true}
     */
    boolean isSucceededWithoutTimestamp() {
        return STATUS_SUCCEEDED.equals(lastSeedRunStatus)
            && (lastSucceededAt == null || lastSucceededAt.isBlank());
    }
}
