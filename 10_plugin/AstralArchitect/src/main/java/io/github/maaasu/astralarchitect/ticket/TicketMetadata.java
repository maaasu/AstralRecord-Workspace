package io.github.maaasu.astralarchitect.ticket;

/**
 * ticket.jsonへ永続化する建築チケットの契約です。
 * 時刻はUTCのISO-8601文字列で保存します。
 */
public record TicketMetadata(
        int schemaVersion,
        String id,
        String name,
        TicketState state,
        TicketState stateBeforeTrash,
        String ownerUuid,
        String ownerName,
        String worldUuid,
        String worldName,
        TicketBounds bounds,
        BlockPosition anchor,
        String anchorBlockState,
        long blockCount,
        String minecraftVersion,
        String faweVersion,
        String createdAt,
        String updatedAt,
        String validatedAt,
        String appliedAt,
        String deletedAt,
        String sourceSha256,
        String candidateSha256,
        String appliedCandidateSha256,
        long changedBlockCount) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 作成処理中のメタデータを生成します。
     *
     * @param id チケットID
     * @param name 表示名
     * @param ownerUuid 所有者UUID
     * @param ownerName 所有者名
     * @param worldUuid ワールドUUID
     * @param worldName ワールド名
     * @param bounds 選択範囲
     * @param anchor 基準座標
     * @param anchorBlockState 基準ブロック状態
     * @param blockCount 総ブロック数
     * @param minecraftVersion Minecraftバージョン
     * @param faweVersion FAWEバージョン
     * @param now 現在時刻
     * @return 作成処理中のメタデータ
     */
    public static TicketMetadata creating(
            String id,
            String name,
            String ownerUuid,
            String ownerName,
            String worldUuid,
            String worldName,
            TicketBounds bounds,
            BlockPosition anchor,
            String anchorBlockState,
            long blockCount,
            String minecraftVersion,
            String faweVersion,
            String now) {
        return new TicketMetadata(
                CURRENT_SCHEMA_VERSION,
                id,
                name,
                TicketState.CREATING,
                null,
                ownerUuid,
                ownerName,
                worldUuid,
                worldName,
                bounds,
                anchor,
                anchorBlockState,
                blockCount,
                minecraftVersion,
                faweVersion,
                now,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                0L);
    }

    /**
     * スナップショット作成完了状態へ更新します。
     *
     * @param sourceHash source.schemのSHA-256
     * @param candidateHash candidate.schemのSHA-256
     * @param now 更新時刻
     * @return 更新後メタデータ
     */
    public TicketMetadata created(String sourceHash, String candidateHash, String now) {
        return copy(TicketState.CREATED, null, now, null, null, null,
                sourceHash, candidateHash, null, 0L);
    }

    /**
     * 候補検証完了状態へ更新します。
     *
     * @param candidateHash 検証済み候補のSHA-256
     * @param changedCount 変更ブロック数
     * @param now 検証時刻
     * @return 更新後メタデータ
     */
    public TicketMetadata ready(String candidateHash, long changedCount, String now) {
        return copy(TicketState.READY, null, now, now, null, null,
                sourceSha256, candidateHash, null, changedCount);
    }

    /**
     * 候補適用中状態へ更新し、再開に使う候補ハッシュを固定します。
     *
     * @param candidateHash 適用対象として固定した候補のSHA-256
     * @param now 更新時刻
     * @return 更新後メタデータ
     */
    public TicketMetadata applying(String candidateHash, String now) {
        return copy(TicketState.APPLYING, null, now, validatedAt, null, null,
                sourceSha256, candidateHash, candidateHash, changedBlockCount);
    }

    /**
     * 候補適用済み状態へ更新します。
     *
     * @param candidateHash 適用した候補のSHA-256
     * @param now 適用時刻
     * @return 更新後メタデータ
     */
    public TicketMetadata applied(String candidateHash, String now) {
        return copy(TicketState.APPLIED, null, now, validatedAt, now, null,
                sourceSha256, candidateHash, candidateHash, changedBlockCount);
    }

    /**
     * ロールバック中状態へ更新します。
     *
     * @param now 更新時刻
     * @return 更新後メタデータ
     */
    public TicketMetadata rollingBack(String now) {
        return copy(TicketState.ROLLING_BACK, null, now, validatedAt, appliedAt, null,
                sourceSha256, candidateSha256, appliedCandidateSha256, changedBlockCount);
    }

    /**
     * ロールバック済み状態へ更新します。
     *
     * @param now 更新時刻
     * @return 更新後メタデータ
     */
    public TicketMetadata rolledBack(String now) {
        return copy(TicketState.ROLLED_BACK, null, now, validatedAt, null, null,
                sourceSha256, candidateSha256, null, changedBlockCount);
    }

    /**
     * trash移動済み状態へ更新します。
     *
     * @param now 削除操作時刻
     * @return 更新後メタデータ
     */
    public TicketMetadata trashed(String now) {
        if (state == TicketState.TRASHED) {
            return this;
        }
        return copy(TicketState.TRASHED, state, now, validatedAt, appliedAt, now,
                sourceSha256, candidateSha256, appliedCandidateSha256, changedBlockCount);
    }

    /**
     * trash移動前の状態へ復元します。
     *
     * @param now 復元時刻
     * @return 更新後メタデータ
     */
    public TicketMetadata restored(String now) {
        if (state != TicketState.TRASHED) {
            return this;
        }
        TicketState restoredState = stateBeforeTrash == null ? TicketState.CREATED : stateBeforeTrash;
        return copy(restoredState, null, now, validatedAt, appliedAt, null,
                sourceSha256, candidateSha256, appliedCandidateSha256, changedBlockCount);
    }

    private TicketMetadata copy(
            TicketState newState,
            TicketState newStateBeforeTrash,
            String newUpdatedAt,
            String newValidatedAt,
            String newAppliedAt,
            String newDeletedAt,
            String newSourceHash,
            String newCandidateHash,
            String newAppliedCandidateHash,
            long newChangedCount) {
        return new TicketMetadata(
                schemaVersion,
                id,
                name,
                newState,
                newStateBeforeTrash,
                ownerUuid,
                ownerName,
                worldUuid,
                worldName,
                bounds,
                anchor,
                anchorBlockState,
                blockCount,
                minecraftVersion,
                faweVersion,
                createdAt,
                newUpdatedAt,
                newValidatedAt,
                newAppliedAt,
                newDeletedAt,
                newSourceHash,
                newCandidateHash,
                newAppliedCandidateHash,
                newChangedCount);
    }
}
