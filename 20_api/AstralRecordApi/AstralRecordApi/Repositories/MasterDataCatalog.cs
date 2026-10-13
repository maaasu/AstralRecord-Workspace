using AstralRecordApi.Data;
using AstralRecordApi.Services;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>固定したマスタ種別だけを必要時に変換し、世代内で共有する。</summary>
internal sealed class MasterDataCatalog<T>(MasterDataDbContext db, MasterDataCache cache, string masterType) where T : class
{
    public Catalog Get() => cache.Get(masterType, () =>
    {
        var entries = db.Entries.AsNoTracking().Where(entry => !entry.IsDeleted && entry.MasterType == masterType)
            .OrderBy(entry => entry.MasterId).Select(entry => new { entry.MasterId, entry.PayloadJson }).ToArray();
        var converted = entries.Select(entry => (entry.MasterId, Value: MasterDataPayloadJson.Deserialize<T>(entry.PayloadJson)))
            .Where(entry => entry.Value is not null).ToArray();
        return new Catalog(converted.Select(entry => entry.Value!).ToArray(),
            converted.ToDictionary(entry => entry.MasterId, entry => entry.Value!, StringComparer.OrdinalIgnoreCase));
    }).Value;
    public sealed record Catalog(IReadOnlyList<T> Items, IReadOnlyDictionary<string, T> ById);
}
