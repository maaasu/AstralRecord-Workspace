namespace AstralRecordApi.Models;

public class BuffResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public required string Name { get; init; }

    public string? Icon { get; init; }

    public IReadOnlyList<string> Lore { get; init; } = [];

    public required long DurationTicks { get; init; }

    public bool IsDebuff { get; init; }

    public IReadOnlyList<BuffModifierResponse> Modifiers { get; init; } = [];
}

public class BuffSummaryResponse
{
    public required string Id { get; init; }

    public required string Type { get; init; }

    public required string Name { get; init; }

    public string? Icon { get; init; }

    public long DurationTicks { get; init; }

    public bool IsDebuff { get; init; }
}

public class BuffModifierResponse
{
    public required string Status { get; init; }

    public required string Type { get; init; }

    public required double Value { get; init; }
}