using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = loot.pool</c> / <c>loot.table</c>）から
/// ルートデータを取得する。
/// </summary>
public class LootRepository(MasterDataDbContext dbContext) : ILootRepository
{
    private const string PoolMasterType = "loot.pool";
    private const string TableMasterType = "loot.table";

    public IReadOnlyList<LootPoolResponse> GetAllPools()
        => LoadAll<LootPoolResponse>(PoolMasterType);

    public LootPoolResponse? GetPoolById(string poolId)
        => LoadOne<LootPoolResponse>(PoolMasterType, poolId);

    public IReadOnlyList<LootTableResponse> GetAllTables()
        => LoadAll<LootTableResponse>(TableMasterType);

    public LootTableResponse? GetTableById(string tableId)
        => LoadOne<LootTableResponse>(TableMasterType, tableId);

    private IReadOnlyList<T> LoadAll<T>(string masterType) where T : class
    {
        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == masterType)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<T>)
            .Where(value => value is not null)
            .Select(value => value!)
            .ToArray();
    }

    private T? LoadOne<T>(string masterType, string masterId) where T : class
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == masterType
                && entry.MasterId == masterId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<T>(payload);
    }
}
