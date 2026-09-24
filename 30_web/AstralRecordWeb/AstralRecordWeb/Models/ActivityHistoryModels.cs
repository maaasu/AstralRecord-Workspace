namespace AstralRecordWeb.Models;

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
