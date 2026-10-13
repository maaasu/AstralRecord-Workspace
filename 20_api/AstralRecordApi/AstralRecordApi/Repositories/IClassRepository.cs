using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IClassRepository
{
    IReadOnlyList<ClassSummaryResponse> GetAllSummaries();

    ClassResponse? GetById(string classId);
    Task<IReadOnlyList<ClassSummaryResponse>> GetAllSummariesAsync(CancellationToken cancellationToken = default)
        => Task.FromResult(GetAllSummaries());
    Task<ClassResponse?> GetByIdAsync(string id, CancellationToken cancellationToken = default)
        => Task.FromResult(GetById(id));
    Task<MasterDataPageResponse<ClassResponse>> GetDetailsAsync(int offset, int limit, CancellationToken cancellationToken = default)
        => throw new NotSupportedException();
}
