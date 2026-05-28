using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = world</c>）から
/// World マスタを取得する。
/// </summary>
public class WorldRepository(MasterDataDbContext dbContext) : IWorldRepository
{
    private const string MasterTypeWorld = "world";

    public IReadOnlyList<WorldSummaryResponse> GetAllSummaries()
    {
        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterTypeWorld)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<WorldResponse>)
            .Where(world => world is not null)
            .Select(world => new WorldSummaryResponse
            {
                Id = world!.Id,
                DisplayName = world.DisplayName,
                WorldType = world.WorldType,
                AutoLoad = world.AutoLoad,
                InstanceEnabled = world.InstanceEnabled,
                MaxPlayers = world.MaxPlayers,
            })
            .ToArray();
    }

    public WorldResponse? GetById(string worldId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterTypeWorld
                && entry.MasterId == worldId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<WorldResponse>(payload);
    }
}
