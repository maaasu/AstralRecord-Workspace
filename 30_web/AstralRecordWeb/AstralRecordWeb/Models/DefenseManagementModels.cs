namespace AstralRecordWeb.Models;

public sealed class DefenseChannelState
{
    public string ServerId { get; init; } = string.Empty;
    public int Revision { get; init; }
    public bool DailyEnabled { get; init; }
    public string? DailyStartTimeJst { get; init; }
    public DefenseConfiguration Configuration { get; init; } = new();
    public IReadOnlyList<string> MissingSettings { get; init; } = [];
    public DateTimeOffset? NextScheduledAtUtc { get; init; }
    public string? RegistrationStatus { get; init; }
    public int? RegisteredCount { get; init; }
    public DefenseRun? ActiveRun { get; init; }
    public DefenseRun? LatestRun { get; init; }
}

public sealed class DefenseConfiguration
{
    public string? WorldName { get; init; }
    public double? CoreX { get; init; }
    public double? CoreY { get; init; }
    public double? CoreZ { get; init; }
    public double? EntryX { get; init; }
    public double? EntryY { get; init; }
    public double? EntryZ { get; init; }
    public IReadOnlyList<DefenseSpawnPoint> SpawnPoints { get; init; } = [];
    public double? CoreMaxHealth { get; init; }
    public double? CoreDefense { get; init; }
    public int? BaseSpawnCount { get; init; }
    public int? SpawnIntervalSeconds { get; init; }
    public int? MaxSpawnsPerWave { get; init; }
    public int? MaxSpawnsPerTick { get; init; }
    public int? MaxAliveEnemies { get; init; }
    public int? RetaliationSeconds { get; init; }
    public double? RetaliationRange { get; init; }
    public double? PlayerSpawnCoefficient { get; init; }
    public double? PlayerHealthCoefficient { get; init; }
    public double? PlayerDamageCoefficient { get; init; }
    public double? PostMaxLevelHealthCoefficient { get; init; }
    public double? PostMaxLevelDamageCoefficient { get; init; }
    public int? RegistrationLeadSeconds { get; init; }
    public int? ReconnectGraceSeconds { get; init; }
    public int? ReviveProtectionSeconds { get; init; }
    public string? ReviveShieldMode { get; init; }
    public int? RewardEveryLevels { get; init; }
    public int? MinimumActiveSeconds { get; init; }
    public IReadOnlyList<DefenseRewardTier> RewardTiers { get; init; } = [];
}

public sealed class DefenseSpawnPoint
{
    public double? X { get; init; }
    public double? Y { get; init; }
    public double? Z { get; init; }
}

public sealed class DefenseRewardTier
{
    public int? RequiredDefenseLevel { get; init; }
    public string? OrbItemId { get; init; }
    public int? OrbQuantity { get; init; }
}

public sealed class DefenseRun
{
    public Guid RunId { get; init; }
    public string Status { get; init; } = string.Empty;
    public string? Source { get; init; }
    public string ServerId { get; init; } = string.Empty;
    public Guid? InstanceId { get; init; }
    public DateTimeOffset? ScheduledAtUtc { get; init; }
    public DateTimeOffset? CreatedAtUtc { get; init; }
    public DateTimeOffset? StartedAtUtc { get; init; }
    public DateTimeOffset? EndedAtUtc { get; init; }
    public int? DefenseLevel { get; init; }
    public int? ParticipantCount { get; init; }
    public string? Reason { get; init; }
}

public sealed record DefenseSettingsUpdate(int ExpectedRevision, bool DailyEnabled, string? DailyStartTimeJst, DefenseConfiguration Configuration);
public sealed record DefenseStartRequest(Guid OperationId, int ExpectedRevision);
public sealed record DefenseStopRequest(Guid OperationId, Guid RunId, string Reason);
