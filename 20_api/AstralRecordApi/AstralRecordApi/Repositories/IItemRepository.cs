using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IItemRepository
{
    IReadOnlyList<ItemSummaryResponse> GetAllSummaries();

    ItemResponse? GetById(string itemId);
}