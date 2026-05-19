using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IClassRepository
{
    IReadOnlyList<ClassSummaryResponse> GetAllSummaries();

    ClassResponse? GetById(string classId);
}
