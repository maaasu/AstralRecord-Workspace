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

    public IReadOnlyList<string>? Lines { get; init; }
}
