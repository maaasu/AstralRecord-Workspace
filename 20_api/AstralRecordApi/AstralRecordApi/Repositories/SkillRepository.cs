using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = skill</c>）から
/// スキルマスタを取得する。
/// </summary>
public class SkillRepository(MasterDataDbContext dbContext) : ISkillRepository
{
    private const string MasterType = "skill";

    public IReadOnlyList<SkillSummaryResponse> GetAllSummaries()
    {
        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterType)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<SkillResponse>)
            .Where(skill => skill is not null)
            .Select(skill => new SkillSummaryResponse
            {
                Id = skill!.Id,
                Name = skill.Name,
                ImplementationId = skill.ImplementationId,
            })
            .ToArray();
    }

    public SkillResponse? GetById(string skillId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.MasterId == skillId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<SkillResponse>(payload);
    }
}
