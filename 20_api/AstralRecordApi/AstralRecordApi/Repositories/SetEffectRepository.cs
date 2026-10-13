using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Services;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = set_effect</c>）から
/// セット効果マスタを取得する。
/// </summary>
public class SetEffectRepository(MasterDataDbContext dbContext, MasterDataCache? cache = null) : ISetEffectRepository
{
    private const string MasterType = "set_effect";
    private readonly MasterDataCatalog<SetEffectResponse> catalog = new(dbContext, cache ?? new(), MasterType);

    public IReadOnlyList<SetEffectResponse> GetAll()
    {
        return catalog.Get().Items
            .Select(setEffect => setEffect!)
            .ToArray();
    }

    public SetEffectResponse? GetById(string setId)
    {
        return catalog.Get().ById.GetValueOrDefault(setId);
    }
}
