using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IItemRepository
{
    IReadOnlyList<ItemSummaryResponse> GetAllSummaries();

    ItemResponse? GetById(string itemId);
    Task<IReadOnlyList<ItemSummaryResponse>> GetAllSummariesAsync(CancellationToken cancellationToken = default)
        => Task.FromResult(GetAllSummaries());
    Task<ItemResponse?> GetByIdAsync(string id, CancellationToken cancellationToken = default)
        => Task.FromResult(GetById(id));
    Task<MasterDataPageResponse<ItemResponse>> GetDetailsAsync(int offset, int limit, CancellationToken cancellationToken = default)
        => throw new NotSupportedException();
}