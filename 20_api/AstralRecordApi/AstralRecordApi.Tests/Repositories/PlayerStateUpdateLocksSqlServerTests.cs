using System.Data;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Repositories;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

/// <summary>本番と同じSQL Serverで更新前ロック・空範囲・複数個体の排他を検証する。</summary>
[Trait("Category", "SqlServerIntegration")]
public class PlayerStateUpdateLocksSqlServerTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task EquipmentReadForUpdateBlocksSecondReaderUntilCommit(bool checkMarket)
    {
        if (!Enabled()) return;
        await using var database = await TemporaryDatabase.CreateAsync();
        var ids = new[] { Guid.NewGuid(), Guid.NewGuid() };
        await using (var seed = database.Context())
        {
            foreach (var id in ids) seed.EquipmentInstances.Add(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = id, AccountId = Guid.NewGuid(), ItemId = "fixture",
                DurabilityMax = 100, DurabilityValue = 100,
                CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow,
            });
            await seed.SaveChangesAsync();
        }
        await using var first = database.Context();
        await using var second = database.Context();
        await using var firstTransaction = await first.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var firstSession = await SessionIdAsync(first);
        var captured = await PlayerStateUpdateLocks.EquipmentAsync(first, ids.Reverse(), checkMarket ? ids.Reverse() : []);
        Assert.NotNull(captured);
        await using var secondTransaction = await second.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var secondSession = await SessionIdAsync(second);
        var waiting = PlayerStateUpdateLocks.EquipmentAsync(second, ids, checkMarket ? ids : []);
        await database.AssertLockWaitAsync(secondSession, firstSession, waiting);
        captured[ids[0]].DurabilityValue = 90;
        await first.SaveChangesAsync();
        await firstTransaction.CommitAsync();
        var resumed = await waiting.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.NotNull(resumed);
        Assert.Equal(90, resumed[ids[0]].DurabilityValue);
        resumed[ids[1]].DurabilityValue = 80;
        await second.SaveChangesAsync();
        await secondTransaction.CommitAsync();
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task AdventureRecordMissingRangeSerializesInsertAndPreservesCounters(bool waitOnDungeon)
    {
        if (!Enabled()) return;
        await using var database = await TemporaryDatabase.CreateAsync();
        var accountId = Guid.NewGuid();
        await using var first = database.Context();
        await using var second = database.Context();
        await using var firstTransaction = await first.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var firstSession = await SessionIdAsync(first);
        Assert.Empty(await PlayerStateUpdateLocks.MobsAsync(first, accountId, ["mob"]));
        Assert.Empty(await PlayerStateUpdateLocks.DungeonsAsync(first, accountId, ["dungeon"]));
        await using var secondTransaction = await second.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var secondSession = await SessionIdAsync(second);
        Task waiting = waitOnDungeon
            ? PlayerStateUpdateLocks.DungeonsAsync(second, accountId, ["dungeon"])
            : PlayerStateUpdateLocks.MobsAsync(second, accountId, ["mob"]);
        await database.AssertLockWaitAsync(secondSession, firstSession, waiting);
        first.AccountMobRecords.Add(new AccountMobRecordEntity
        {
            AccountMobRecordId = Guid.NewGuid(), AccountId = accountId, MobId = "mob", MobCategory = "ENEMY",
            DefeatCount = 2, FirstDefeatedAt = DateTime.UtcNow, LastDefeatedAt = DateTime.UtcNow,
            CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow,
        });
        first.AccountDungeonRecords.Add(new AccountDungeonRecordEntity
        {
            AccountDungeonRecordId = Guid.NewGuid(), AccountId = accountId, DungeonId = "dungeon",
            ClearCount = 3, FirstClearedAt = DateTime.UtcNow, LastClearedAt = DateTime.UtcNow,
            CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow,
        });
        await first.SaveChangesAsync();
        await firstTransaction.CommitAsync();
        await waiting.WaitAsync(TimeSpan.FromSeconds(10));
        var mobs = await PlayerStateUpdateLocks.MobsAsync(second, accountId, ["mob"]);
        Assert.Equal(2, Assert.Single(mobs).DefeatCount);
        var dungeons = await PlayerStateUpdateLocks.DungeonsAsync(second, accountId, ["dungeon"]);
        Assert.Equal(3, Assert.Single(dungeons).ClearCount);
        mobs[0].DefeatCount += 5;
        dungeons[0].ClearCount += 7;
        await second.SaveChangesAsync();
        await secondTransaction.CommitAsync();
        await using var verify = database.Context();
        Assert.Equal(7, (await verify.AccountMobRecords.SingleAsync()).DefeatCount);
        Assert.Equal(10, (await verify.AccountDungeonRecords.SingleAsync()).ClearCount);
    }

    [Fact]
    public async Task ListedEquipmentIsRejectedBeforeEquipmentUpdateLock()
    {
        if (!Enabled()) return;
        await using var database = await TemporaryDatabase.CreateAsync();
        var id = Guid.NewGuid();
        await using var context = database.Context();
        await context.Database.ExecuteSqlInterpolatedAsync($"""
            INSERT INTO dbo.market_listing(instance_type,instance_id,is_deleted,status)
            VALUES ('EQUIPMENT',{id},0,'ACTIVE')
            """);
        await using var transaction = await context.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        Assert.Null(await PlayerStateUpdateLocks.EquipmentAsync(context, [id], [id]));
    }

    private static bool Enabled() => Environment.GetEnvironmentVariable("ASTRALRECORD_RUN_SQLSERVER_INTEGRATION") == "1";

    private static Task<int> SessionIdAsync(AstralRecordDbContext context) =>
        context.Database.SqlQueryRaw<int>("SELECT CAST(@@SPID AS int) AS [Value]").SingleAsync();

    /// <summary>localhostのランダムな専用DBだけを作成・破棄する。</summary>
    private sealed class TemporaryDatabase(string name) : IAsyncDisposable
    {
        private const string Prefix = "AstralRecordStateLocks_";
        private static string ConnectionString(string database) => new SqlConnectionStringBuilder
        {
            DataSource = @"localhost\SQLEXPRESS", InitialCatalog = database,
            IntegratedSecurity = true, TrustServerCertificate = true, ConnectTimeout = 10,
            Pooling = false,
        }.ConnectionString;

        internal AstralRecordDbContext Context() => new(new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlServer(ConnectionString(name), options => options.CommandTimeout(15)).Options);

        /// <summary>処理開始遅延ではなく、指定session同士のSQLロック待機が発生したことを確認する。</summary>
        internal async Task AssertLockWaitAsync(int waitingSession, int owningSession, Task operation)
        {
            await using var connection = new SqlConnection(ConnectionString(name));
            await connection.OpenAsync();
            await using var command = new SqlCommand("""
                SELECT CAST(blocking_session_id AS int) FROM sys.dm_exec_requests
                WHERE session_id = @session AND wait_type LIKE 'LCK_M_%'
                """, connection) { CommandTimeout = 5 };
            command.Parameters.AddWithValue("@session", waitingSession);
            var deadline = DateTime.UtcNow.AddSeconds(10);
            while (DateTime.UtcNow < deadline)
            {
                Assert.False(operation.IsCompleted, "Second reader completed without waiting for the first transaction.");
                var blocker = await command.ExecuteScalarAsync();
                if (blocker is int session && session == owningSession) return;
                await Task.Delay(20);
            }
            Assert.Fail("SQL Server did not report the expected lock wait before the deadline.");
        }

        internal static async Task<TemporaryDatabase> CreateAsync()
        {
            var database = new TemporaryDatabase(Prefix + Guid.NewGuid().ToString("N"));
            await ExecuteAsync("master", $"CREATE DATABASE [{database.nameValue}]");
            try
            {
                await ExecuteAsync(database.nameValue, Schema);
                return database;
            }
            catch
            {
                await database.DisposeAsync();
                throw;
            }
        }

        private string nameValue => name;

        public async ValueTask DisposeAsync()
        {
            if (!name.StartsWith(Prefix, StringComparison.Ordinal)
                || !Guid.TryParseExact(name[Prefix.Length..], "N", out _))
                throw new InvalidOperationException("Only the generated temporary database may be dropped.");
            await ExecuteAsync("master", $"ALTER DATABASE [{name}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [{name}]");
        }

        private static async Task ExecuteAsync(string database, string sql)
        {
            await using var connection = new SqlConnection(ConnectionString(database));
            await connection.OpenAsync();
            await using var command = new SqlCommand(sql, connection) { CommandTimeout = 30 };
            await command.ExecuteNonQueryAsync();
        }

        private const string Schema = """
            CREATE TABLE dbo.market_listing (
                instance_type nvarchar(30) NULL, instance_id uniqueidentifier NULL,
                is_deleted bit NOT NULL, status nvarchar(20) NOT NULL);
            CREATE INDEX IX_market_listing_instance_active_status
                ON dbo.market_listing(instance_type,instance_id,is_deleted,status);
            CREATE TABLE dbo.equipment_instance (
                equipment_instance_id uniqueidentifier NOT NULL CONSTRAINT PK_equipment_instance PRIMARY KEY,
                account_id uniqueidentifier NOT NULL, item_id nvarchar(100) NOT NULL,
                enhance_level int NOT NULL, rune_max_slots int NOT NULL, transcendence_rank int NOT NULL,
                durability_max int NULL, durability_value int NULL,
                created_at datetime2 NOT NULL, updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL, updated_by uniqueidentifier NOT NULL, is_deleted bit NOT NULL);
            CREATE TABLE dbo.account_mob_record (
                account_mob_record_id uniqueidentifier NOT NULL CONSTRAINT PK_account_mob_record PRIMARY KEY,
                account_id uniqueidentifier NOT NULL, mob_id nvarchar(100) NOT NULL, mob_category nvarchar(20) NOT NULL,
                defeat_count bigint NOT NULL, first_defeated_at datetime2 NOT NULL, last_defeated_at datetime2 NOT NULL,
                created_at datetime2 NOT NULL, updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL, updated_by uniqueidentifier NOT NULL, is_deleted bit NOT NULL,
                CONSTRAINT UX_account_mob_record_account_mob UNIQUE(account_id,mob_id));
            CREATE TABLE dbo.account_dungeon_record (
                account_dungeon_record_id uniqueidentifier NOT NULL CONSTRAINT PK_account_dungeon_record PRIMARY KEY,
                account_id uniqueidentifier NOT NULL, dungeon_id nvarchar(100) NOT NULL,
                clear_count bigint NOT NULL, first_cleared_at datetime2 NOT NULL, last_cleared_at datetime2 NOT NULL,
                created_at datetime2 NOT NULL, updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL, updated_by uniqueidentifier NOT NULL, is_deleted bit NOT NULL,
                CONSTRAINT UX_account_dungeon_record_account_dungeon UNIQUE(account_id,dungeon_id));
            """;
    }
}
