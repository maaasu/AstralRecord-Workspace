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

/// <summary>ゲーム内ガイドに表示する手順です。</summary>
public class GuideStepResponse
{
    public required string Id { get; init; }

    public required string Text { get; init; }

    public IReadOnlyList<string> Details { get; init; } = [];

    public required GuideConditionResponse Condition { get; init; }

    public GuideActionResponse? Action { get; init; }
}

/// <summary>ガイド詳細画面から実行できる案内アクションです。</summary>
public class GuideActionResponse
{
    public required string Type { get; init; }

    public string? Description { get; init; }

    public string? NpcId { get; init; }

    public string? MenuId { get; init; }
}

/// <summary>ガイド手順の達成条件です。</summary>
public class GuideConditionResponse
{
    public required string Type { get; init; }

    public string? TargetId { get; init; }
}
