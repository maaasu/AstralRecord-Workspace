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
public sealed record DungeonClearActivityResponse(Guid EventId, string DungeonId, string DungeonName, DateTime StartedAt, DateTime ClearedAt, long DurationSeconds, IReadOnlyList<DungeonParticipantActivityResponse> Participants);
public sealed record DungeonPlayerSummaryResponse(ActivityPlayerSnapshotResponse Player, int ClearCount, DateTime FirstClearedAt, DateTime LastClearedAt, decimal? TotalDistanceMeters);
public sealed record MobRankingResponse(string MobId, string MobName, int PlayerKillCount, decimal DamageToPlayers, int HitCount, DateTime LastOccurredAt);
public sealed record MobPlayerSummaryResponse(ActivityPlayerSnapshotResponse Player, int DeathCount, decimal DamageTaken, int HitCount, DateTime LastOccurredAt);
public sealed record MobPlayerDeathResponse(Guid EventId, DateTime OccurredAt, string MobId, string MobName, ActivityPlayerSnapshotResponse Victim);
