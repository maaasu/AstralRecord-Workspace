using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IWorldRepository
{
    IReadOnlyList<WorldSummaryResponse> GetAllSummaries();

    WorldResponse? GetById(string worldId);
}
