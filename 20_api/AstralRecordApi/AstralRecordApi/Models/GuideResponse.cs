namespace AstralRecordApi.Models;

/// <summary>ゲーム内ガイドのレスポンスです。</summary>
public class GuideResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Category { get; init; }

    public int DisplayOrder { get; init; }

    public required string Title { get; init; }

    public string? IconMaterial { get; init; }

    public string? Summary { get; init; }

    public IReadOnlyList<GuideStepResponse> Steps { get; init; } = [];
}

/// <summary>ゲーム内ガイドの順序付き手順です。</summary>
public class GuideStepResponse
{
    public required string Id { get; init; }

    public required string Text { get; init; }

    public required GuideConditionResponse Condition { get; init; }
}

/// <summary>ガイド手順の達成条件です。</summary>
public class GuideConditionResponse
{
    public required string Type { get; init; }

    public string? TargetId { get; init; }
}
