namespace AstralRecordApi.Models;

/// <summary>World マスタ詳細レスポンスです。</summary>
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

    public bool ShowSpawnParticle { get; init; }

    public required WorldSpawnLocationResponse SpawnLocation { get; init; }

    public required string Description { get; init; }

    public string? RequiredItemId { get; init; }

    public string? GuiIconMaterial { get; init; }

    public WorldAdventureGuideResponse? AdventureGuide { get; init; }

    public OverworldTeleportGuiResponse? OverworldTeleportGui { get; init; }
}

/// <summary>World のスポーン地点座標です。</summary>
public class WorldSpawnLocationResponse
{
    public double X { get; init; }

    public double Y { get; init; }

    public double Z { get; init; }

    public float Yaw { get; init; }

    public float Pitch { get; init; }
}

/// <summary>ワールド選択 GUI に表示する冒険ガイド情報です。</summary>
public class WorldAdventureGuideResponse
{
    public int? RecommendedLevelMin { get; init; }

    public int? RecommendedLevelMax { get; init; }

    public int? RecommendedPartySizeMin { get; init; }

    public int? RecommendedPartySizeMax { get; init; }

    public IReadOnlyList<string>? Notes { get; init; }
}

/// <summary>拠点から開くオーバーワールド転送 GUI の配置設定です。</summary>
public class OverworldTeleportGuiResponse
{
    public int? Slot { get; init; }
}

/// <summary>World マスタ一覧レスポンス要約です。</summary>
public class WorldSummaryResponse
{
    public required string Id { get; init; }

    public required string DisplayName { get; init; }

    public required string WorldType { get; init; }

    public bool AutoLoad { get; init; }

    public bool InstanceEnabled { get; init; }

    public int MaxPlayers { get; init; }
}
