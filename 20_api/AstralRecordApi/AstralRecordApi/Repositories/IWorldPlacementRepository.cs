using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IWorldPlacementRepository
{
    Task<WorldPlacementSnapshot> GetAsync(string environment, CancellationToken cancellationToken);
    Task<WorldPlacementSnapshot?> ReplaceDevAsync(WorldPlacementReplaceRequest request, CancellationToken cancellationToken);
    Task<WorldPlacementSnapshot?> UpsertDevAsync(WorldPlacementUpsertRequest request, CancellationToken cancellationToken);
    Task<WorldPlacementSnapshot?> DeleteDevAsync(string placementId, string expectedRevision, CancellationToken cancellationToken);
    Task<WorldPlacementSnapshot?> SyncAsync(WorldPlacementSyncRequest request, CancellationToken cancellationToken);
}
