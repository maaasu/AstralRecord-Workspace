namespace AstralRecordApi.Models;

/// <summary>採集スポナー詳細レスポンスです。</summary>
public class GatheringSpawnerResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public double RadiusMeters { get; init; }

    public IReadOnlyList<GatheringSpawnEntryResponse> SpawnGatherings { get; init; } = [];

    public IReadOnlyList<GatheringSpawnTimeResponse> SpawnTimes { get; init; } = [];

    public required string ItemMaterial { get; init; }

    public long SpawnIntervalTicks { get; init; } = 100;

    public required GatheringSpawnLimitResponse SpawnLimit { get; init; }

    public IReadOnlyList<string> RequiredBaseBlocks { get; init; } = [];
}

/// <summary>採集スポナー一覧用の要約レスポンスです。</summary>
public class GatheringSpawnerSummaryResponse
{
    public required string Id { get; init; }

    public double RadiusMeters { get; init; }

    public int SpawnTargetCount { get; init; }

    public bool HasBaseBlockFilter { get; init; }
}

/// <summary>スポナーの抽選対象採集オブジェクトです。</summary>
public class GatheringSpawnEntryResponse
{
    public required string GatheringId { get; init; }

    public int Weight { get; init; }
}

/// <summary>スポーン可能時刻帯です。</summary>
public class GatheringSpawnTimeResponse
{
    public long StartTick { get; init; }

    public long EndTick { get; init; }
}

/// <summary>採集スポナーの同時存在上限設定です。</summary>
public class GatheringSpawnLimitResponse
{
    public int MaxAlivePerSpawner { get; init; } = 8;

    public int MaxNearbyGatherings { get; init; } = 18;

    public int SpawnPerPlayer { get; init; } = 1;
}
