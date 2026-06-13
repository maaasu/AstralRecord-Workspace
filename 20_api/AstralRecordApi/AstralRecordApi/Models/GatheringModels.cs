namespace AstralRecordApi.Models;

/// <summary>採集オブジェクト詳細レスポンスです。</summary>
public class GatheringResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Category { get; init; }

    public required string Name { get; init; }

    public int MaxHealth { get; init; }

    public required string DisplayBlock { get; init; }

    public required GatheringDisplayScaleResponse DisplayScale { get; init; }

    public IReadOnlyList<string> RequiredToolTags { get; init; } = [];
}

/// <summary>採集オブジェクト一覧用の要約レスポンスです。</summary>
public class GatheringSummaryResponse
{
    public required string Id { get; init; }

    public required string Category { get; init; }

    public required string Name { get; init; }

    public int MaxHealth { get; init; }

    public required string DisplayBlock { get; init; }

    public IReadOnlyList<string> RequiredToolTags { get; init; } = [];
}

/// <summary>DisplayBlock の拡大率です。</summary>
public class GatheringDisplayScaleResponse
{
    public double X { get; init; }

    public double Y { get; init; }

    public double Z { get; init; }
}
