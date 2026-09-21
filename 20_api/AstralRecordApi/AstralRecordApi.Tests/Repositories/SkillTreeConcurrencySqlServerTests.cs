using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

[Trait("Category", "SqlServerIntegration")]
public sealed class SkillTreeConcurrencySqlServerTests
{
    [Fact]
    public async Task ConcurrentRequestsAndSessionsSerializeOnAccountRow()
    {
        if (Environment.GetEnvironmentVariable("ASTRALRECORD_RUN_SQLSERVER_INTEGRATION") != "1") return;
        var name = "AstralRecordSkillTreeConcurrency_" + Guid.NewGuid().ToString("N");
        await ExecuteAsync("master", $"CREATE DATABASE [{name}]");
        try
        {
            await using var seed = Context(name);
            await seed.Database.EnsureCreatedAsync();
            await using var fixture = await SkillTreeOperationRepositoryTests.Fixture.SeedAsync(seed);
            var firstRequest = fixture.Request(); var secondRequest = fixture.Request();
            var result = await Task.WhenAll(Create(firstRequest), Create(secondRequest));
            Assert.Single(result, x => x is not null);
            await using (var check = Context(name))
            {
                Assert.Single(await check.SkillTreeOperations.ToListAsync());
                var operation = result.Single(x => x is not null)!;
                await new SkillTreeOperationRepository(check, new NetworkRuntimeService(TimeProvider.System))
                    .CancelAsync(fixture.Account, operation.OperationId, fixture.User);
            }
            var retryRequest = fixture.Request();
            var repeated = await Task.WhenAll(Create(retryRequest), Create(retryRequest));
            Assert.All(repeated, value => Assert.NotNull(value));
            Assert.Equal(repeated[0]!.OperationId, repeated[1]!.OperationId);
            await fixture.CloseAsync();
            var sessions = await Task.WhenAll(Acquire(Guid.NewGuid()), Acquire(Guid.NewGuid()));
            Assert.Single(sessions, value => value);
            await using var final = Context(name);
            Assert.Single(await final.SkillTreeAccountSessions.Where(x => !x.Closed).ToListAsync());

            async Task<SkillTreeOperationResponse?> Create(SkillTreeOperationCreateRequest request)
            {
                await using var context = Context(name);
                return await new SkillTreeOperationRepository(context, new NetworkRuntimeService(TimeProvider.System)).CreateAsync(fixture.Account, request);
            }
            async Task<bool> Acquire(Guid id)
            {
                await using var context = Context(name);
                return await new SkillTreeOperationRepository(context, new NetworkRuntimeService(TimeProvider.System)).AcquireAccountSessionAsync(
                    fixture.Server, fixture.Account, new() { AccountSessionId = id, AccountLeaseToken = SkillTreeOperationRepositoryTests.Hash(id.ToString()), ServerSessionId = fixture.Boot, DefinitionGenerationId = fixture.Generation });
            }
        }
        finally
        {
            if (!name.StartsWith("AstralRecordSkillTreeConcurrency_", StringComparison.Ordinal)
                || !Guid.TryParseExact(name["AstralRecordSkillTreeConcurrency_".Length..], "N", out _))
                throw new InvalidOperationException("Refusing to remove an unrelated database.");
            await ExecuteAsync("master", $"ALTER DATABASE [{name}] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE [{name}]");
        }
    }

    private static string ConnectionString(string database) => new SqlConnectionStringBuilder
    {
        DataSource = @"localhost\SQLEXPRESS", InitialCatalog = database, IntegratedSecurity = true,
        TrustServerCertificate = true, Pooling = false, ConnectTimeout = 15,
    }.ConnectionString;
    private static LimitedContext Context(string database) => new(new DbContextOptionsBuilder<AstralRecordDbContext>()
        .UseSqlServer(ConnectionString(database), options => options.CommandTimeout(30).EnableRetryOnFailure()).Options);
    private static async Task ExecuteAsync(string database, string sql)
    {
        await using var connection = new SqlConnection(ConnectionString(database));
        await connection.OpenAsync();
        await using var command = new SqlCommand(sql, connection) { CommandTimeout = 30 };
        await command.ExecuteNonQueryAsync();
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
