using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using AstralRecordApi.Services;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = skill</c>）から
/// スキルマスタを取得する。
/// </summary>
public class SkillRepository(MasterDataDbContext dbContext, MasterDataCache? cache = null) : ISkillRepository
{
    private readonly MasterDataCache masterCache = cache ?? new();
    private const string MasterType = "skill";

    public IReadOnlyList<SkillSummaryResponse> GetAllSummaries()
    {
        return GetSnapshot().Value.Summaries;
    }

    public SkillResponse? GetById(string skillId)
    {
        return GetSnapshot().Value.ById.GetValueOrDefault(skillId);
    }

    public async Task<IReadOnlyList<SkillSummaryResponse>> GetAllSummariesAsync(CancellationToken cancellationToken = default)
    {
        return (await GetSnapshotAsync(cancellationToken)).Value.Summaries;
    }

    public async Task<SkillResponse?> GetByIdAsync(string id, CancellationToken cancellationToken = default)
        => (await GetSnapshotAsync(cancellationToken)).Value.ById.GetValueOrDefault(id);

    public async Task<MasterDataPageResponse<SkillResponse>> GetDetailsAsync(int offset, int limit, CancellationToken cancellationToken = default)
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
        var converted = entries.Select(entry => (entry.Id, Value: MasterDataPayloadJson.Deserialize<SkillResponse>(entry.Json)))
            .Where(entry => entry.Value is not null).ToArray();
        var active = converted.Select(entry => entry.Value!).ToArray();
        var summaries = active
            .Select(skill => new SkillSummaryResponse
            {
                Id = skill!.Id,
                Name = skill.Name,
                ImplementationId = skill.ImplementationId,
                Icon = skill.Icon,
                IconTexture = skill.IconTexture,
                Tags = skill.Tags,
            })
            .ToArray();
        return new(active, summaries,
            converted.ToDictionary(entry => entry.Id, entry => entry.Value!, StringComparer.OrdinalIgnoreCase));
    }
    private sealed record Payload(string Id, string Json);
    private sealed record Snapshot(IReadOnlyList<SkillResponse> Active, IReadOnlyList<SkillSummaryResponse> Summaries, IReadOnlyDictionary<string, SkillResponse> ById);
}
