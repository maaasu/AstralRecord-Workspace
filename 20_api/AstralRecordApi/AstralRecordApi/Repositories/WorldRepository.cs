using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Services;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = world</c>）から
/// World マスタを取得する。
/// </summary>
public class WorldRepository(MasterDataDbContext dbContext, MasterDataCache? cache = null) : IWorldRepository
{
    private const string MasterTypeWorld = "world";
    private readonly MasterDataCatalog<WorldResponse> catalog = new(dbContext, cache ?? new(), MasterTypeWorld);

    public IReadOnlyList<WorldSummaryResponse> GetAllSummaries()
    {
        return catalog.Get().Items
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
        return catalog.Get().ById.GetValueOrDefault(worldId);
    }
}
