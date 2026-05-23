using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = set_effect</c>）から
/// セット効果マスタを取得する。
/// </summary>
public class SetEffectRepository(MasterDataDbContext dbContext) : ISetEffectRepository
{
    private const string MasterType = "set_effect";

    public IReadOnlyList<SetEffectResponse> GetAll()
    {
        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterType)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<SetEffectResponse>)
            .Where(setEffect => setEffect is not null)
            .Select(setEffect => setEffect!)
            .ToArray();
    }

    public SetEffectResponse? GetById(string setId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.MasterId == setId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<SetEffectResponse>(payload);
    }
}
