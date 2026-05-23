using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = class</c>）から
/// クラスマスタを取得する。
/// </summary>
public class ClassRepository(MasterDataDbContext dbContext) : IClassRepository
{
    private const string MasterType = "class";

    public IReadOnlyList<ClassSummaryResponse> GetAllSummaries()
    {
        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterType)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<ClassResponse>)
            .Where(cls => cls is not null)
            .Select(cls => new ClassSummaryResponse
            {
                Id = cls!.Id,
                Name = cls.Name,
                Role = cls.Role,
            })
            .ToArray();
    }

    public ClassResponse? GetById(string classId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.MasterId == classId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<ClassResponse>(payload);
    }
}
