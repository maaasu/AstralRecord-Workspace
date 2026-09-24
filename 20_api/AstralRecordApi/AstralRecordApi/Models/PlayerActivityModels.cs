namespace AstralRecordApi.Models;

/// <summary>Plugin が非同期キューから送る、ゲーム進行と独立した行動履歴バッチです。</summary>
public sealed class PlayerActivityBatchRequest
{
    public Guid BatchId { get; set; }
    public List<PlayerIpObservationRequest> IpObservations { get; set; } = [];
    public List<PlayerTradeActivityRequest> Trades { get; set; } = [];
    public List<DungeonClearActivityRequest> DungeonClears { get; set; } = [];
    public List<BossClearActivityRequest> BossClears { get; set; } = [];
    public List<MobDamageSummaryRequest> MobDamageSummaries { get; set; } = [];
    public List<MobPlayerDeathRequest> MobPlayerDeaths { get; set; } = [];
}

public sealed class PlayerActivityBatchResponse
{
    public Guid BatchId { get; init; }
    public bool Replayed { get; init; }
    public int AcceptedEventCount { get; init; }
}

/// <summary>履歴に残すプレイヤー表示名の時点スナップショットです。</summary>
public sealed class ActivityPlayerSnapshotRequest
{
    public Guid UserUuid { get; set; }
    public Guid AccountId { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public string AccountName { get; set; } = string.Empty;
}

public sealed class PlayerIpObservationRequest
{
    public Guid EventId { get; set; }
    public DateTime ObservedAt { get; set; }
    public string GlobalIp { get; set; } = string.Empty;
    public ActivityPlayerSnapshotRequest Player { get; set; } = new();
}

public sealed class PlayerTradeActivityRequest
{
    public Guid EventId { get; set; }
    public DateTime CompletedAt { get; set; }
    public ActivityPlayerSnapshotRequest Source { get; set; } = new();
    public ActivityPlayerSnapshotRequest Destination { get; set; } = new();
    public List<PlayerTradeItemRequest> Items { get; set; } = [];
    public long Gold { get; set; }
}

public sealed class PlayerTradeItemRequest
{
    public string ItemId { get; set; } = string.Empty;
    public string ItemName { get; set; } = string.Empty;
    public long Quantity { get; set; }
}

public sealed class DungeonClearActivityRequest
{
    public Guid EventId { get; set; }
    public string DungeonId { get; set; } = string.Empty;
    public string DungeonName { get; set; } = string.Empty;
    public DateTime StartedAt { get; set; }
    public DateTime ClearedAt { get; set; }
    public List<DungeonParticipantActivityRequest> Participants { get; set; } = [];
}

public sealed class DungeonParticipantActivityRequest
{
    public ActivityPlayerSnapshotRequest Player { get; set; } = new();
    /// <summary>null は移動観測が未実装または開始前参加であることを表し、0m と区別します。</summary>
    public decimal? DistanceMeters { get; set; }
    public int MovementSampleCount { get; set; }
}

public sealed class BossClearActivityRequest
{
    public Guid EventId { get; set; }
    public string BossId { get; set; } = string.Empty;
    public string BossName { get; set; } = string.Empty;
    public DateTime StartedAt { get; set; }
    public DateTime ClearedAt { get; set; }
    public List<BossParticipantActivityRequest> Participants { get; set; } = [];
}

public sealed class BossParticipantActivityRequest
{
    public ActivityPlayerSnapshotRequest Player { get; set; } = new();
    public decimal DamageDealt { get; set; }
    public int DeathCount { get; set; }
}

public sealed class MobDamageSummaryRequest
{
    public Guid EventId { get; set; }
    public string MobId { get; set; } = string.Empty;
    public string MobName { get; set; } = string.Empty;
    public DateTime WindowStartedAt { get; set; }
    public DateTime WindowEndedAt { get; set; }
    public ActivityPlayerSnapshotRequest Victim { get; set; } = new();
    public decimal Damage { get; set; }
    public int HitCount { get; set; }
}

public sealed class MobPlayerDeathRequest
{
    public Guid EventId { get; set; }
    public DateTime OccurredAt { get; set; }
    public string MobId { get; set; } = string.Empty;
    public string MobName { get; set; } = string.Empty;
    public ActivityPlayerSnapshotRequest Victim { get; set; } = new();
}

public sealed class PlayerActivityQuery
{
    public string? Query { get; init; }
    public Guid? UserUuid { get; init; }
    public Guid? OtherUserUuid { get; init; }
    public Guid? AccountId { get; init; }
    public string? DungeonId { get; init; }
    public string? BossId { get; init; }
    public string? MobId { get; init; }
    public string? Sort { get; init; }
    public string? EventType { get; init; }
    public DateTime? From { get; init; }
    public DateTime? To { get; init; }
    public int Page { get; init; } = 1;
    public int PageSize { get; init; } = 50;
}

public sealed class PagedPlayerActivityResponse<T>
{
    public required int Page { get; init; }
    public required int PageSize { get; init; }
    public required int TotalCount { get; init; }
    public required IReadOnlyList<T> Items { get; init; }
}

public sealed record ActivityPlayerSnapshotResponse(Guid UserUuid, Guid AccountId, string Mcid, string AccountName);
public sealed record SameIpActivityResponse(DateTime FirstObservedAt, DateTime LastObservedAt, int TradeCount, IReadOnlyList<ActivityPlayerSnapshotResponse> Players);
public sealed record PlayerTradeActivityResponse(Guid EventId, DateTime CompletedAt, ActivityPlayerSnapshotResponse Source, ActivityPlayerSnapshotResponse Destination, IReadOnlyList<PlayerTradeItemResponse> Items, long Gold);
public sealed record PlayerTradeItemResponse(string ItemId, string ItemName, long Quantity);
public sealed record DungeonParticipantActivityResponse(ActivityPlayerSnapshotResponse Player, decimal? DistanceMeters, int MovementSampleCount);
public sealed record DungeonClearActivityResponse(Guid EventId, string DungeonId, string DungeonName, DateTime StartedAt, DateTime ClearedAt, double DurationSeconds, IReadOnlyList<DungeonParticipantActivityResponse> Participants);
public sealed record DungeonPlayerSummaryResponse(ActivityPlayerSnapshotResponse Player, int ClearCount, DateTime FirstClearedAt, DateTime LastClearedAt, decimal? TotalDistanceMeters, double BestDurationSeconds, double AverageDurationSeconds);
public sealed record BossParticipantActivityResponse(ActivityPlayerSnapshotResponse Player, decimal DamageDealt, int DeathCount);
public sealed record BossClearActivityResponse(Guid EventId, string BossId, string BossName, DateTime StartedAt, DateTime ClearedAt, double DurationSeconds, IReadOnlyList<BossParticipantActivityResponse> Participants);
public sealed record BossPlayerSummaryResponse(ActivityPlayerSnapshotResponse Player, int ClearCount, DateTime FirstClearedAt, DateTime LastClearedAt, double BestDurationSeconds, double AverageDurationSeconds, decimal TotalDamageDealt, int TotalDeathCount);
public sealed record UserActivityEventResponse(long HistoryId, Guid? UserUuid, DateTime EventTime, string EventType, string Source, string Message, ActivityPlayerSnapshotResponse? Player);
public sealed record MobRankingResponse(string MobId, string MobName, int PlayerKillCount, decimal DamageToPlayers, int HitCount, DateTime LastOccurredAt);
public sealed record MobPlayerSummaryResponse(ActivityPlayerSnapshotResponse Player, int DeathCount, decimal DamageTaken, int HitCount, DateTime LastOccurredAt);
public sealed record MobPlayerDeathResponse(Guid EventId, DateTime OccurredAt, string MobId, string MobName, ActivityPlayerSnapshotResponse Victim);
