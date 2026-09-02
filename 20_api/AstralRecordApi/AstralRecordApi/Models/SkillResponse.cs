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

    public string? CooldownId { get; init; }

    public double ManaCost { get; init; }

    public string? ResourceType { get; init; }

    public double? ResourceCost { get; init; }

    public long CastTimeTicks { get; init; }

    public int RequiredLevel { get; init; } = 1;

    public SkillOnCastResponse? OnCast { get; init; }

    public SkillPassiveResponse? Passive { get; init; }

    public int MaxLevel { get; init; } = 1;

    public IReadOnlyList<SkillLevelResponse> Levels { get; init; } = [];

    public IReadOnlyList<SkillSigilSlotResponse> SigilSlotsByLevel { get; init; } = [];

    public IReadOnlyList<string> AllowedSigilIds { get; init; } = [];

    /// <summary>初回習得時に消費するアイテム。未指定時は無条件で習得できます。</summary>
    public IReadOnlyList<SkillRequiredItemResponse> LearnRequiredItems { get; init; } = [];

    /// <summary>レベルアップ時に消費するアイテム。未指定時は無条件でレベルアップできます。</summary>
    public IReadOnlyList<SkillRequiredItemResponse> LevelUpRequiredItems { get; init; } = [];

    public IReadOnlyDictionary<string, object?> Params { get; init; } = new Dictionary<string, object?>();

    public IReadOnlyList<string> Tags { get; init; } = [];
}

public class SkillSummaryResponse
{
    public required string Id { get; init; }

    public required string Name { get; init; }

    public required string ImplementationId { get; init; }

    public string? Icon { get; init; }

    public IReadOnlyList<string> Tags { get; init; } = [];
}

public class SkillOnCastResponse
{
    public string? Sound { get; init; }
}

public class SkillPassiveResponse
{
    public bool BindRequired { get; init; } = true;
}

public class SkillLevelResponse
{
    public int Level { get; init; }

    public long CooldownTicksDelta { get; init; }

    public double ResourceCostDelta { get; init; }

    public long CastTimeTicksDelta { get; init; }

    public IReadOnlyDictionary<string, double> ParamDeltas { get; init; } = new Dictionary<string, double>();

    public IReadOnlyList<SkillStatusModifierResponse> StatusModifiers { get; init; } = [];
}

public class SkillStatusModifierResponse
{
    public required string Status { get; init; }

    public double Value { get; init; }
}

public class SkillSigilSlotResponse
{
    public int Level { get; init; }

    public int Slots { get; init; }
}

public class SkillRequiredItemResponse
{
    public required string ItemId { get; init; }

    public int Amount { get; init; } = 1;
}
