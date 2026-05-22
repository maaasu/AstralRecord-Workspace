namespace AstralRecordApi.Models;

/// <summary>Seeder 実行結果</summary>
public class MasterDataSeedResultResponse
{
    public Guid SeedRunId { get; init; }
    public string TriggerType { get; init; } = string.Empty;
    public string Status { get; init; } = string.Empty;
    public string SourceRootPath { get; init; } = string.Empty;
    public DateTime StartedAt { get; init; }
    public DateTime? FinishedAt { get; init; }
    public int FileCount { get; init; }
    public int UpsertedCount { get; init; }
    public int DeletedCount { get; init; }
    public int SkippedCount { get; init; }
    public string? ErrorMessage { get; init; }

    /// <summary>任意参照の未解決など、失敗扱いにしない警告。</summary>
    public IReadOnlyList<string> Warnings { get; init; } = [];
}

/// <summary>Seeder 実行履歴の 1 件</summary>
public class MasterDataSeedRunResponse
{
    public Guid SeedRunId { get; init; }
    public string TriggerType { get; init; } = string.Empty;
    public string Status { get; init; } = string.Empty;
    public string SourceRootPath { get; init; } = string.Empty;
    public DateTime StartedAt { get; init; }
    public DateTime? FinishedAt { get; init; }
    public int FileCount { get; init; }
    public int UpsertedCount { get; init; }
    public int DeletedCount { get; init; }
    public int SkippedCount { get; init; }
    public string? ErrorMessage { get; init; }
}

/// <summary>MasterDataDB の参照可能状態</summary>
public class MasterDataHealthResponse
{
    /// <summary>ok / empty / degraded</summary>
    public string Status { get; init; } = string.Empty;
    public int ActiveEntryCount { get; init; }
    public Guid? LastSeedRunId { get; init; }
    public string? LastSeedRunStatus { get; init; }
    public DateTime? LastSucceededAt { get; init; }
}
