namespace AstralRecordApi.Models;

public class SkillResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public required string ImplementationId { get; init; }

    public required string Name { get; init; }

    public string? Description { get; init; }

    public string? Icon { get; init; }

    public IReadOnlyList<string> Lore { get; init; } = [];

    public long CooldownTicks { get; init; }

    public double ManaCost { get; init; }

    public long CastTimeTicks { get; init; }

    public int RequiredLevel { get; init; } = 1;

    public SkillOnCastResponse? OnCast { get; init; }

    public IReadOnlyDictionary<string, object?> Params { get; init; } = new Dictionary<string, object?>();

    public IReadOnlyList<string> Tags { get; init; } = [];
}

public class SkillSummaryResponse
{
    public required string Id { get; init; }

    public required string Name { get; init; }

    public required string ImplementationId { get; init; }
}

public class SkillOnCastResponse
{
    public string? Sound { get; init; }
}
