using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IRuntimeFilebaseRepository
{
    Task<RuntimeFilebaseSnapshotResponse> GetSnapshotAsync(CancellationToken cancellationToken = default);
}
