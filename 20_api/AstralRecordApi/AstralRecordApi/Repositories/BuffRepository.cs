using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = buff</c>）から
/// バフマスタを取得する。
/// </summary>
public class BuffRepository(MasterDataDbContext dbContext) : IBuffRepository
{
    private const string MasterType = "buff";

    public IReadOnlyList<BuffSummaryResponse> GetAllSummaries()
    {
        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterType)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<BuffResponse>)
            .Where(buff => buff is not null)
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
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.MasterId == buffId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<BuffResponse>(payload);
    }
}
