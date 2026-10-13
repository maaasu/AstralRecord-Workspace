using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using AstralRecordApi.Services;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = class</c>）から
/// クラスマスタを取得する。
/// </summary>
public class ClassRepository(MasterDataDbContext dbContext, MasterDataCache? cache = null) : IClassRepository
{
    private readonly MasterDataCache masterCache = cache ?? new();
    private const string MasterType = "class";

    public IReadOnlyList<ClassSummaryResponse> GetAllSummaries()
    {
        return GetSnapshot().Value.Summaries;
    }

    public ClassResponse? GetById(string classId)
    {
        return GetSnapshot().Value.ById.GetValueOrDefault(classId);
    }

    public async Task<IReadOnlyList<ClassSummaryResponse>> GetAllSummariesAsync(CancellationToken cancellationToken = default)
    {
        return (await GetSnapshotAsync(cancellationToken)).Value.Summaries;
    }

    public async Task<ClassResponse?> GetByIdAsync(string id, CancellationToken cancellationToken = default)
        => (await GetSnapshotAsync(cancellationToken)).Value.ById.GetValueOrDefault(id);

    public async Task<MasterDataPageResponse<ClassResponse>> GetDetailsAsync(int offset, int limit, CancellationToken cancellationToken = default)
    {
        var snapshot = await GetSnapshotAsync(cancellationToken);
        return new(snapshot.Generation, snapshot.Value.Active.Count, snapshot.Value.Active.Skip(offset).Take(limit).ToArray());
    }

    private MasterDataCacheValue<Snapshot> GetSnapshot() => masterCache.Get(MasterType, () => ConvertEntries(Query().ToArray()));
    private Task<MasterDataCacheValue<Snapshot>> GetSnapshotAsync(CancellationToken cancellationToken)
        => masterCache.GetAsync(MasterType, async () => ConvertEntries(await Query().ToArrayAsync(cancellationToken)), cancellationToken);
    private IQueryable<Payload> Query() => dbContext.Entries.AsNoTracking()
        .Where(entry => !entry.IsDeleted && entry.MasterType == MasterType).OrderBy(entry => entry.MasterId)
        .Select(entry => new Payload(entry.MasterId, entry.PayloadJson));
    private static Snapshot ConvertEntries(Payload[] entries)
    {
        var converted = entries.Select(entry => (entry.Id, Value: MasterDataPayloadJson.Deserialize<ClassResponse>(entry.Json)))
            .Where(entry => entry.Value is not null).ToArray();
        var active = converted.Select(entry => entry.Value!).ToArray();
        var summaries = active
            .Select(cls => new ClassSummaryResponse
            {
                Id = cls!.Id,
                Name = cls.Name,
                Order = cls.Order,
                ShortName = cls.ShortName,
                Role = cls.Role,
            })
            .OrderBy(cls => cls.Order)
            .ThenBy(cls => cls.Id, StringComparer.OrdinalIgnoreCase)
            .ToArray();
        return new(active, summaries,
            converted.ToDictionary(entry => entry.Id, entry => entry.Value!, StringComparer.OrdinalIgnoreCase));
    }
    private sealed record Payload(string Id, string Json);
    private sealed record Snapshot(IReadOnlyList<ClassResponse> Active, IReadOnlyList<ClassSummaryResponse> Summaries, IReadOnlyDictionary<string, ClassResponse> ById);
}
