using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

/// <summary>Seeder の起動契機。</summary>
public enum MasterDataSeedTrigger
{
    /// <summary>API 起動時の自動実行。</summary>
    Startup,

    /// <summary>Seeder API からの実行。</summary>
    SeederApi,

    /// <summary>手動実行。</summary>
    Manual
}

/// <summary>Seeder の同期モード。</summary>
public enum MasterDataSeedMode
{
    /// <summary>ハッシュ差分のみ反映する通常同期。</summary>
    Diff,

    /// <summary>entry/reference を全削除してから再投入するフル再構築。</summary>
    Rebuild
}

/// <summary>filebase YAML を MasterDataDB へ同期する Seeder。</summary>
public interface IMasterDataSeeder
{
    /// <summary>filebase から MasterDataDB を同期し、実行結果を返す。</summary>
    Task<MasterDataSeedResultResponse> RunAsync(
        MasterDataSeedTrigger trigger,
        MasterDataSeedMode mode,
        CancellationToken cancellationToken = default);
}
