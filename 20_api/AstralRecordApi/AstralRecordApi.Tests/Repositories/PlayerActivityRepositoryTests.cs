using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class PlayerActivityRepositoryTests
{
    [Trait("Category", "SqlServerIntegration")]
    [Fact]
    public async Task SqlServerAggregates_SameIpDungeonAndMobQueriesExecute()
    {
        if (!string.Equals(Environment.GetEnvironmentVariable("ASTRALRECORD_RUN_SQLSERVER_INTEGRATION"), "1", StringComparison.Ordinal)) return;
        var name = "HistoryActivityIntegration_" + Guid.NewGuid().ToString("N");
        var master = new SqlConnectionStringBuilder { DataSource = Environment.GetEnvironmentVariable("ASTRALRECORD_SQLSERVER_TEST_SOURCE") ?? @"localhost\SQLEXPRESS", InitialCatalog = "master", IntegratedSecurity = true, TrustServerCertificate = true }.ConnectionString;
        var target = new SqlConnectionStringBuilder(master) { InitialCatalog = name }.ConnectionString;
        await using var masterConnection = new SqlConnection(master); await masterConnection.OpenAsync();
        await using (var create = new SqlCommand($"CREATE DATABASE [{name}]", masterConnection)) await create.ExecuteNonQueryAsync();
        try
        {
            var options = new DbContextOptionsBuilder<HistoryDbContext>().UseSqlServer(target, sqlServerOptions => sqlServerOptions.EnableRetryOnFailure()).Options;
            await using (var setup = new HistoryDbContext(options)) await setup.Database.EnsureCreatedAsync();
            var now = DateTime.UtcNow; var first = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "First", AccountName = "First" }; var second = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "Second", AccountName = "Second" };
            await using var db = new HistoryDbContext(options); var repository = new PlayerActivityRepository(db, TimeProvider.System);
            await repository.RecordBatchAsync(new PlayerActivityBatchRequest { BatchId = Guid.NewGuid(), IpObservations = [new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.7", Player = first }, new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.7", Player = second }], Trades = [new() { EventId = Guid.NewGuid(), CompletedAt = now, Source = first, Destination = second }], DungeonClears = [new() { EventId = Guid.NewGuid(), DungeonId = "cave", DungeonName = "Cave", StartedAt = now.AddMinutes(-1), ClearedAt = now, Participants = [new() { Player = first, DistanceMeters = 10, MovementSampleCount = 2 }, new() { Player = second, DistanceMeters = 0, MovementSampleCount = 1 }] }], BossClears = [new() { EventId = Guid.NewGuid(), BossId = "dragon", BossName = "Dragon", StartedAt = now.AddSeconds(-1.125), ClearedAt = now, Participants = [new() { Player = first, DamageDealt = 5.50049m, DeathCount = 1 }] }], MobDamageSummaries = [new() { EventId = Guid.NewGuid(), MobId = "slime", MobName = "Slime", WindowStartedAt = now.AddMinutes(-1), WindowEndedAt = now, Victim = first, Damage = 5, HitCount = 1 }], MobPlayerDeaths = [new() { EventId = Guid.NewGuid(), OccurredAt = now, MobId = "slime", MobName = "Slime", Victim = first }] });
            var query = new PlayerActivityQuery { From = now.AddMinutes(-2), To = now.AddMinutes(1) };
            Assert.Single((await repository.GetSameIpAsync(query)).Items); Assert.Single((await repository.GetDungeonsAsync(query)).Items); Assert.Equal(2, (await repository.GetDungeonPlayersAsync(query)).Items.Count); Assert.Single((await repository.GetMobsAsync(query)).Items); Assert.Single((await repository.GetMobPlayersAsync("slime", query)).Items);
            Assert.Equal(1.125, Assert.Single((await repository.GetBossesAsync(query)).Items).DurationSeconds, 3);
            Assert.Equal(5.5m, Assert.Single((await repository.GetBossPlayersAsync(query)).Items).TotalDamageDealt);
        }
        finally { await using var drop = new SqlCommand($"ALTER DATABASE [{name}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [{name}]", masterConnection); await drop.ExecuteNonQueryAsync(); }
    }

    [Fact]
    public async Task RecordBatch_RejectsMoreThanOneThousandEvents()
    {
        await using var db = new HistoryDbContext(new DbContextOptions<HistoryDbContext>());
        var request = new PlayerActivityBatchRequest { BatchId = Guid.NewGuid(), MobPlayerDeaths = Enumerable.Range(0, 1001).Select(_ => new MobPlayerDeathRequest { EventId = Guid.NewGuid(), MobId = "mob", Victim = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid() } }).ToList() };
        await Assert.ThrowsAsync<ArgumentException>(() => new PlayerActivityRepository(db, TimeProvider.System).RecordBatchAsync(request));
    }

    /// <summary>同一バッチの再送は同じ受理結果となり、同IPの別ユーザーペアと移転回数を重複させません。</summary>
    [Fact]
    public async Task RecordBatch_ReplayKeepsSameIpPairAndTradeCountIdempotent()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<HistoryDbContext>().UseSqlite(connection).Options;
        await using (var setup = new HistoryDbContext(options)) await setup.Database.EnsureCreatedAsync();
        var now = DateTime.UtcNow;
        var first = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "First", AccountName = "FirstAccount" };
        var second = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "Second", AccountName = "SecondAccount" };
        var request = new PlayerActivityBatchRequest
        {
            BatchId = Guid.NewGuid(),
            IpObservations = [new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.1", Player = first }, new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.1", Player = new ActivityPlayerSnapshotRequest { UserUuid = first.UserUuid, AccountId = Guid.NewGuid(), Mcid = "First", AccountName = "AnotherAccount" } }, new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.1", Player = second }, new() { EventId = Guid.NewGuid(), ObservedAt = now.AddSeconds(1), GlobalIp = "203.0.113.2", Player = first }, new() { EventId = Guid.NewGuid(), ObservedAt = now.AddSeconds(1), GlobalIp = "203.0.113.2", Player = second }],
            Trades = [new() { EventId = Guid.NewGuid(), CompletedAt = now, Source = first, Destination = second, Gold = 12, Items = [new() { ItemId = "iron", ItemName = "Iron", Quantity = 2 }] }],
            MobDamageSummaries = [new() { EventId = Guid.NewGuid(), MobId = "slime", MobName = "Slime", WindowStartedAt = now.AddMinutes(-1), WindowEndedAt = now, Victim = first, Damage = 9, HitCount = 2 }],
            MobPlayerDeaths = [new() { EventId = Guid.NewGuid(), OccurredAt = now, MobId = "slime", MobName = "Slime", Victim = first }],
        };
        await using (var db = new HistoryDbContext(options))
        {
            var repository = new PlayerActivityRepository(db, TimeProvider.System);
            Assert.False((await repository.RecordBatchAsync(request)).Replayed);
        }
        await using (var db = new HistoryDbContext(options))
        {
            var repository = new PlayerActivityRepository(db, TimeProvider.System);
            Assert.True((await repository.RecordBatchAsync(request)).Replayed);
            var alternateBatch = new PlayerActivityBatchRequest { BatchId = Guid.NewGuid(), IpObservations = request.IpObservations, Trades = request.Trades, MobDamageSummaries = request.MobDamageSummaries, MobPlayerDeaths = request.MobPlayerDeaths };
            Assert.Equal(0, (await repository.RecordBatchAsync(alternateBatch)).AcceptedEventCount);
            var result = await repository.GetSameIpAsync(new PlayerActivityQuery { From = now.AddMinutes(-1), To = now.AddMinutes(1) });
            var pair = Assert.Single(result.Items);
            Assert.Equal(1, result.TotalCount);
            Assert.Equal(1, pair.TradeCount);
            Assert.Equal(2, pair.Players.Count);
            var mobs = await repository.GetMobsAsync(new PlayerActivityQuery { From = now.AddMinutes(-1), To = now.AddMinutes(1) });
            var slime = Assert.Single(mobs.Items);
            Assert.Equal(1, slime.PlayerKillCount);
            Assert.Equal(9m, slime.DamageToPlayers);
        }
    }

    /// <summary>移動距離未観測は0mに丸めず、ダンジョン一覧応答でnullとして返します。</summary>
    [Fact]
    public async Task DungeonList_PreservesUnobservedMovementAsNull()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<HistoryDbContext>().UseSqlite(connection).Options;
        await using (var setup = new HistoryDbContext(options)) await setup.Database.EnsureCreatedAsync();
        var now = DateTime.UtcNow; var player = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "Player", AccountName = "Account" };
        await using var db = new HistoryDbContext(options); var repository = new PlayerActivityRepository(db, TimeProvider.System);
        await repository.RecordBatchAsync(new PlayerActivityBatchRequest { BatchId = Guid.NewGuid(), DungeonClears = [new() { EventId = Guid.NewGuid(), DungeonId = "cave", DungeonName = "Cave", StartedAt = now.AddMinutes(-3), ClearedAt = now, Participants = [new() { Player = player, DistanceMeters = null, MovementSampleCount = 0 }] }] });
        var result = await repository.GetDungeonsAsync(new PlayerActivityQuery { From = now.AddMinutes(-5), To = now.AddMinutes(1) });
        Assert.Null(Assert.Single(Assert.Single(result.Items).Participants).DistanceMeters);
        var summaries = await repository.GetDungeonPlayersAsync(new PlayerActivityQuery { From = now.AddMinutes(-5), To = now.AddMinutes(1) });
        Assert.Null(Assert.Single(summaries.Items).TotalDistanceMeters);
        await Assert.ThrowsAsync<ArgumentException>(() => repository.GetDungeonsAsync(new PlayerActivityQuery { From = now.AddDays(-367), To = now }));
    }

    [Fact]
    public async Task DungeonPlayerSummary_GroupsRenamedSnapshotsByAccountAndReturnsAnActualLatestSnapshot()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<HistoryDbContext>().UseSqlite(connection).Options;
        await using (var setup = new HistoryDbContext(options)) await setup.Database.EnsureCreatedAsync();
        var now = DateTime.UtcNow; var userId = Guid.NewGuid(); var accountId = Guid.NewGuid();
        var former = new ActivityPlayerSnapshotRequest { UserUuid = userId, AccountId = accountId, Mcid = "FormerName", AccountName = "FormerAccount" };
        var current = new ActivityPlayerSnapshotRequest { UserUuid = userId, AccountId = accountId, Mcid = "CurrentName", AccountName = "CurrentAccount" };
        await using var db = new HistoryDbContext(options); var repository = new PlayerActivityRepository(db, TimeProvider.System);
        await repository.RecordBatchAsync(new PlayerActivityBatchRequest { BatchId = Guid.NewGuid(), DungeonClears = [new() { EventId = Guid.NewGuid(), DungeonId = "cave", DungeonName = "Cave", StartedAt = now.AddMinutes(-10), ClearedAt = now.AddMinutes(-9), Participants = [new() { Player = former, DistanceMeters = 2, MovementSampleCount = 1 }] }, new() { EventId = Guid.NewGuid(), DungeonId = "cave", DungeonName = "Cave", StartedAt = now.AddMinutes(-2), ClearedAt = now.AddMinutes(-1), Participants = [new() { Player = current, DistanceMeters = 3, MovementSampleCount = 1 }] }] });
        var summary = Assert.Single((await repository.GetDungeonPlayersAsync(new PlayerActivityQuery { From = now.AddMinutes(-20), To = now })).Items);
        Assert.Equal(2, summary.ClearCount); Assert.Equal(accountId, summary.Player.AccountId); Assert.Equal(userId, summary.Player.UserUuid); Assert.Equal("CurrentName", summary.Player.Mcid); Assert.Equal("CurrentAccount", summary.Player.AccountName);
    }

    [Fact]
    public async Task ClearRankings_AreSortedBeforePagingAndKeepMillisecondPrecision()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<HistoryDbContext>().UseSqlite(connection).Options;
        await using (var setup = new HistoryDbContext(options)) await setup.Database.EnsureCreatedAsync();
        var now = DateTime.UtcNow; var first = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "First", AccountName = "First" }; var second = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "Second", AccountName = "Second" };
        var quick = Guid.NewGuid(); var slow = Guid.NewGuid();
        await using var db = new HistoryDbContext(options); var repository = new PlayerActivityRepository(db, TimeProvider.System);
        var batch = new PlayerActivityBatchRequest { BatchId = Guid.NewGuid(), DungeonClears = [
            new() { EventId = slow, DungeonId = "cave", DungeonName = "Cave", StartedAt = now.AddSeconds(-10.25), ClearedAt = now, Participants = [new() { Player = second }] },
            new() { EventId = quick, DungeonId = "cave", DungeonName = "Cave", StartedAt = now.AddSeconds(-1.125), ClearedAt = now.AddSeconds(-1), Participants = [new() { Player = first }] },
        ], BossClears = [
            new() { EventId = Guid.NewGuid(), BossId = "dragon", BossName = "Dragon", StartedAt = now.AddSeconds(-10.25), ClearedAt = now, Participants = [new() { Player = second, DamageDealt = 12.5m, DeathCount = 1 }] },
            new() { EventId = Guid.NewGuid(), BossId = "dragon", BossName = "Dragon", StartedAt = now.AddSeconds(-1.125), ClearedAt = now.AddSeconds(-1), Participants = [new() { Player = first, DamageDealt = 7.25049m }] },
        ] };
        Assert.Equal(4, (await repository.RecordBatchAsync(batch)).AcceptedEventCount);
        Assert.Equal(0, (await repository.RecordBatchAsync(new PlayerActivityBatchRequest { BatchId = Guid.NewGuid(), BossClears = batch.BossClears })).AcceptedEventCount);
        var q = new PlayerActivityQuery { From = now.AddMinutes(-1), To = now.AddMinutes(1), DungeonId = "cave", BossId = "dragon", Sort = "fastest", PageSize = 1 };
        var dungeon = await repository.GetDungeonsAsync(q); Assert.Equal(2, dungeon.TotalCount); Assert.Equal(quick, Assert.Single(dungeon.Items).EventId); Assert.Equal(0.125, dungeon.Items[0].DurationSeconds, 3);
        var boss = await repository.GetBossesAsync(q); Assert.Equal(2, boss.TotalCount); Assert.Equal(0.125, Assert.Single(boss.Items).DurationSeconds, 3);
        var bossPlayers = await repository.GetBossPlayersAsync(q); Assert.Equal(2, bossPlayers.TotalCount); Assert.Equal(first.AccountId, Assert.Single(bossPlayers.Items).Player.AccountId); Assert.Equal(7.25m, bossPlayers.Items[0].TotalDamageDealt);
        var dungeonPlayers = await repository.GetDungeonPlayersAsync(q); Assert.Equal(2, dungeonPlayers.TotalCount); Assert.Equal(first.AccountId, Assert.Single(dungeonPlayers.Items).Player.AccountId);
        Assert.Empty((await repository.GetBossesAsync(new PlayerActivityQuery { From = q.From, To = q.To, BossId = "other" })).Items);
        await Assert.ThrowsAsync<ArgumentException>(() => repository.GetBossesAsync(new PlayerActivityQuery { Sort = "invalid" }));
    }

    [Fact]
    public async Task UserEvents_SearchesStoredHistoryWithoutReturningPayload()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<HistoryDbContext>().UseSqlite(connection).Options;
        await using var db = new HistoryDbContext(options); await db.Database.EnsureCreatedAsync();
        var now = DateTime.UtcNow; var user = Guid.NewGuid();
        db.UserHistories.Add(new UserHistoryEntity { UserUuid = user, EventTime = now, EventType = "PLAYER_LOGIN", Source = "PLUGIN", Message = "Player login: Alice", PayloadJson = "{\"private\":true}" });
        db.UserHistories.Add(new UserHistoryEntity { UserUuid = Guid.NewGuid(), EventTime = now, EventType = "PARTY_JOINED", Source = "PLUGIN", Message = "Party joined", PayloadJson = "{}" });
        var accountId = Guid.NewGuid();
        db.PlayerIpObservations.Add(new PlayerIpObservationEntity { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "127.0.0.1", UserUuid = user, AccountId = accountId, Mcid = "Alice", AccountName = "AliceMain" });
        await db.SaveChangesAsync();
        var repository = new PlayerActivityRepository(db, TimeProvider.System);
        var result = await repository.GetEventsAsync(new PlayerActivityQuery { From = now.AddMinutes(-1), To = now.AddMinutes(1), UserUuid = user, EventType = "PLAYER_LOGIN", Query = "login" });
        var item = Assert.Single(result.Items); Assert.Equal(1, result.TotalCount); Assert.Equal("Player login: Alice", item.Message);
        Assert.Equal(accountId, item.Player?.AccountId);
        Assert.Equal("AliceMain", item.Player?.AccountName);
        Assert.DoesNotContain("Payload", System.Text.Json.JsonSerializer.Serialize(item));
    }
}
