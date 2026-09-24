using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class PlayerActivityRepository(HistoryDbContext history, TimeProvider clock) : IPlayerActivityRepository
{
    private const int MaxEventsPerBatch = 1000;
    public async Task<PlayerActivityBatchResponse> RecordBatchAsync(PlayerActivityBatchRequest request)
    {
        ValidateBatch(request);
        return await history.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
        history.ChangeTracker.Clear();
        await using var transaction = await history.Database.BeginTransactionAsync();
        if (await history.PlayerActivityBatches.AnyAsync(x => x.BatchId == request.BatchId))
            return new PlayerActivityBatchResponse { BatchId = request.BatchId, Replayed = true };
        var ipIds = request.IpObservations.Select(x => x.EventId).ToArray(); var tradeIds = request.Trades.Select(x => x.EventId).ToArray(); var dungeonIds = request.DungeonClears.Select(x => x.EventId).ToArray(); var bossIds = request.BossClears.Select(x => x.EventId).ToArray(); var damageIds = request.MobDamageSummaries.Select(x => x.EventId).ToArray(); var deathIds = request.MobPlayerDeaths.Select(x => x.EventId).ToArray();
        var existingIp = (await history.PlayerIpObservations.Where(x => ipIds.Contains(x.EventId)).Select(x => x.EventId).ToListAsync()).ToHashSet(); var existingTrades = (await history.PlayerTradeActivities.Where(x => tradeIds.Contains(x.EventId)).Select(x => x.EventId).ToListAsync()).ToHashSet(); var existingDungeons = (await history.DungeonClearActivities.Where(x => dungeonIds.Contains(x.EventId)).Select(x => x.EventId).ToListAsync()).ToHashSet(); var existingBosses = (await history.BossClearActivities.Where(x => bossIds.Contains(x.EventId)).Select(x => x.EventId).ToListAsync()).ToHashSet(); var existingDamage = (await history.MobDamageSummaries.Where(x => damageIds.Contains(x.EventId)).Select(x => x.EventId).ToListAsync()).ToHashSet(); var existingDeaths = (await history.MobPlayerDeaths.Where(x => deathIds.Contains(x.EventId)).Select(x => x.EventId).ToListAsync()).ToHashSet();
        var ips = request.IpObservations.Where(x => !existingIp.Contains(x.EventId)).ToArray(); var trades = request.Trades.Where(x => !existingTrades.Contains(x.EventId)).ToArray(); var dungeons = request.DungeonClears.Where(x => !existingDungeons.Contains(x.EventId)).ToArray(); var bosses = request.BossClears.Where(x => !existingBosses.Contains(x.EventId)).ToArray(); var damages = request.MobDamageSummaries.Where(x => !existingDamage.Contains(x.EventId)).ToArray(); var deaths = request.MobPlayerDeaths.Where(x => !existingDeaths.Contains(x.EventId)).ToArray();
        history.PlayerActivityBatches.Add(new PlayerActivityBatchEntity { BatchId = request.BatchId, ReceivedAt = clock.GetUtcNow().UtcDateTime });
        history.PlayerIpObservations.AddRange(ips.Select(x => new PlayerIpObservationEntity { EventId = x.EventId, ObservedAt = x.ObservedAt, GlobalIp = x.GlobalIp.Trim(), UserUuid = x.Player.UserUuid, AccountId = x.Player.AccountId, Mcid = x.Player.Mcid.Trim(), AccountName = x.Player.AccountName.Trim() }));
        history.PlayerTradeActivities.AddRange(trades.Select(x => new PlayerTradeActivityEntity { EventId = x.EventId, CompletedAt = x.CompletedAt, SourceUserUuid = x.Source.UserUuid, SourceAccountId = x.Source.AccountId, SourceMcid = x.Source.Mcid.Trim(), SourceAccountName = x.Source.AccountName.Trim(), DestinationUserUuid = x.Destination.UserUuid, DestinationAccountId = x.Destination.AccountId, DestinationMcid = x.Destination.Mcid.Trim(), DestinationAccountName = x.Destination.AccountName.Trim(), Gold = x.Gold, Items = x.Items.Select((item, index) => new PlayerTradeActivityItemEntity { EventId = x.EventId, LineNumber = index, ItemId = item.ItemId.Trim(), ItemName = item.ItemName.Trim(), Quantity = item.Quantity }).ToList() }));
        history.DungeonClearActivities.AddRange(dungeons.Select(x => new DungeonClearActivityEntity { EventId = x.EventId, DungeonId = x.DungeonId.Trim(), DungeonName = x.DungeonName.Trim(), StartedAt = x.StartedAt, ClearedAt = x.ClearedAt, DurationMilliseconds = (x.ClearedAt - x.StartedAt).Ticks / TimeSpan.TicksPerMillisecond, Participants = x.Participants.Select(p => new DungeonParticipantActivityEntity { EventId = x.EventId, UserUuid = p.Player.UserUuid, AccountId = p.Player.AccountId, Mcid = p.Player.Mcid.Trim(), AccountName = p.Player.AccountName.Trim(), DistanceMeters = p.DistanceMeters, MovementSampleCount = p.MovementSampleCount }).ToList() }));
        history.BossClearActivities.AddRange(bosses.Select(x => new BossClearActivityEntity { EventId = x.EventId, BossId = x.BossId.Trim(), BossName = x.BossName.Trim(), StartedAt = x.StartedAt, ClearedAt = x.ClearedAt, DurationMilliseconds = (x.ClearedAt - x.StartedAt).Ticks / TimeSpan.TicksPerMillisecond, Participants = x.Participants.Select(p => new BossParticipantActivityEntity { EventId = x.EventId, UserUuid = p.Player.UserUuid, AccountId = p.Player.AccountId, Mcid = p.Player.Mcid.Trim(), AccountName = p.Player.AccountName.Trim(), DamageDealt = decimal.Round(p.DamageDealt, 3, MidpointRounding.AwayFromZero), DeathCount = p.DeathCount }).ToList() }));
        history.MobDamageSummaries.AddRange(damages.Select(x => new MobDamageSummaryEntity { EventId = x.EventId, MobId = x.MobId.Trim(), MobName = x.MobName.Trim(), WindowStartedAt = x.WindowStartedAt, WindowEndedAt = x.WindowEndedAt, VictimUserUuid = x.Victim.UserUuid, VictimAccountId = x.Victim.AccountId, VictimMcid = x.Victim.Mcid.Trim(), VictimAccountName = x.Victim.AccountName.Trim(), Damage = x.Damage, HitCount = x.HitCount }));
        history.MobPlayerDeaths.AddRange(deaths.Select(x => new MobPlayerDeathEntity { EventId = x.EventId, OccurredAt = x.OccurredAt, MobId = x.MobId.Trim(), MobName = x.MobName.Trim(), VictimUserUuid = x.Victim.UserUuid, VictimAccountId = x.Victim.AccountId, VictimMcid = x.Victim.Mcid.Trim(), VictimAccountName = x.Victim.AccountName.Trim() }));
        await history.SaveChangesAsync(); await transaction.CommitAsync();
        return new PlayerActivityBatchResponse { BatchId = request.BatchId, AcceptedEventCount = ips.Length + trades.Length + dungeons.Length + bosses.Length + damages.Length + deaths.Length };
        });
    }

    public async Task<PagedPlayerActivityResponse<SameIpActivityResponse>> GetSameIpAsync(PlayerActivityQuery query)
    {
        var (from, to, page, size) = Page(query);
        var observationRows = history.PlayerIpObservations.AsNoTracking().Where(x => x.ObservedAt >= from && x.ObservedAt < to);
        var term = query.Query?.Trim() ?? string.Empty;
        // ログイン回数による結合行数の増幅を防ぎ、IP・ユーザーごとに畳んでから関連を作る。
        var observations = observationRows.GroupBy(x => new { x.GlobalIp, x.UserUuid }).Select(group => new
        {
            group.Key.GlobalIp,
            group.Key.UserUuid,
            First = group.Min(x => x.ObservedAt),
            Last = group.Max(x => x.ObservedAt),
            Matches = group.Max(x => term == "" || x.Mcid.Contains(term) || x.AccountName.Contains(term) ? 1 : 0),
        });
        var commonIpPairs = from player in observations
                            join related in observations on player.GlobalIp equals related.GlobalIp
                            where player.UserUuid.CompareTo(related.UserUuid) < 0
                                && (player.Matches > 0 || related.Matches > 0)
                            select new { Player = player, Related = related };
        var pairs = commonIpPairs.GroupBy(x => new { PlayerUserUuid = x.Player.UserUuid, RelatedUserUuid = x.Related.UserUuid }).Select(group => new
        {
            group.Key.PlayerUserUuid,
            group.Key.RelatedUserUuid,
            First = group.Min(x => x.Player.First < x.Related.First ? x.Player.First : x.Related.First),
            Last = group.Max(x => x.Player.Last > x.Related.Last ? x.Player.Last : x.Related.Last),
        });
        if (query.UserUuid is { } user) pairs = pairs.Where(x => x.PlayerUserUuid == user || x.RelatedUserUuid == user);
        if (query.OtherUserUuid is { } other && query.UserUuid is { } selected) pairs = pairs.Where(x => (x.PlayerUserUuid == selected && x.RelatedUserUuid == other) || (x.PlayerUserUuid == other && x.RelatedUserUuid == selected));
        var result = pairs.Select(x => new { x.PlayerUserUuid, x.RelatedUserUuid, x.First, x.Last, TradeCount = history.PlayerTradeActivities.Count(t => t.CompletedAt >= from && t.CompletedAt < to && ((t.SourceUserUuid == x.PlayerUserUuid && t.DestinationUserUuid == x.RelatedUserUuid) || (t.SourceUserUuid == x.RelatedUserUuid && t.DestinationUserUuid == x.PlayerUserUuid))) });
        var total = await result.CountAsync(); var rows = await result.OrderByDescending(x => x.TradeCount).ThenByDescending(x => x.Last).ThenBy(x => x.PlayerUserUuid).ThenBy(x => x.RelatedUserUuid).Skip((page - 1) * size).Take(size).ToListAsync();
        var userIds = rows.SelectMany(x => new[] { x.PlayerUserUuid, x.RelatedUserUuid }).Distinct().ToArray();
        var snapshotRows = await observationRows.Where(x => userIds.Contains(x.UserUuid)).GroupBy(x => x.UserUuid)
            .Select(group => group.OrderByDescending(x => x.ObservedAt).ThenByDescending(x => x.EventId).First()).ToListAsync();
        var snapshots = snapshotRows.ToDictionary(x => x.UserUuid, Player);
        return new PagedPlayerActivityResponse<SameIpActivityResponse> { Page = page, PageSize = size, TotalCount = total, Items = rows.Select(x => new SameIpActivityResponse(x.First, x.Last, x.TradeCount, [snapshots[x.PlayerUserUuid], snapshots[x.RelatedUserUuid]])).ToArray() };
    }

    public async Task<PagedPlayerActivityResponse<PlayerTradeActivityResponse>> GetTradesAsync(PlayerActivityQuery query)
    {
        var (from, to, page, size) = Page(query); var q = history.PlayerTradeActivities.AsNoTracking().Include(x => x.Items).Where(x => x.CompletedAt >= from && x.CompletedAt < to);
        if (query.UserUuid is { } user) q = q.Where(x => x.SourceUserUuid == user || x.DestinationUserUuid == user);
        if (query.OtherUserUuid is { } other && query.UserUuid is { } selected) q = q.Where(x => (x.SourceUserUuid == selected && x.DestinationUserUuid == other) || (x.SourceUserUuid == other && x.DestinationUserUuid == selected));
        if (query.AccountId is { } account) q = q.Where(x => x.SourceAccountId == account || x.DestinationAccountId == account);
        if (!string.IsNullOrWhiteSpace(query.Query)) { var term = query.Query.Trim(); q = q.Where(x => x.SourceMcid.Contains(term) || x.SourceAccountName.Contains(term) || x.DestinationMcid.Contains(term) || x.DestinationAccountName.Contains(term) || x.Items.Any(i => i.ItemId.Contains(term) || i.ItemName.Contains(term))); }
        var total = await q.CountAsync(); var rows = (await q.OrderByDescending(x => x.CompletedAt).Skip((page - 1) * size).Take(size).ToListAsync()).Select(Map).ToList(); return new PagedPlayerActivityResponse<PlayerTradeActivityResponse> { Page = page, PageSize = size, TotalCount = total, Items = rows };
    }

    public async Task<PagedPlayerActivityResponse<DungeonClearActivityResponse>> GetDungeonsAsync(PlayerActivityQuery query)
    {
        var sort = RunSort(query.Sort);
        var (from, to, page, size) = Page(query); var q = history.DungeonClearActivities.AsNoTracking().Include(x => x.Participants).Where(x => x.ClearedAt >= from && x.ClearedAt < to);
        if (!string.IsNullOrWhiteSpace(query.DungeonId)) q = q.Where(x => x.DungeonId == query.DungeonId.Trim()); if (query.AccountId is { } account) q = q.Where(x => x.Participants.Any(p => p.AccountId == account)); if (query.UserUuid is { } user) q = q.Where(x => x.Participants.Any(p => p.UserUuid == user));
        if (!string.IsNullOrWhiteSpace(query.Query)) { var term = query.Query.Trim(); q = q.Where(x => x.DungeonId.Contains(term) || x.DungeonName.Contains(term) || x.Participants.Any(p => p.Mcid.Contains(term) || p.AccountName.Contains(term))); }
        var ordered = sort switch { "fastest" => q.OrderBy(x => x.DurationMilliseconds).ThenByDescending(x => x.ClearedAt).ThenBy(x => x.EventId), "slowest" => q.OrderByDescending(x => x.DurationMilliseconds).ThenByDescending(x => x.ClearedAt).ThenBy(x => x.EventId), _ => q.OrderByDescending(x => x.ClearedAt).ThenBy(x => x.EventId) };
        var total = await q.CountAsync(); var rows = (await ordered.Skip((page - 1) * size).Take(size).ToListAsync()).Select(Map).ToList(); return new PagedPlayerActivityResponse<DungeonClearActivityResponse> { Page = page, PageSize = size, TotalCount = total, Items = rows };
    }

    public async Task<PagedPlayerActivityResponse<DungeonPlayerSummaryResponse>> GetDungeonPlayersAsync(PlayerActivityQuery query)
    {
        var sort = PlayerSort(query.Sort);
        var (rangeStart, rangeEnd, page, size) = Page(query); var q = from run in history.DungeonClearActivities.AsNoTracking().Where(x => x.ClearedAt >= rangeStart && x.ClearedAt < rangeEnd) from p in history.DungeonParticipantActivities.AsNoTracking().Where(p => p.EventId == run.EventId) select new { run, p };
        if (!string.IsNullOrWhiteSpace(query.DungeonId)) q = q.Where(x => x.run.DungeonId == query.DungeonId.Trim()); if (query.AccountId is { } account) q = q.Where(x => x.p.AccountId == account); if (query.UserUuid is { } user) q = q.Where(x => x.p.UserUuid == user);
        if (!string.IsNullOrWhiteSpace(query.Query)) { var term = query.Query.Trim(); q = q.Where(x => x.p.Mcid.Contains(term) || x.p.AccountName.Contains(term)); }
        var grouped = q.GroupBy(x => x.p.AccountId).Select(g => new { AccountId = g.Key, ClearCount = g.Count(), First = g.Min(x => x.run.ClearedAt), Last = g.Max(x => x.run.ClearedAt), DistanceCount = g.Count(x => x.p.DistanceMeters != null), Distance = g.Sum(x => x.p.DistanceMeters ?? 0m), BestDuration = g.Min(x => x.run.DurationMilliseconds), AverageDuration = g.Average(x => x.run.DurationMilliseconds) });
        var ordered = sort switch { "fastest" => grouped.OrderBy(x => x.BestDuration).ThenByDescending(x => x.ClearCount).ThenBy(x => x.AccountId), "slowest" => grouped.OrderByDescending(x => x.AverageDuration).ThenByDescending(x => x.ClearCount).ThenBy(x => x.AccountId), _ => grouped.OrderByDescending(x => x.ClearCount).ThenBy(x => x.AccountId) };
        var total = await grouped.CountAsync(); var rows = await ordered.Skip((page - 1) * size).Take(size).ToListAsync();
        var accountIds = rows.Select(x => x.AccountId).ToArray();
        var snapshots = (await q.Where(x => accountIds.Contains(x.p.AccountId)).OrderByDescending(x => x.run.ClearedAt).ThenByDescending(x => x.p.EventId).Select(x => x.p).ToListAsync()).GroupBy(x => x.AccountId).ToDictionary(group => group.Key, group => Player(group.First()));
        return new PagedPlayerActivityResponse<DungeonPlayerSummaryResponse> { Page = page, PageSize = size, TotalCount = total, Items = rows.Select(x => new DungeonPlayerSummaryResponse(snapshots[x.AccountId], x.ClearCount, x.First, x.Last, x.DistanceCount == 0 ? null : x.Distance, x.BestDuration / 1000d, x.AverageDuration / 1000d)).ToArray() };
    }

    public async Task<PagedPlayerActivityResponse<BossClearActivityResponse>> GetBossesAsync(PlayerActivityQuery query)
    {
        var sort = RunSort(query.Sort);
        var (from, to, page, size) = Page(query);
        var q = history.BossClearActivities.AsNoTracking().Include(x => x.Participants).Where(x => x.ClearedAt >= from && x.ClearedAt < to);
        if (!string.IsNullOrWhiteSpace(query.BossId)) q = q.Where(x => x.BossId == query.BossId.Trim());
        if (query.AccountId is { } account) q = q.Where(x => x.Participants.Any(p => p.AccountId == account));
        if (query.UserUuid is { } user) q = q.Where(x => x.Participants.Any(p => p.UserUuid == user));
        if (!string.IsNullOrWhiteSpace(query.Query)) { var term = query.Query.Trim(); q = q.Where(x => x.BossId.Contains(term) || x.BossName.Contains(term) || x.Participants.Any(p => p.Mcid.Contains(term) || p.AccountName.Contains(term))); }
        var ordered = sort switch { "fastest" => q.OrderBy(x => x.DurationMilliseconds).ThenByDescending(x => x.ClearedAt).ThenBy(x => x.EventId), "slowest" => q.OrderByDescending(x => x.DurationMilliseconds).ThenByDescending(x => x.ClearedAt).ThenBy(x => x.EventId), _ => q.OrderByDescending(x => x.ClearedAt).ThenBy(x => x.EventId) };
        var total = await q.CountAsync(); var rows = (await ordered.Skip((page - 1) * size).Take(size).ToListAsync()).Select(Map).ToArray();
        return new PagedPlayerActivityResponse<BossClearActivityResponse> { Page = page, PageSize = size, TotalCount = total, Items = rows };
    }

    public async Task<PagedPlayerActivityResponse<BossPlayerSummaryResponse>> GetBossPlayersAsync(PlayerActivityQuery query)
    {
        var sort = PlayerSort(query.Sort);
        var (rangeStart, rangeEnd, page, size) = Page(query);
        var q = from run in history.BossClearActivities.AsNoTracking().Where(x => x.ClearedAt >= rangeStart && x.ClearedAt < rangeEnd)
                from p in history.BossParticipantActivities.AsNoTracking().Where(p => p.EventId == run.EventId)
                select new { run, p };
        if (!string.IsNullOrWhiteSpace(query.BossId)) q = q.Where(x => x.run.BossId == query.BossId.Trim());
        if (query.AccountId is { } account) q = q.Where(x => x.p.AccountId == account);
        if (query.UserUuid is { } user) q = q.Where(x => x.p.UserUuid == user);
        if (!string.IsNullOrWhiteSpace(query.Query)) { var term = query.Query.Trim(); q = q.Where(x => x.p.Mcid.Contains(term) || x.p.AccountName.Contains(term)); }
        var grouped = q.GroupBy(x => x.p.AccountId).Select(g => new { AccountId = g.Key, ClearCount = g.Count(), First = g.Min(x => x.run.ClearedAt), Last = g.Max(x => x.run.ClearedAt), BestDuration = g.Min(x => x.run.DurationMilliseconds), AverageDuration = g.Average(x => x.run.DurationMilliseconds), Damage = g.Sum(x => x.p.DamageDealt), Deaths = g.Sum(x => x.p.DeathCount) });
        var ordered = sort switch { "fastest" => grouped.OrderBy(x => x.BestDuration).ThenByDescending(x => x.ClearCount).ThenBy(x => x.AccountId), "slowest" => grouped.OrderByDescending(x => x.AverageDuration).ThenByDescending(x => x.ClearCount).ThenBy(x => x.AccountId), _ => grouped.OrderByDescending(x => x.ClearCount).ThenBy(x => x.AccountId) };
        var total = await grouped.CountAsync(); var rows = await ordered.Skip((page - 1) * size).Take(size).ToListAsync();
        var accountIds = rows.Select(x => x.AccountId).ToArray();
        var snapshots = (await q.Where(x => accountIds.Contains(x.p.AccountId)).OrderByDescending(x => x.run.ClearedAt).ThenByDescending(x => x.p.EventId).Select(x => x.p).ToListAsync()).GroupBy(x => x.AccountId).ToDictionary(g => g.Key, g => Player(g.First()));
        return new PagedPlayerActivityResponse<BossPlayerSummaryResponse> { Page = page, PageSize = size, TotalCount = total, Items = rows.Select(x => new BossPlayerSummaryResponse(snapshots[x.AccountId], x.ClearCount, x.First, x.Last, x.BestDuration / 1000d, x.AverageDuration / 1000d, x.Damage, x.Deaths)).ToArray() };
    }

    public async Task<PagedPlayerActivityResponse<UserActivityEventResponse>> GetEventsAsync(PlayerActivityQuery query)
    {
        var (from, to, page, size) = Page(query);
        var q = history.UserHistories.AsNoTracking().Where(x => x.EventTime >= from && x.EventTime < to);
        if (query.UserUuid is { } user) q = q.Where(x => x.UserUuid == user);
        if (!string.IsNullOrWhiteSpace(query.EventType)) q = q.Where(x => x.EventType == query.EventType.Trim());
        if (!string.IsNullOrWhiteSpace(query.Query)) { var term = query.Query.Trim(); q = q.Where(x => x.Message.Contains(term) || x.EventType.Contains(term) || x.Source.Contains(term)); }
        var total = await q.CountAsync(); var rows = await q.OrderByDescending(x => x.EventTime).ThenByDescending(x => x.HistoryId).Skip((page - 1) * size).Take(size).Select(x => new UserActivityEventResponse(x.HistoryId, x.UserUuid, x.EventTime, x.EventType, x.Source, x.Message)).ToArrayAsync();
        return new PagedPlayerActivityResponse<UserActivityEventResponse> { Page = page, PageSize = size, TotalCount = total, Items = rows };
    }

    public async Task<PagedPlayerActivityResponse<MobRankingResponse>> GetMobsAsync(PlayerActivityQuery query)
    {
        var (from, to, page, size) = Page(query); var damages = await history.MobDamageSummaries.AsNoTracking().Where(x => x.WindowEndedAt >= from && x.WindowEndedAt < to).GroupBy(x => x.MobId).Select(g => new { MobId = g.Key, MobName = g.Max(x => x.MobName), Damage = g.Sum(x => x.Damage), Hits = g.Sum(x => x.HitCount), Last = g.Max(x => x.WindowEndedAt) }).ToListAsync(); var deaths = await history.MobPlayerDeaths.AsNoTracking().Where(x => x.OccurredAt >= from && x.OccurredAt < to).GroupBy(x => x.MobId).Select(g => new { MobId = g.Key, MobName = g.Max(x => x.MobName), Kills = g.Count(), Last = g.Max(x => x.OccurredAt) }).ToListAsync();
        var rows = damages.Select(x => x.MobId).Concat(deaths.Select(x => x.MobId)).Distinct().Select(id => { var damage = damages.SingleOrDefault(x => x.MobId == id); var death = deaths.SingleOrDefault(x => x.MobId == id); return new MobRankingResponse(id, damage?.MobName ?? death?.MobName ?? id, death?.Kills ?? 0, damage?.Damage ?? 0, damage?.Hits ?? 0, new[] { damage?.Last ?? DateTime.MinValue, death?.Last ?? DateTime.MinValue }.Max()); }).Where(x => Matches(query.MobId, x.MobId, x.MobName) && Matches(query.Query, x.MobId, x.MobName)).OrderByDescending(x => x.PlayerKillCount).ThenByDescending(x => x.DamageToPlayers).ToList(); return Page(rows, page, size);
    }

    public async Task<PagedPlayerActivityResponse<MobPlayerSummaryResponse>> GetMobPlayersAsync(string mobId, PlayerActivityQuery query)
    {
        var (from, to, page, size) = Page(query); var damages = await history.MobDamageSummaries.AsNoTracking().Where(x => x.MobId == mobId && x.WindowEndedAt >= from && x.WindowEndedAt < to).GroupBy(x => x.VictimAccountId).Select(g => new { AccountId = g.Key, Damage = g.Sum(x => x.Damage), Hits = g.Sum(x => x.HitCount), Last = g.Max(x => x.WindowEndedAt) }).ToListAsync(); var deaths = await history.MobPlayerDeaths.AsNoTracking().Where(x => x.MobId == mobId && x.OccurredAt >= from && x.OccurredAt < to).GroupBy(x => x.VictimAccountId).Select(g => new { AccountId = g.Key, Kills = g.Count(), Last = g.Max(x => x.OccurredAt) }).ToListAsync();
        var accountIds = damages.Select(x => x.AccountId).Concat(deaths.Select(x => x.AccountId)).Distinct().ToArray();
        var damageSnapshots = await history.MobDamageSummaries.AsNoTracking().Where(x => x.MobId == mobId && x.WindowEndedAt >= from && x.WindowEndedAt < to && accountIds.Contains(x.VictimAccountId)).OrderByDescending(x => x.WindowEndedAt).ThenByDescending(x => x.EventId).ToListAsync();
        var deathSnapshots = await history.MobPlayerDeaths.AsNoTracking().Where(x => x.MobId == mobId && x.OccurredAt >= from && x.OccurredAt < to && accountIds.Contains(x.VictimAccountId)).OrderByDescending(x => x.OccurredAt).ThenByDescending(x => x.EventId).ToListAsync();
        var snapshots = damageSnapshots.Select(x => (AccountId: x.VictimAccountId, At: x.WindowEndedAt, EventId: x.EventId, Player: Player(x))).Concat(deathSnapshots.Select(x => (AccountId: x.VictimAccountId, At: x.OccurredAt, EventId: x.EventId, Player: Player(x)))).GroupBy(x => x.AccountId).ToDictionary(group => group.Key, group => group.OrderByDescending(x => x.At).ThenByDescending(x => x.EventId).First().Player);
        var rows = accountIds.Select(accountId => { var damage = damages.SingleOrDefault(x => x.AccountId == accountId); var death = deaths.SingleOrDefault(x => x.AccountId == accountId); return new MobPlayerSummaryResponse(snapshots[accountId], death?.Kills ?? 0, damage?.Damage ?? 0, damage?.Hits ?? 0, new[] { damage?.Last ?? DateTime.MinValue, death?.Last ?? DateTime.MinValue }.Max()); }).Where(x => Matches(query.Query, x.Player)).OrderByDescending(x => x.DeathCount).ThenByDescending(x => x.DamageTaken).ToList(); return Page(rows, page, size);
    }

    public async Task<PagedPlayerActivityResponse<MobPlayerDeathResponse>> GetMobDeathsAsync(string mobId, PlayerActivityQuery query)
    { var (from, to, page, size) = Page(query); var q = history.MobPlayerDeaths.AsNoTracking().Where(x => x.MobId == mobId && x.OccurredAt >= from && x.OccurredAt < to); if (query.UserUuid is { } user) q = q.Where(x => x.VictimUserUuid == user); if (query.AccountId is { } account) q = q.Where(x => x.VictimAccountId == account); if (!string.IsNullOrWhiteSpace(query.Query)) { var term = query.Query.Trim(); q = q.Where(x => x.VictimMcid.Contains(term) || x.VictimAccountName.Contains(term)); } var total = await q.CountAsync(); var rows = (await q.OrderByDescending(x => x.OccurredAt).Skip((page - 1) * size).Take(size).ToListAsync()).Select(x => new MobPlayerDeathResponse(x.EventId, x.OccurredAt, x.MobId, x.MobName, Player(x))).ToList(); return new PagedPlayerActivityResponse<MobPlayerDeathResponse> { Page = page, PageSize = size, TotalCount = total, Items = rows }; }

    private static PlayerTradeActivityResponse Map(PlayerTradeActivityEntity x) => new(x.EventId, x.CompletedAt, new ActivityPlayerSnapshotResponse(x.SourceUserUuid, x.SourceAccountId, x.SourceMcid, x.SourceAccountName), new ActivityPlayerSnapshotResponse(x.DestinationUserUuid, x.DestinationAccountId, x.DestinationMcid, x.DestinationAccountName), x.Items.OrderBy(i => i.LineNumber).Select(i => new PlayerTradeItemResponse(i.ItemId, i.ItemName, i.Quantity)).ToArray(), x.Gold);
    private static DungeonClearActivityResponse Map(DungeonClearActivityEntity x) => new(x.EventId, x.DungeonId, x.DungeonName, x.StartedAt, x.ClearedAt, x.DurationMilliseconds / 1000d, x.Participants.Select(p => new DungeonParticipantActivityResponse(Player(p), p.DistanceMeters, p.MovementSampleCount)).ToArray());
    private static BossClearActivityResponse Map(BossClearActivityEntity x) => new(x.EventId, x.BossId, x.BossName, x.StartedAt, x.ClearedAt, x.DurationMilliseconds / 1000d, x.Participants.OrderBy(p => p.AccountId).Select(p => new BossParticipantActivityResponse(Player(p), p.DamageDealt, p.DeathCount)).ToArray());
    private static ActivityPlayerSnapshotResponse Player(PlayerIpObservationEntity x) => new(x.UserUuid, x.AccountId, x.Mcid, x.AccountName); private static ActivityPlayerSnapshotResponse Player(DungeonParticipantActivityEntity x) => new(x.UserUuid, x.AccountId, x.Mcid, x.AccountName); private static ActivityPlayerSnapshotResponse Player(BossParticipantActivityEntity x) => new(x.UserUuid, x.AccountId, x.Mcid, x.AccountName); private static ActivityPlayerSnapshotResponse Player(MobDamageSummaryEntity x) => new(x.VictimUserUuid, x.VictimAccountId, x.VictimMcid, x.VictimAccountName); private static ActivityPlayerSnapshotResponse Player(MobPlayerDeathEntity x) => new(x.VictimUserUuid, x.VictimAccountId, x.VictimMcid, x.VictimAccountName);
    private static bool Matches(string? query, ActivityPlayerSnapshotResponse player) => Matches(query, player.Mcid, player.AccountName); private static bool Matches(string? query, params string[] values) => string.IsNullOrWhiteSpace(query) || values.Any(value => value.Contains(query.Trim(), StringComparison.OrdinalIgnoreCase));
    private (DateTime From, DateTime To, int Page, int Size) Page(PlayerActivityQuery query) { var to = query.To?.ToUniversalTime() ?? clock.GetUtcNow().UtcDateTime; var from = query.From?.ToUniversalTime() ?? to.AddDays(-30); if (from >= to || to - from > TimeSpan.FromDays(366)) throw new ArgumentException("from/to must be a UTC range within 366 days."); return (from, to, Math.Max(1, query.Page), Math.Clamp(query.PageSize, 1, 100)); }
    private static PagedPlayerActivityResponse<T> Page<T>(IReadOnlyList<T> rows, int page, int size) => new() { Page = page, PageSize = size, TotalCount = rows.Count, Items = rows.Skip((page - 1) * size).Take(size).ToArray() };
    private static string RunSort(string? sort) => sort?.Trim().ToLowerInvariant() switch { null or "" or "recent" => "recent", "fastest" => "fastest", "slowest" => "slowest", _ => throw new ArgumentException("sort must be recent, fastest, or slowest.") };
    private static string PlayerSort(string? sort) => sort?.Trim().ToLowerInvariant() switch { null or "" or "count" => "count", "fastest" => "fastest", "slowest" => "slowest", _ => throw new ArgumentException("sort must be count, fastest, or slowest.") };
    private static void ValidateBatch(PlayerActivityBatchRequest request)
    {
        if (request.BatchId == Guid.Empty) throw new ArgumentException("batchId is required.");
        if (request.BossClears is null) throw new ArgumentException("bossClears must be an array.");
        var all = request.IpObservations.Select(x => x.EventId).Concat(request.Trades.Select(x => x.EventId)).Concat(request.DungeonClears.Select(x => x.EventId)).Concat(request.BossClears.Select(x => x.EventId)).Concat(request.MobDamageSummaries.Select(x => x.EventId)).Concat(request.MobPlayerDeaths.Select(x => x.EventId)).ToArray();
        if (all.Length > MaxEventsPerBatch || all.Any(x => x == Guid.Empty) || all.Distinct().Count() != all.Length) throw new ArgumentException("events must have unique IDs and be at most 1000.");
        if (request.IpObservations.Any(x => string.IsNullOrWhiteSpace(x.GlobalIp)) || request.Trades.Any(x => x.Source.AccountId == Guid.Empty || x.Destination.AccountId == Guid.Empty || x.Gold < 0 || x.Items.Any(i => string.IsNullOrWhiteSpace(i.ItemId) || i.Quantity < 1)) || request.DungeonClears.Any(x => string.IsNullOrWhiteSpace(x.DungeonId) || x.ClearedAt < x.StartedAt || x.Participants.Count == 0 || x.Participants.Any(p => p.Player.AccountId == Guid.Empty || p.DistanceMeters < 0 || p.MovementSampleCount < 0)) || request.BossClears.Any(x => x is null || string.IsNullOrWhiteSpace(x.BossId) || x.BossId.Length > 100 || string.IsNullOrWhiteSpace(x.BossName) || x.BossName.Length > 200 || x.ClearedAt < x.StartedAt || x.Participants is null || x.Participants.Count == 0 || x.Participants.Any(p => p is null || p.Player is null) || x.Participants.Select(p => p.Player.AccountId).Distinct().Count() != x.Participants.Count || x.Participants.Any(p => p.Player.AccountId == Guid.Empty || p.Player.UserUuid == Guid.Empty || string.IsNullOrWhiteSpace(p.Player.Mcid) || p.Player.Mcid.Length > 20 || string.IsNullOrWhiteSpace(p.Player.AccountName) || p.Player.AccountName.Length > 50 || p.DamageDealt < 0 || p.DamageDealt >= 1000000000000000m || decimal.Round(p.DamageDealt, 3, MidpointRounding.AwayFromZero) >= 1000000000000000m || p.DeathCount < 0)) || request.MobDamageSummaries.Any(x => string.IsNullOrWhiteSpace(x.MobId) || x.WindowEndedAt < x.WindowStartedAt || x.Damage < 0 || x.HitCount < 0) || request.MobPlayerDeaths.Any(x => string.IsNullOrWhiteSpace(x.MobId))) throw new ArgumentException("activity event is invalid.");
    }
}
