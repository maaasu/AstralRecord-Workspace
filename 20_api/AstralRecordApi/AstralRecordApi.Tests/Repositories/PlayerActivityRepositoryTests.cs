using AstralRecordApi.Data;
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
        var master = new SqlConnectionStringBuilder { DataSource = @"localhost\SQLEXPRESS", InitialCatalog = "master", IntegratedSecurity = true, TrustServerCertificate = true }.ConnectionString;
        var target = new SqlConnectionStringBuilder(master) { InitialCatalog = name }.ConnectionString;
        await using var masterConnection = new SqlConnection(master); await masterConnection.OpenAsync();
        await using (var create = new SqlCommand($"CREATE DATABASE [{name}]", masterConnection)) await create.ExecuteNonQueryAsync();
        try
        {
            var options = new DbContextOptionsBuilder<HistoryDbContext>().UseSqlServer(target).Options;
            await using (var setup = new HistoryDbContext(options)) await setup.Database.EnsureCreatedAsync();
            var now = DateTime.UtcNow; var first = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "First", AccountName = "First" }; var second = new ActivityPlayerSnapshotRequest { UserUuid = Guid.NewGuid(), AccountId = Guid.NewGuid(), Mcid = "Second", AccountName = "Second" };
            await using var db = new HistoryDbContext(options); var repository = new PlayerActivityRepository(db, TimeProvider.System);
            await repository.RecordBatchAsync(new PlayerActivityBatchRequest { BatchId = Guid.NewGuid(), IpObservations = [new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.7", Player = first }, new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.7", Player = second }], Trades = [new() { EventId = Guid.NewGuid(), CompletedAt = now, Source = first, Destination = second }], DungeonClears = [new() { EventId = Guid.NewGuid(), DungeonId = "cave", DungeonName = "Cave", StartedAt = now.AddMinutes(-1), ClearedAt = now, Participants = [new() { Player = first, DistanceMeters = 10, MovementSampleCount = 2 }, new() { Player = second, DistanceMeters = 0, MovementSampleCount = 1 }] }], MobDamageSummaries = [new() { EventId = Guid.NewGuid(), MobId = "slime", MobName = "Slime", WindowStartedAt = now.AddMinutes(-1), WindowEndedAt = now, Victim = first, Damage = 5, HitCount = 1 }], MobPlayerDeaths = [new() { EventId = Guid.NewGuid(), OccurredAt = now, MobId = "slime", MobName = "Slime", Victim = first }] });
            var query = new PlayerActivityQuery { From = now.AddMinutes(-2), To = now.AddMinutes(1) };
            Assert.Single((await repository.GetSameIpAsync(query)).Items); Assert.Single((await repository.GetDungeonsAsync(query)).Items); Assert.Equal(2, (await repository.GetDungeonPlayersAsync(query)).Items.Count); Assert.Single((await repository.GetMobsAsync(query)).Items); Assert.Single((await repository.GetMobPlayersAsync("slime", query)).Items);
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
            IpObservations = [new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.1", Player = first }, new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.1", Player = new ActivityPlayerSnapshotRequest { UserUuid = first.UserUuid, AccountId = Guid.NewGuid(), Mcid = "First", AccountName = "AnotherAccount" } }, new() { EventId = Guid.NewGuid(), ObservedAt = now, GlobalIp = "203.0.113.1", Player = second }],
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
}
