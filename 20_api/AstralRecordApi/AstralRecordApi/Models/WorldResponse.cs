namespace AstralRecordApi.Models;

/// <summary>World マスタ詳細レスポンス。</summary>
public class WorldResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string DisplayName { get; init; }

    public required string WorldType { get; init; }

    public required string BaseWorldPath { get; init; }

    public required string InstanceRootPath { get; init; }

    public bool AutoLoad { get; init; }

    public bool InstanceEnabled { get; init; }

    public int MaxPlayers { get; init; }

    public bool AllowBlockBreak { get; init; }

    public bool AllowBlockPlace { get; init; }

    public bool AllowMobSpawn { get; init; }

    public required string Description { get; init; }
}

/// <summary>World マスタ一覧レスポンス要素。</summary>
public class WorldSummaryResponse
{
    public required string Id { get; init; }

    public required string DisplayName { get; init; }

    public required string WorldType { get; init; }

    public bool AutoLoad { get; init; }

    public bool InstanceEnabled { get; init; }

    public int MaxPlayers { get; init; }
}
