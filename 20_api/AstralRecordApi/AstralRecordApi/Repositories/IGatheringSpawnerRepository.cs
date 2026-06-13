using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

/// <summary>採集スポナー master data 取得リポジトリです。</summary>
public interface IGatheringSpawnerRepository
{
    IReadOnlyList<GatheringSpawnerSummaryResponse> GetAllSummaries();

    GatheringSpawnerResponse? GetById(string spawnerId);
}
