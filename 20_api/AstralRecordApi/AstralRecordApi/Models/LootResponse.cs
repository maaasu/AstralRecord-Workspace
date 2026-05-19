namespace AstralRecordApi.Models;

public class LootPoolResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public string? Pick { get; init; }

    public IReadOnlyList<LootPoolContentResponse> Contents { get; init; } = [];
}

public class LootPoolContentResponse
{
    public required string ItemId { get; init; }

    public required double Rate { get; init; }

    public string? Amount { get; init; }
}

public class LootTableResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public string? Rolls { get; init; }

    public IReadOnlyList<string> Pools { get; init; } = [];
}
