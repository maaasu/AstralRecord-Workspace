using System.Data;
using System.Data.Common;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

/// <summary>本番XELで確認したsession読取・operation更新・session更新の競合を再試行なしで検証します。</summary>
[Trait("Category", "SqlServerIntegration")]
public sealed class SkillTreeRuntimeDeadlockSqlServerTests
{
    [Fact]
    public async Task ConcurrentViewsForDifferentAccountsCompleteWithoutDeadlockRetry()
    {
        if (!Enabled()) return;
        await using var database = await TemporaryDatabase.CreateAsync();
        await using var seed = database.Context();
        await using var first = await SkillTreeOperationRepositoryTests.Fixture.SeedAsync(seed, server: "first");
        await using var secondSeed = database.Context();
        await using var second = await SkillTreeOperationRepositoryTests.Fixture.SeedAsync(secondSeed, server: "second");
        var gate = new ViewUpdateBarrier();
        await using var left = database.Context(gate);
        await using var right = database.Context(gate);

        // 両方がsessionを読んでからoperation更新へ進む、本番の循環待ち条件を揃える。
        var views = await Task.WhenAll(
            Repository(left).RegisterPlayerViewAsync(first.Server, first.Account, first.ViewRequest(2)),
            Repository(right).RegisterPlayerViewAsync(second.Server, second.Account, second.ViewRequest(2)))
            .WaitAsync(TimeSpan.FromSeconds(20));

        Assert.All(views, view => Assert.NotNull(view));
        await using var check = database.Context();
        Assert.All(await check.SkillTreeAccountSessions.ToListAsync(), session => Assert.Equal(2, session.ViewSequence));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task SessionReadsReleaseSharedLocksButRuntimeFenceSurvives(bool validateOwnership)
    {
        if (!Enabled()) return;
        await using var database = await TemporaryDatabase.CreateAsync();
        await using var seed = database.Context();
        await using var fixture = await SkillTreeOperationRepositoryTests.Fixture.SeedAsync(seed);
        await using var reader = database.Context();
        await using var transaction = await reader.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var repository = Repository(reader);
        if (validateOwnership)
            Assert.True(await repository.ValidateRuntimeStateSaveAsync(fixture.Account, fixture.Server, fixture.Boot,
                fixture.Generation, fixture.Session, fixture.Token));
        else
            Assert.True(await repository.RequiresRuntimeAuthorityAsync(fixture.Account));

        // account直列化は別の既存テストで検証する。ここではsession読取だけのロック寿命を検証する。
        // 旧Serializable SELECTなら共有ロックを保持し、このUPDATEは1222で失敗する。
        await database.ExecuteAsync("""
            SET LOCK_TIMEOUT 1500;
            UPDATE dbo.skilltree_account_session SET view_sequence = view_sequence + 1
            WHERE account_session_id = @session;
            """, new SqlParameter("@session", fixture.Session));

        if (validateOwnership)
        {
            // sessionだけを文単位へ限定しても、runtimeのboot変更をcommitまで止める既存fenceは維持する。
            var blocked = await Assert.ThrowsAsync<SqlException>(() => database.ExecuteAsync("""
                SET LOCK_TIMEOUT 1500;
                UPDATE dbo.skilltree_server_runtime SET server_session_id = NEWID() WHERE server_id = @server;
                """, new SqlParameter("@server", fixture.Server)));
            Assert.Equal(1222, blocked.Number);
        }
        await transaction.CommitAsync();
        await using var check = database.Context();
        Assert.Equal(2, (await check.SkillTreeAccountSessions.SingleAsync()).ViewSequence);
    }

    private static bool Enabled() => Environment.GetEnvironmentVariable("ASTRALRECORD_RUN_SQLSERVER_INTEGRATION") == "1";
    private static SkillTreeOperationRepository Repository(AstralRecordDbContext context) =>
        new(context, new NetworkRuntimeService(TimeProvider.System));

    private sealed class ViewUpdateBarrier : DbCommandInterceptor
    {
        private readonly TaskCompletionSource ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private int arrivals;

        public override async ValueTask<InterceptionResult<int>> NonQueryExecutingAsync(DbCommand command,
            CommandEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
        {
            if (command.CommandText.Contains("[target_server_id] <>", StringComparison.Ordinal))
            {
                if (Interlocked.Increment(ref arrivals) == 2) ready.TrySetResult();
                await ready.Task.WaitAsync(TimeSpan.FromSeconds(10), cancellationToken);
            }
            return result;
        }
    }

    private sealed class TemporaryDatabase(string name) : IAsyncDisposable
    {
        private const string Prefix = "AstralRecordSkillTreeDeadlock_";
        internal static async Task<TemporaryDatabase> CreateAsync()
        {
            var database = new TemporaryDatabase(Prefix + Guid.NewGuid().ToString("N"));
            await ExecuteOnAsync("master", $"CREATE DATABASE [{database.nameValue}]");
            try
            {
                await using var context = database.Context();
                await context.Database.EnsureCreatedAsync();
                return database;
            }
            catch { await database.DisposeAsync(); throw; }
        }

        private string nameValue => name;
        internal LimitedContext Context(params IInterceptor[] interceptors) => new(new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlServer(ConnectionString(name), options => options.CommandTimeout(15))
            .AddInterceptors(interceptors).Options);
        internal Task ExecuteAsync(string sql, params SqlParameter[] parameters) => ExecuteOnAsync(name, sql, parameters);
        private static string ConnectionString(string database) => new SqlConnectionStringBuilder
        {
            DataSource = @"localhost\SQLEXPRESS", InitialCatalog = database, IntegratedSecurity = true,
            TrustServerCertificate = true, Pooling = false, ConnectTimeout = 10,
        }.ConnectionString;
        private static async Task ExecuteOnAsync(string database, string sql, params SqlParameter[] parameters)
        {
            await using var connection = new SqlConnection(ConnectionString(database));
            await connection.OpenAsync();
            await using var command = new SqlCommand(sql, connection) { CommandTimeout = 15 };
            command.Parameters.AddRange(parameters);
            await command.ExecuteNonQueryAsync();
        }
        public async ValueTask DisposeAsync()
        {
            if (!name.StartsWith(Prefix, StringComparison.Ordinal) || !Guid.TryParseExact(name[Prefix.Length..], "N", out _))
                throw new InvalidOperationException("Only the generated temporary database may be dropped.");
            await ExecuteOnAsync("master", $"ALTER DATABASE [{name}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [{name}]");
        }
    }

    private sealed class LimitedContext(DbContextOptions<AstralRecordDbContext> options) : AstralRecordDbContext(options)
    {
        protected override void OnModelCreating(ModelBuilder builder)
        {
            base.OnModelCreating(builder);
            var allowed = new HashSet<Type>
            {
                typeof(AccountEntity), typeof(AccountSkillTreeStateEntity), typeof(AccountSkillTreeUnlockedNodeEntity),
                typeof(InventoryEntity), typeof(InventoryEntryEntity), typeof(SkillTreeDefinitionGenerationEntity),
                typeof(SkillTreeServerRuntimeEntity), typeof(SkillTreeServerPlayerViewEntity), typeof(SkillTreeOperationEntity),
                typeof(SkillTreeMigrationOperationEntity), typeof(SkillTreeAccountSessionEntity),
            };
            foreach (var entity in builder.Model.GetEntityTypes().ToList())
                if (!allowed.Contains(entity.ClrType)) builder.Ignore(entity.ClrType);
        }
    }
}
