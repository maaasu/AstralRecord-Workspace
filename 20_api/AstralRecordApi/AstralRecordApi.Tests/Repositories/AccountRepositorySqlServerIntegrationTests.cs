using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

[Trait("Category", "SqlServerIntegration")]
public sealed class AccountRepositorySqlServerIntegrationTests
{
    private const string DatabasePrefix = "AstralRecordAccountIntegration_";
    private const string LocalSqlServerInstance = @"localhost\SQLEXPRESS";
    private const string SqlServerOptInEnvironmentVariable = "ASTRALRECORD_RUN_SQLSERVER_INTEGRATION";

    [Fact]
    public async Task CloneAsync_ComposesLockedAccountQueryAsValidSqlServerSql()
    {
        if (!SqlServerIntegrationEnabled())
            return;

        var databaseName = DatabasePrefix + Guid.NewGuid().ToString("N");
        var connectionString = BuildConnectionString(databaseName);
        try
        {
            await CreateDatabaseAsync(databaseName);
            var sourceUserId = Guid.NewGuid();
            var targetUserId = Guid.NewGuid();
            var sourceAccountId = Guid.NewGuid();
            var now = DateTime.UtcNow;

            await using (var setup = CreateDbContext(connectionString))
            {
                await setup.Database.EnsureCreatedAsync();
                setup.Users.AddRange(
                    CreateUser(sourceUserId, sourceAccountId, "source", now),
                    CreateUser(targetUserId, null, "target", now));
                setup.Accounts.Add(CreateAccount(sourceAccountId, sourceUserId, now));
                await setup.SaveChangesAsync();
            }

            await using var dbContext = CreateDbContext(connectionString);
            var cloned = await new AccountRepository(dbContext).CloneAsync(sourceAccountId, new AccountCloneRequest
            {
                TargetUserId = targetUserId,
                TargetSlotIndex = 0,
                CreatedBy = sourceUserId,
            });

            Assert.NotNull(cloned);
            Assert.Equal(targetUserId, cloned!.Account.UserId);
            Assert.Equal(0, cloned.Account.SlotIndex);
        }
        finally
        {
            await DropDatabaseAsync(databaseName);
        }
    }

    private static AstralRecordDbContext CreateDbContext(string connectionString) => new(
        new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlServer(connectionString, options => options.EnableRetryOnFailure())
            .Options);

    private static UserEntity CreateUser(Guid userId, Guid? accountId, string mcid, DateTime now) => new()
    {
        Uuid = userId,
        Mcid = mcid,
        JoinDate = now,
        LastJoinDate = now,
        GlobalIp = "127.0.0.1",
        AccountId = accountId,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = userId,
        UpdatedBy = userId,
    };

    private static AccountEntity CreateAccount(Guid accountId, Guid userId, DateTime now) => new()
    {
        Uuid = accountId,
        UserId = userId,
        AccountName = "source",
        SlotIndex = 0,
        IsActive = true,
        Mode = 0,
        MenuShortcutsJson = "{}",
        Level = 1,
        TotalExperience = 0,
        ClassId = "adventurer",
        ClassLevel = 1,
        ClassExperience = 0,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = userId,
        UpdatedBy = userId,
        IsDeleted = false,
    };

    private static async Task CreateDatabaseAsync(string databaseName)
    {
        EnsureTemporaryDatabaseName(databaseName);
        await using var connection = new SqlConnection(BuildConnectionString("master"));
        await connection.OpenAsync();
        await ExecuteAsync(connection, $"CREATE DATABASE {QuoteIdentifier(databaseName)}");
    }

    private static async Task DropDatabaseAsync(string databaseName)
    {
        EnsureTemporaryDatabaseName(databaseName);
        await using var connection = new SqlConnection(BuildConnectionString("master"));
        await connection.OpenAsync();
        await ExecuteAsync(connection,
            $"ALTER DATABASE {QuoteIdentifier(databaseName)} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; " +
            $"DROP DATABASE {QuoteIdentifier(databaseName)}");
    }

    private static string BuildConnectionString(string databaseName)
    {
        return new SqlConnectionStringBuilder
        {
            DataSource = LocalSqlServerInstance,
            InitialCatalog = databaseName,
            IntegratedSecurity = true,
            TrustServerCertificate = true,
        }.ConnectionString;
    }

    private static bool SqlServerIntegrationEnabled() => string.Equals(
        Environment.GetEnvironmentVariable(SqlServerOptInEnvironmentVariable),
        "1",
        StringComparison.Ordinal);

    private static void EnsureTemporaryDatabaseName(string databaseName)
    {
        if (!databaseName.StartsWith(DatabasePrefix, StringComparison.Ordinal)
            || databaseName.Length != DatabasePrefix.Length + 32)
        {
            throw new InvalidOperationException("Only this test's random temporary database may be created or removed.");
        }
    }

    private static string QuoteIdentifier(string value) =>
        "[" + value.Replace("]", "]]", StringComparison.Ordinal) + "]";

    private static async Task ExecuteAsync(SqlConnection connection, string commandText)
    {
        await using var command = new SqlCommand(commandText, connection);
        await command.ExecuteNonQueryAsync();
    }
}
