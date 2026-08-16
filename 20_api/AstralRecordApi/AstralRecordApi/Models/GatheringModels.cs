namespace AstralRecordApi.Models;

/// <summary>Gathering object detail response.</summary>
public class GatheringResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public required string Category { get; init; }

    public required string Name { get; init; }

    public int MaxHealth { get; init; }

    public required string DisplayBlock { get; init; }

    public required GatheringDisplayScaleResponse DisplayScale { get; init; }

    public IReadOnlyList<string> RequiredToolTags { get; init; } = [];

    public GatheringSoundSetResponse Sounds { get; init; } = new();

    public GatheringDropsResponse Drops { get; init; } = new();
}

/// <summary>Gathering object summary response.</summary>
public class GatheringSummaryResponse
{
    public required string Id { get; init; }

    public required string Category { get; init; }

    public required string Name { get; init; }

    public int MaxHealth { get; init; }

    public required string DisplayBlock { get; init; }

    public IReadOnlyList<string> RequiredToolTags { get; init; } = [];
}

/// <summary>BlockDisplay scale.</summary>
public class GatheringDisplayScaleResponse
{
    public double X { get; init; }

    public double Y { get; init; }

    public double Z { get; init; }
}

/// <summary>Gathering sound configuration.</summary>
public class GatheringSoundSetResponse
{
    public GatheringSoundResponse? Hit { get; init; }

    public GatheringSoundResponse? Break { get; init; }
}

/// <summary>Single gathering sound effect.</summary>
public class GatheringSoundResponse
{
    public required string Sound { get; init; }

    public double Volume { get; init; } = 1.0;

    public double Pitch { get; init; } = 1.0;
}

/// <summary>Gathering drop configuration.</summary>
public class GatheringDropsResponse
{
    public int Exp { get; init; }

    public IReadOnlyList<GatheringDropItemResponse> Items { get; init; } = [];

    public string? LootTable { get; init; }
}

/// <summary>Gathering drop item candidate.</summary>
public class GatheringDropItemResponse
{
    public required string ItemId { get; init; }

    public double Rate { get; init; }

    public string Amount { get; init; } = "1";

    public bool LuckAffected { get; init; } = true;

    public bool Hidden { get; init; }
}
