using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Services;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = buff</c>）から
/// バフマスタを取得する。
/// </summary>
public class BuffRepository(MasterDataDbContext dbContext, MasterDataCache? cache = null) : IBuffRepository
{
    private const string MasterType = "buff";
    private readonly MasterDataCatalog<BuffResponse> catalog = new(dbContext, cache ?? new(), MasterType);

    public IReadOnlyList<BuffSummaryResponse> GetAllSummaries()
    {
        return catalog.Get().Items
            .Select(buff => new BuffSummaryResponse
            {
                Id = buff!.Id,
                Type = buff.Type,
                Name = buff.Name,
                Icon = buff.Icon,
                DurationTicks = buff.DurationTicks,
                IsDebuff = buff.IsDebuff,
                StackGroup = buff.StackGroup,
            })
            .ToArray();
    }

    public BuffResponse? GetById(string buffId)
    {
        return catalog.Get().ById.GetValueOrDefault(buffId);
    }
}
