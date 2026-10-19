namespace AstralRecordApi.Models;

/// <summary>チャンネルごとの防衛戦調整値。未確定値は null として保存し、開始時に検証します。</summary>
public sealed class DefenseConfiguration
{
    public string? WorldName { get; init; }
    public double? CoreX { get; init; }
    public double? CoreY { get; init; }
    public double? CoreZ { get; init; }
    public double? EntryX { get; init; }
    public double? EntryY { get; init; }
    public double? EntryZ { get; init; }
    public IReadOnlyList<DefensePoint> SpawnPoints { get; init; } = [];
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

public sealed record DefensePoint(double? X, double? Y, double? Z);
public sealed record DefenseRewardTier(int? RequiredDefenseLevel, string? OrbItemId, int? OrbQuantity);

public sealed class DefenseChannelUpdateRequest
{
    public int ExpectedRevision { get; init; }
    public bool DailyEnabled { get; init; }
    public string? DailyStartTimeJst { get; init; }
    public DefenseConfiguration Configuration { get; init; } = new();
}

public sealed record DefenseStartRequest(Guid OperationId, int ExpectedRevision);
public sealed record DefenseStopRequest(Guid OperationId, Guid RunId, string Reason);

public sealed record DefenseChannelResponse(
    string ServerId, int Revision, bool DailyEnabled, string? DailyStartTimeJst,
    DefenseConfiguration Configuration, IReadOnlyList<string> MissingSettings,
    DateTime? NextScheduledAtUtc, DefenseRunResponse? ActiveRun, DefenseRunResponse? LatestRun,
    string RegistrationStatus, int? RegisteredCount);

public sealed record DefenseRunResponse(
    Guid RunId, string ServerId, string Source, string Status, DateTime? ScheduledAtUtc,
    DateTime CreatedAtUtc, DateTime? StartedAtUtc, DateTime? EndedAtUtc, Guid? InstanceId,
    DefenseConfiguration Configuration, int? DefenseLevel, int? ParticipantCount, string? Reason);

public sealed record DefenseRuntimeResponse(
    string ServerId, DefenseConfiguration Configuration, DefenseRunResponse? PendingRun,
    DefenseRunResponse? ActiveRun, IReadOnlyList<string> MissingSettings, DateTime ServerTimeUtc);

public sealed record DefenseParticipant(Guid UserUuid, Guid AccountId, int ActiveSeconds = 0,
    bool IsAfk = false, bool Eliminated = false);

public sealed class DefenseRunReportRequest
{
    public Guid InstanceId { get; init; }
    public Guid OperationId { get; init; }
    public string Event { get; init; } = string.Empty;
    public IReadOnlyList<DefenseParticipant> Participants { get; init; } = [];
    public int? DefenseLevel { get; init; }
    public string? Reason { get; init; }
}

public sealed record DefenseRewardEntry(Guid UserUuid, Guid AccountId, int RequiredDefenseLevel,
    int ActiveSeconds, bool IsAfk);
public sealed record DefenseRewardReportRequest(Guid InstanceId, Guid OperationId,
    IReadOnlyList<DefenseRewardEntry> Rewards);
public sealed record DefenseRegistrationReport(Guid InstanceId, Guid OperationId, string Status, int RegisteredCount);
public sealed record DefenseRewardReceipt(Guid AccountId, int RequiredDefenseLevel, string OrbItemId,
    int OrbQuantity, string MailId);
