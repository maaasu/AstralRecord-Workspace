using System.Data;
using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

/// <summary>出品枠集計をSQL Serverで実行し、検索ヒントの互換性と範囲ロックを検証する。</summary>
[Trait("Category", "SqlServerIntegration")]
public sealed class MarketListingSlotsSqlServerTests
{
    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task UsedListingSlots_CountsEligibleRowsWithAndWithoutSerializableTransaction(
        bool seedListings, bool serializable)
    {
        if (!Enabled()) return;
        await using var database = await TemporaryDatabase.CreateAsync();
        var seller = Guid.NewGuid();
        await using var context = database.Context();
        if (seedListings)
        {
            await context.Database.ExecuteSqlInterpolatedAsync($"""
                INSERT INTO dbo.market_listing (seller_account_id, status, status_reason, is_deleted)
                VALUES
                    ({seller}, 'ACTIVE', NULL, 0),
                    ({seller}, 'SUSPENDED', NULL, 0),
                    ({seller}, 'SOLD', NULL, 0),
                    ({seller}, 'SOLD', 'PARTIAL_SALE', 0),
                    ({seller}, 'SOLD', 'EXPIRED_AFTER_PARTIAL_SALE', 0),
                    ({seller}, 'EXPIRED', NULL, 0),
                    ({seller}, 'CANCELED', NULL, 0),
                    ({seller}, 'ACTIVE', NULL, 1),
                    ({seller}, 'SUSPENDED', NULL, 1),
                    ({seller}, 'SOLD', NULL, 1),
                    ({Guid.NewGuid()}, 'ACTIVE', NULL, 0)
                """);
        }

        await using var transaction = serializable
            ? await context.Database.BeginTransactionAsync(IsolationLevel.Serializable)
            : null;

        var count = await CountAsync(context, seller);

        Assert.Equal(seedListings ? 4 : 0, count);
        if (transaction is not null) await transaction.CommitAsync();
    }

    [Fact]
    public async Task UsedListingSlots_HoldsEmptySellerRangeUntilTransactionCompletes()
    {
        if (!Enabled()) return;
        await using var database = await TemporaryDatabase.CreateAsync();
        var seller = Guid.NewGuid();
        await using var first = database.Context();
        await using var second = database.Context();
        await using var transaction = await first.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        await second.Database.OpenConnectionAsync();
        var ownerSession = await SessionIdAsync(first);
        var waitingSession = await SessionIdAsync(second);

        Assert.Equal(0, await CountAsync(first, seller));
        var insert = second.Database.ExecuteSqlInterpolatedAsync($"""
            INSERT INTO dbo.market_listing (seller_account_id, status, status_reason, is_deleted)
            VALUES ({seller}, 'ACTIVE', NULL, 0)
            """);
        try
        {
            await database.AssertLockWaitAsync(waitingSession, ownerSession, insert);
        }
        finally
        {
            await transaction.RollbackAsync();
            await insert.WaitAsync(TimeSpan.FromSeconds(10));
        }

        Assert.Equal(1, await CountAsync(first, seller));
    }

    private static bool Enabled() =>
        Environment.GetEnvironmentVariable("ASTRALRECORD_RUN_SQLSERVER_INTEGRATION") == "1";

    private static Task<int> CountAsync(AstralRecordDbContext context, Guid seller) =>
        context.Database.SqlQuery<int>(MarketRepository.BuildUsedListingSlotsForSerializableQuery(seller))
            .SingleAsync();

    private static Task<int> SessionIdAsync(AstralRecordDbContext context) =>
        context.Database.SqlQueryRaw<int>("SELECT CAST(@@SPID AS int) AS [Value]").SingleAsync();

    /// <summary>本番設定を使用せず、localhostのランダムな専用DBだけを作成・破棄する。</summary>
    private sealed class TemporaryDatabase(string name) : IAsyncDisposable
    {
        private const string Prefix = "AstralRecordMarketSlots_";

        private static string ConnectionString(string database) => new SqlConnectionStringBuilder
        {
            DataSource = @"localhost\SQLEXPRESS", InitialCatalog = database,
            IntegratedSecurity = true, TrustServerCertificate = true, ConnectTimeout = 10,
            Pooling = false,
        }.ConnectionString;

        internal AstralRecordDbContext Context() => new(new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlServer(ConnectionString(name), options => options.CommandTimeout(20)).Options);

        internal static async Task<TemporaryDatabase> CreateAsync()
        {
            var databaseName = Prefix + Guid.NewGuid().ToString("N");
            var database = new TemporaryDatabase(databaseName);
            await ExecuteAsync("master", $"CREATE DATABASE [{databaseName}]");
            try
            {
                await ExecuteAsync(databaseName, """
                    CREATE TABLE dbo.market_listing (
                        listing_id uniqueidentifier NOT NULL DEFAULT NEWID() PRIMARY KEY,
                        seller_account_id uniqueidentifier NOT NULL,
                        status nvarchar(20) NOT NULL,
                        status_reason nvarchar(200) NULL,
                        is_deleted bit NOT NULL);
                    CREATE INDEX IX_market_listing_seller_status
                        ON dbo.market_listing (seller_account_id, status);
                    """);
                return database;
            }
            catch
            {
                await database.DisposeAsync();
                throw;
            }
        }

        /// <summary>処理開始の遅延ではなく、集計transactionが挿入を実際にブロックすることを確認する。</summary>
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
                Assert.False(operation.IsCompleted, "Insert completed before the counting transaction released its range lock.");
                if (await command.ExecuteScalarAsync() is int blocker && blocker == owningSession) return;
                await Task.Delay(20);
            }
            Assert.Fail("SQL Server did not report the expected range lock wait.");
        }

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
    }
}
