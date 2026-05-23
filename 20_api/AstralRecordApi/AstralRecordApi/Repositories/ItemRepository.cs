using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = item</c>）から
/// アイテムマスタを取得する。
/// </summary>
public class ItemRepository(MasterDataDbContext dbContext) : IItemRepository
{
    private const string MasterType = "item";
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    public IReadOnlyList<ItemSummaryResponse> GetAllSummaries()
        => dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterType)
            .OrderBy(entry => entry.Category)
            .ThenBy(entry => entry.MasterId)
            .Select(entry => new ItemSummaryResponse
            {
                Id = entry.MasterId,
                Category = entry.Category ?? string.Empty,
            })
            .ToArray();

    public ItemResponse? GetById(string itemId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.MasterId == itemId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<ItemResponse>(payload);
    }
}
