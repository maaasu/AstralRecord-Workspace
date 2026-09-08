using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IPlayerStateSnapshotRepository
{
    Task<PlayerStateSnapshotSaveResult> SaveAsync(PlayerStateSnapshotSaveRequest request);

    /// <summary>完了済み snapshot の固定 ACK を返します。未完了または不一致の場合は null です。</summary>
    Task<PlayerStateSnapshotAck?> FindCompletedAsync(Guid snapshotId, Guid accountId);
}
