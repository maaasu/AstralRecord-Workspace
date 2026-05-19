using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IBuffRepository
{
    IReadOnlyList<BuffSummaryResponse> GetAllSummaries();

    BuffResponse? GetById(string buffId);
}