using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

/// <summary>MasterDataDB の Seeder 実行履歴・状態を参照する Repository。</summary>
public interface IMasterDataRepository
{
    /// <summary>直近の Seeder 実行履歴を新しい順に取得する。</summary>
    Task<IReadOnlyList<MasterDataSeedRunResponse>> GetSeedRunsAsync(int limit);

    /// <summary>MasterDataDB の参照可能状態を取得する。</summary>
    Task<MasterDataHealthResponse> GetHealthAsync();
}
