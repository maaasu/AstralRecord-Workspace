namespace AstralRecordApi.Models;

public class ClassResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public required string Name { get; init; }

    public string? Description { get; init; }

    public string? Icon { get; init; }

    public required string Role { get; init; }

    public int UnlockLevel { get; init; } = 1;

    public IReadOnlyList<ClassUnlockClassLevelResponse> UnlockClassLevel { get; init; } = [];

    public required IReadOnlyList<ClassStatResponse> BaseStats { get; init; }

    public IReadOnlyList<ClassStatResponse> GrowthPerLevel { get; init; } = [];

    public int ExpRate { get; init; } = 100;

    public IReadOnlyList<string> StarterSkills { get; init; } = [];

    public IReadOnlyList<ClassLevelSkillResponse> LevelSkills { get; init; } = [];

    public IReadOnlyList<string> Tags { get; init; } = [];
}

public class ClassSummaryResponse
{
    public required string Id { get; init; }

    public required string Name { get; init; }

    public required string Role { get; init; }
}

public class ClassUnlockClassLevelResponse
{
    public required string ClassId { get; init; }

    public int Level { get; init; }
}

public class ClassStatResponse
{
    public required string Status { get; init; }

    public double Value { get; init; }
}

public class ClassLevelSkillResponse
{
    public int Level { get; init; }

    public required string Skill { get; init; }
}
