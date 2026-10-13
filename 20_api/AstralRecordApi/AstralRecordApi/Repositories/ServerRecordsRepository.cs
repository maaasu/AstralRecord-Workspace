using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class ServerRecordsRepository(
    AstralRecordDbContext db, INetworkManagementRepository network, TimeProvider clock, INetworkRuntimeService runtime,
    ServerRecordsCache? cache = null) : IServerRecordsRepository
{
    public async Task<ServerRecordsResponse> GetAsync(int limit, CancellationToken cancellationToken = default)
    {
        if (limit is < 1 or > 100) throw new ArgumentOutOfRangeException(nameof(limit));
        // ManagementDB の永続UUID一覧を使うため、オフライン運営も除外する。
        var authorities = ((await network.GetSettingsAsync())?.AuthorityUsers ?? runtime.GetAuthorities()).ToArray();
        return cache is null
            ? await ReadRankingsAsync(authorities, limit, cancellationToken)
            : await cache.GetAsync(authorities, limit,
                token => ReadRankingsAsync(authorities, ServerRecordsCache.MaximumLimit, token), cancellationToken);
    }

    private async Task<ServerRecordsResponse> ReadRankingsAsync(Guid[] authorities, int limit, CancellationToken cancellationToken)
    {
        var users = db.Users.AsNoTracking().Where(user => !user.IsDeleted && user.Permission == 0
            && !authorities.Contains(user.Uuid));
        var accounts = db.Accounts.AsNoTracking().Where(account => !account.IsDeleted && account.Mode == 0)
            .Join(users, account => account.UserId, user => user.Uuid,
                (account, user) => new { Account = account, User = user });
        var rankings = new List<ServerRankingResponse>();
        var level = accounts.GroupBy(row => new { row.User.Uuid, row.User.Mcid })
            .Select(group => new Score { UserUuid = group.Key.Uuid, PlayerName = group.Key.Mcid, Value = group.Max(row => (long)row.Account.Level) });
        rankings.Add(await ReadAsync("level", "level", level, limit, cancellationToken));
        IQueryable<Score> SumAmounts(IQueryable<AccountAmount> amounts) =>
            accounts.Join(amounts, row => row.Account.Uuid, amount => amount.AccountId,
                    (row, amount) => new { row.User.Uuid, row.User.Mcid, amount.Amount })
                .GroupBy(row => new { row.Uuid, row.Mcid })
                .Select(group => new { group.Key.Uuid, group.Key.Mcid, Total = group.Sum(row => row.Amount) })
                .Select(row => new Score { UserUuid = row.Uuid, PlayerName = row.Mcid,
                    Value = (long)(row.Total > long.MaxValue ? long.MaxValue : row.Total) });
        var goldRows = GoldCurrencyBalanceSupport.GoldAmounts(db)
            .Select(row => new AccountAmount { AccountId = row.AccountId, Amount = row.Amount });
        rankings.Add(await ReadAsync("gold", "gold", SumAmounts(goldRows), limit, cancellationToken));
        var playTimeAmounts = db.Database.IsSqlServer()
            ? db.Database.SqlQuery<AccountAmount>($"SELECT [uuid] AS [AccountId], CAST([play_time_seconds] AS decimal(38,0)) AS [Amount] FROM [dbo].[account]")
            : db.Accounts.Select(row => new AccountAmount { AccountId = row.Uuid, Amount = (decimal)row.PlayTimeSeconds });
        rankings.Add(await ReadAsync("play_time", "seconds", SumAmounts(playTimeAmounts), limit, cancellationToken));
        var dungeonRecords = db.AccountDungeonRecords.AsNoTracking().Where(record => !record.IsDeleted && record.ClearCount > 0);
        var dungeonAmounts = db.Database.IsSqlServer()
            ? db.Database.SqlQuery<AccountAmount>($"SELECT [account_id] AS [AccountId], CAST([clear_count] AS decimal(38,0)) AS [Amount] FROM [dbo].[account_dungeon_record] WHERE [is_deleted] = 0 AND [clear_count] > 0")
            : dungeonRecords.Select(record => new AccountAmount { AccountId = record.AccountId, Amount = (decimal)record.ClearCount });
        rankings.Add(await ReadAsync("dungeon_clears", "count", SumAmounts(dungeonAmounts), limit, cancellationToken));
        var mobRecords = db.AccountMobRecords.AsNoTracking().Where(record => !record.IsDeleted && record.DefeatCount > 0);
        var bossAmounts = db.Database.IsSqlServer()
            ? db.Database.SqlQuery<AccountAmount>($"SELECT [account_id] AS [AccountId], CAST([defeat_count] AS decimal(38,0)) AS [Amount] FROM [dbo].[account_mob_record] WHERE [is_deleted] = 0 AND [defeat_count] > 0 AND [mob_category] = 'BOSS'")
            : mobRecords.Where(record => record.MobCategory == "BOSS").Select(record => new AccountAmount { AccountId = record.AccountId, Amount = (decimal)record.DefeatCount });
        rankings.Add(await ReadAsync("boss_defeats", "count", SumAmounts(bossAmounts), limit, cancellationToken));
        var mobAmounts = db.Database.IsSqlServer()
            ? db.Database.SqlQuery<AccountAmount>($"SELECT [account_id] AS [AccountId], CAST([defeat_count] AS decimal(38,0)) AS [Amount] FROM [dbo].[account_mob_record] WHERE [is_deleted] = 0 AND [defeat_count] > 0 AND [mob_category] IN ('ENEMY', 'BOSS')")
            : mobRecords.Where(record => record.MobCategory == "BOSS" || record.MobCategory == "ENEMY").Select(record => new AccountAmount { AccountId = record.AccountId, Amount = (decimal)record.DefeatCount });
        rankings.Add(await ReadAsync("mob_defeats", "count", SumAmounts(mobAmounts), limit, cancellationToken));
        var dungeonRows = accounts.Join(dungeonRecords, row => row.Account.Uuid, record => record.AccountId,
            (row, record) => new { row.User.Uuid, row.User.Mcid, record.DungeonId });
        var dungeonTypes = dungeonRows.GroupBy(row => new { row.Uuid, row.Mcid })
            .Select(group => new Score { UserUuid = group.Key.Uuid, PlayerName = group.Key.Mcid, Value = group.Select(row => row.DungeonId).Distinct().LongCount() });
        rankings.Add(await ReadAsync("dungeon_types", "count", dungeonTypes, limit, cancellationToken));
        var quests = accounts.Join(db.AccountQuestStates.AsNoTracking().Where(state => !state.IsDeleted), row => row.Account.Uuid, state => state.AccountId, (row, state) => new { row.User, state.AccountQuestStateId })
            .Join(db.AccountQuestCompletions.AsNoTracking(), row => row.AccountQuestStateId, completion => completion.AccountQuestStateId, (row, completion) => new { row.User.Uuid, row.User.Mcid, completion.QuestId })
            .GroupBy(row => new { row.Uuid, row.Mcid })
            .Select(group => new Score { UserUuid = group.Key.Uuid, PlayerName = group.Key.Mcid, Value = group.Select(row => row.QuestId).Distinct().LongCount() });
        rankings.Add(await ReadAsync("quest_types", "count", quests, limit, cancellationToken));
        return new(clock.GetUtcNow(), rankings);
    }

    private static async Task<ServerRankingResponse> ReadAsync(string type, string unit, IQueryable<Score> query, int limit, CancellationToken cancellationToken)
    {
        var rows = await query.Where(row => row.Value > 0).OrderByDescending(row => row.Value)
            .ThenBy(row => row.PlayerName).ThenBy(row => row.UserUuid).Take(limit).ToListAsync(cancellationToken);
        return new(type, unit, true, rows.Select((row, index) => new ServerRankingEntryResponse(index + 1, row.UserUuid, row.PlayerName, row.Value)).ToArray());
    }

    private sealed class AccountAmount
    {
        public Guid AccountId { get; init; }
        public decimal Amount { get; init; }
    }

    private sealed class Score
    {
        public Guid UserUuid { get; init; }
        public string PlayerName { get; init; } = string.Empty;
        public long Value { get; init; }
    }
}
