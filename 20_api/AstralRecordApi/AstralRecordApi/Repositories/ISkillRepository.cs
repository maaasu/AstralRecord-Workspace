using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ISkillRepository
{
    IReadOnlyList<SkillSummaryResponse> GetAllSummaries();

    SkillResponse? GetById(string skillId);
    Task<IReadOnlyList<SkillSummaryResponse>> GetAllSummariesAsync(CancellationToken cancellationToken = default)
        => Task.FromResult(GetAllSummaries());
    Task<SkillResponse?> GetByIdAsync(string id, CancellationToken cancellationToken = default)
        => Task.FromResult(GetById(id));
    Task<MasterDataPageResponse<SkillResponse>> GetDetailsAsync(int offset, int limit, CancellationToken cancellationToken = default)
        => throw new NotSupportedException();
}
