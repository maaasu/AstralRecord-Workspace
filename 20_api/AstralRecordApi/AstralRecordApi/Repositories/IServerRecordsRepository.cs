using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IServerRecordsRepository
{
    Task<ServerRecordsResponse> GetAsync(int limit, CancellationToken cancellationToken = default);
}
