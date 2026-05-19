namespace AstralRecordApi.Models;

public class RecipeResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public required string Category { get; init; }

    public string? Name { get; init; }

    public IReadOnlyList<string> Lore { get; init; } = [];

    public IReadOnlyList<string> Tags { get; init; } = [];

    public RecipeResultResponse? Result { get; init; }

    public IReadOnlyList<RecipeIngredientResponse> Ingredients { get; init; } = [];

    public int RequiredLevel { get; init; }

    public IReadOnlyList<string> RequiredClasses { get; init; } = [];

    public int RequiredCurrency { get; init; }

    public string? StationId { get; init; }

    public double SuccessRate { get; init; }

    public string FailAction { get; init; } = "NONE";

    public long CooldownTicks { get; init; }

    public RecipeEffectResponse? OnSuccess { get; init; }

    public RecipeEffectResponse? OnFail { get; init; }
}

public class RecipeSummaryResponse
{
    public required string Id { get; init; }

    public required string Type { get; init; }

    public required string Category { get; init; }

    public string? Name { get; init; }
}

public class RecipeResultResponse
{
    public required string ItemId { get; init; }

    public int Amount { get; init; } = 1;
}

public class RecipeIngredientResponse
{
    public required string ItemId { get; init; }

    public required int Amount { get; init; }
}

public class RecipeEffectResponse
{
    public string? Sound { get; init; }

    public string? Particle { get; init; }
}
