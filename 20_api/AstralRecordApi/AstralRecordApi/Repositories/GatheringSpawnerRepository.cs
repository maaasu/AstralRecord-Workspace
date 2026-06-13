using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = gathering_spawner</c>）から
/// 採集スポナー master data を取得します。
/// </summary>
public class GatheringSpawnerRepository(MasterDataDbContext dbContext) : IGatheringSpawnerRepository
{
    private const string MasterTypeGatheringSpawner = "gathering_spawner";

    public IReadOnlyList<GatheringSpawnerSummaryResponse> GetAllSummaries()
    {
        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterTypeGatheringSpawner)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<GatheringSpawnerResponse>)
            .Where(spawner => spawner is not null)
            .Select(spawner => new GatheringSpawnerSummaryResponse
            {
                Id = spawner!.Id,
                RadiusMeters = spawner.RadiusMeters,
                SpawnTargetCount = spawner.SpawnGatherings.Count,
                HasBaseBlockFilter = spawner.RequiredBaseBlocks.Count > 0,
            })
            .ToArray();
    }

    public GatheringSpawnerResponse? GetById(string spawnerId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterTypeGatheringSpawner
                && entry.MasterId == spawnerId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<GatheringSpawnerResponse>(payload);
    }
}
