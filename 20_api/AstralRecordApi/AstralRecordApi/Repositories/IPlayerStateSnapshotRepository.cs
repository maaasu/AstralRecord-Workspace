using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IPlayerStateSnapshotRepository
{
    Task<PlayerStateSnapshotSaveResult> SaveAsync(PlayerStateSnapshotSaveRequest request);
}
