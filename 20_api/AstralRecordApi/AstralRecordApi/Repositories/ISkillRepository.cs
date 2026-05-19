using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ISkillRepository
{
    IReadOnlyList<SkillSummaryResponse> GetAllSummaries();

    SkillResponse? GetById(string skillId);
}
