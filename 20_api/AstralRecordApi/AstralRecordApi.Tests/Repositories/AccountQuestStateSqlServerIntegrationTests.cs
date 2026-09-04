using AstralRecordApi.Controllers;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

[Trait("Category", "SqlServerIntegration")]
public class AccountQuestStateSqlServerIntegrationTests
{
    private const string DatabasePrefix = "AstralRecordQuestIntegration_";
    private const string LocalSqlServerInstance = @"localhost\SQLEXPRESS";
    private const string SqlServerOptInEnvironmentVariable = "ASTRALRECORD_RUN_SQLSERVER_INTEGRATION";

    [Fact]
    public async Task PutSameAccountConcurrently_SucceedsAndKeepsOneCompleteSnapshot()
    {
        if (!SqlServerIntegrationEnabled())
            return;

        var databaseName = DatabasePrefix + Guid.NewGuid().ToString("N");
        var connectionString = BuildConnectionString(databaseName);
        try
        {
            await CreateDatabaseAsync(databaseName);
            await CreateQuestSchemaAsync(connectionString);

            var accountId = Guid.NewGuid();
            var updatedBy = Guid.NewGuid();
            await CreateAccountAsync(connectionString, accountId, updatedBy);
            await PutAsync(connectionString, accountId, CreateRequest("initial", updatedBy));

            var firstRequest = CreateRequest("first", updatedBy);
            var secondRequest = CreateRequest("second", updatedBy);
            using var start = new Barrier(2);
            var firstPut = Task.Run(async () =>
            {
                start.SignalAndWait();
                return await PutAsync(connectionString, accountId, firstRequest);
            });
            var secondPut = Task.Run(async () =>
            {
                start.SignalAndWait();
                return await PutAsync(connectionString, accountId, secondRequest);
            });

            var results = await Task.WhenAll(firstPut, secondPut);
            Assert.All(results, result => Assert.IsType<OkObjectResult>(result));

            await using var verifyContext = CreateDbContext(connectionString);
            var finalState = await new AccountQuestStateRepository(verifyContext).GetByAccountIdAsync(accountId);
            Assert.True(IsSnapshot(finalState, firstRequest) || IsSnapshot(finalState, secondRequest));
            Assert.Equal(2, finalState.ActiveQuests.Count);
            Assert.Single(finalState.Completions);
            Assert.Single(finalState.Cooldowns);
        }
        finally
        {
            await DropDatabaseAsync(databaseName);
        }
    }

    [Fact]
    public async Task PutConcurrentlyWithAccountDelete_SerializesWithoutDeadlock()
    {
        if (!SqlServerIntegrationEnabled())
            return;

        var databaseName = DatabasePrefix + Guid.NewGuid().ToString("N");
        var connectionString = BuildConnectionString(databaseName);
        try
        {
            await CreateDatabaseAsync(databaseName);
            await CreateFullSchemaAsync(connectionString);

            var userId = Guid.NewGuid();
            var accountId = Guid.NewGuid();
            var remainingAccountId = Guid.NewGuid();
            var inventoryId = Guid.NewGuid();
            var inventoryEntryId = Guid.NewGuid();
            var updatedBy = Guid.NewGuid();
            await SeedAccountDeleteScenarioAsync(
                connectionString,
                userId,
                accountId,
                remainingAccountId,
                inventoryId,
                inventoryEntryId,
                updatedBy);
            await PutAsync(connectionString, accountId, CreateRequest("initial", updatedBy));

            using var start = new Barrier(2);
            var put = Task.Run(async () =>
            {
                start.SignalAndWait();
                try
                {
                    return (Result: await PutAsync(connectionString, accountId, CreateRequest("concurrent", updatedBy)),
                        Exception: (Exception?)null);
                }
                catch (Exception exception)
                {
                    return (Result: (IActionResult?)null, Exception: exception);
                }
            });
            var delete = Task.Run(async () =>
            {
                await using var dbContext = CreateDbContext(connectionString);
                start.SignalAndWait();
                try
                {
                    return (Result: await new AccountRepository(dbContext).DeleteAsync(
                            accountId,
                            new AccountDeleteRequest { DeletedBy = updatedBy }),
                        Exception: (Exception?)null);
                }
                catch (Exception exception)
                {
                    return (Result: (AccountDeleteResponse?)null, Exception: exception);
                }
            });

            var combined = Task.WhenAll(put, delete);
            var completed = await Task.WhenAny(combined, Task.Delay(TimeSpan.FromSeconds(30)));
            Assert.Same(combined, completed);
            await combined;
            var putResult = await put;
            var deleteResult = await delete;

            Assert.Null(putResult.Exception);
            Assert.Null(deleteResult.Exception);
            Assert.True(putResult.Result is OkObjectResult or NotFoundResult);
            Assert.NotNull(deleteResult.Result);

            await using var verifyContext = CreateDbContext(connectionString);
            var deletedAccount = await verifyContext.Accounts
                .AsNoTracking()
                .SingleAsync(account => account.Uuid == accountId);
            var user = await verifyContext.Users
                .AsNoTracking()
                .SingleAsync(candidate => candidate.Uuid == userId);
            var inventoryEntry = await verifyContext.InventoryEntries
                .AsNoTracking()
                .SingleAsync(entry => entry.InventoryEntryId == inventoryEntryId);
            Assert.True(deletedAccount.IsDeleted);
            Assert.False(deletedAccount.IsActive);
            Assert.Equal(remainingAccountId, user.AccountId);
            Assert.True(inventoryEntry.IsDeleted);
        }
        finally
        {
            await DropDatabaseAsync(databaseName);
        }
    }

    private static AstralRecordDbContext CreateDbContext(string connectionString) => new(
        new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlServer(connectionString, sqlServerOptions => sqlServerOptions.EnableRetryOnFailure())
            .Options);

    private static async Task<IActionResult> PutAsync(
        string connectionString,
        Guid accountId,
        AccountQuestStateUpsertRequest request)
    {
        await using var dbContext = CreateDbContext(connectionString);
        var controller = new AccountQuestController(new AccountQuestStateRepository(dbContext));
        return await controller.Upsert(accountId, request);
    }

    private static async Task CreateDatabaseAsync(string databaseName)
    {
        EnsureTemporaryDatabaseName(databaseName);
        var builder = new SqlConnectionStringBuilder(BuildConnectionString("master"));
        await using var connection = new SqlConnection(builder.ConnectionString);
        await connection.OpenAsync();
        await ExecuteAsync(connection, $"CREATE DATABASE {QuoteIdentifier(databaseName)}");
    }

    private static async Task DropDatabaseAsync(string databaseName)
    {
        EnsureTemporaryDatabaseName(databaseName);
        var builder = new SqlConnectionStringBuilder(BuildConnectionString("master"));
        await using var connection = new SqlConnection(builder.ConnectionString);
        await connection.OpenAsync();
        await ExecuteAsync(connection, $"ALTER DATABASE {QuoteIdentifier(databaseName)} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE {QuoteIdentifier(databaseName)}");
    }

    private static async Task CreateQuestSchemaAsync(string connectionString)
    {
        await using var connection = new SqlConnection(connectionString);
        await connection.OpenAsync();
        await ExecuteAsync(connection, """
            CREATE TABLE dbo.account (
                uuid uniqueidentifier NOT NULL PRIMARY KEY,
                user_id uniqueidentifier NOT NULL,
                account_name nvarchar(max) NOT NULL,
                slot_index int NOT NULL,
                is_active bit NOT NULL,
                mode tinyint NOT NULL,
                menu_shortcuts_json nvarchar(max) NOT NULL,
                level int NOT NULL,
                total_experience bigint NOT NULL,
                class_id nvarchar(100) NOT NULL,
                class_level int NOT NULL,
                class_experience bigint NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE TABLE dbo.account_quest_state (
                account_quest_state_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_id uniqueidentifier NOT NULL,
                version int NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL,
                CONSTRAINT UX_account_quest_state_account UNIQUE (account_id)
            );
            CREATE TABLE dbo.account_quest_active (
                account_quest_active_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_quest_state_id uniqueidentifier NOT NULL,
                quest_id nvarchar(100) NOT NULL,
                accepted_at datetime2 NOT NULL,
                accepted_npc_id nvarchar(100) NULL,
                ready_to_turn_in bit NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                CONSTRAINT UX_account_quest_active_state_quest UNIQUE (account_quest_state_id, quest_id),
                CONSTRAINT FK_account_quest_active_state FOREIGN KEY (account_quest_state_id) REFERENCES dbo.account_quest_state(account_quest_state_id) ON DELETE CASCADE
            );
            CREATE TABLE dbo.account_quest_objective_progress (
                account_quest_objective_progress_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_quest_active_id uniqueidentifier NOT NULL,
                objective_id nvarchar(100) NOT NULL,
                progress int NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                CONSTRAINT UX_account_quest_objective_progress_active_objective UNIQUE (account_quest_active_id, objective_id),
                CONSTRAINT FK_account_quest_objective_progress_active FOREIGN KEY (account_quest_active_id) REFERENCES dbo.account_quest_active(account_quest_active_id) ON DELETE CASCADE
            );
            CREATE TABLE dbo.account_quest_completion (
                account_quest_completion_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_quest_state_id uniqueidentifier NOT NULL,
                quest_id nvarchar(100) NOT NULL,
                completed_at datetime2 NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                CONSTRAINT UX_account_quest_completion_state_quest UNIQUE (account_quest_state_id, quest_id),
                CONSTRAINT FK_account_quest_completion_state FOREIGN KEY (account_quest_state_id) REFERENCES dbo.account_quest_state(account_quest_state_id) ON DELETE CASCADE
            );
            CREATE TABLE dbo.account_quest_cooldown (
                account_quest_cooldown_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_quest_state_id uniqueidentifier NOT NULL,
                quest_id nvarchar(100) NOT NULL,
                cooldown_until datetime2 NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                CONSTRAINT UX_account_quest_cooldown_state_quest UNIQUE (account_quest_state_id, quest_id),
                CONSTRAINT FK_account_quest_cooldown_state FOREIGN KEY (account_quest_state_id) REFERENCES dbo.account_quest_state(account_quest_state_id) ON DELETE CASCADE
            );
            """);
    }

    private static async Task CreateFullSchemaAsync(string connectionString)
    {
        await using var dbContext = CreateDbContext(connectionString);
        await dbContext.Database.EnsureCreatedAsync();
    }

    private static async Task SeedAccountDeleteScenarioAsync(
        string connectionString,
        Guid userId,
        Guid accountId,
        Guid remainingAccountId,
        Guid inventoryId,
        Guid inventoryEntryId,
        Guid updatedBy)
    {
        await using var dbContext = CreateDbContext(connectionString);
        var now = DateTime.UtcNow;
        dbContext.Users.Add(new UserEntity
        {
            Uuid = userId,
            Mcid = "quest-delete-sqlserver-integration",
            JoinDate = now,
            LastJoinDate = now,
            GlobalIp = "127.0.0.1",
            AccountId = accountId,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
            IsDeleted = false,
        });
        dbContext.Accounts.AddRange(
            new AccountEntity
            {
                Uuid = accountId,
                UserId = userId,
                AccountName = "quest-delete-target",
                SlotIndex = 0,
                IsActive = true,
                Mode = 0,
                MenuShortcutsJson = "{}",
                Level = 1,
                ClassId = "adventurer",
                ClassLevel = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
                IsDeleted = false,
            },
            new AccountEntity
            {
                Uuid = remainingAccountId,
                UserId = userId,
                AccountName = "quest-delete-remaining",
                SlotIndex = 1,
                IsActive = false,
                Mode = 0,
                MenuShortcutsJson = "{}",
                Level = 1,
                ClassId = "adventurer",
                ClassLevel = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
                IsDeleted = false,
            });
        dbContext.Inventories.Add(new InventoryEntity
        {
            InventoryId = inventoryId,
            AccountId = accountId,
            InventoryType = "BAG",
            InventoryProfile = "GAME",
            SlotCapacity = 9,
            IsEnabled = true,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
            IsDeleted = false,
        });
        dbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = inventoryEntryId,
            InventoryId = inventoryId,
            SlotIndex = 1,
            ItemCategory = "MATERIAL",
            ItemId = "quest-delete-test-item",
            Quantity = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
            IsDeleted = false,
        });
        await dbContext.SaveChangesAsync();
    }

    private static async Task CreateAccountAsync(string connectionString, Guid accountId, Guid updatedBy)
    {
        await using var dbContext = CreateDbContext(connectionString);
        var now = DateTime.UtcNow;
        dbContext.Accounts.Add(new AccountEntity
        {
            Uuid = accountId,
            UserId = Guid.NewGuid(),
            AccountName = "quest-sqlserver-integration",
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
        });
        await dbContext.SaveChangesAsync();
    }

    private static AccountQuestStateUpsertRequest CreateRequest(string suffix, Guid updatedBy) => new()
    {
        UpdatedBy = updatedBy,
        ActiveQuests =
        [
            new()
            {
                QuestId = suffix + "_active_a",
                AcceptedAtEpochMillis = 1_785_542_400_000,
                AcceptedNpcId = suffix + "_npc",
                ReadyToTurnIn = true,
                ObjectiveProgress =
                [
                    new() { ObjectiveId = suffix + "_objective_a", Progress = 3 },
                    new() { ObjectiveId = suffix + "_objective_b", Progress = 7 },
                ],
            },
            new()
            {
                QuestId = suffix + "_active_b",
                AcceptedAtEpochMillis = 1_785_542_401_000,
                ObjectiveProgress = [new() { ObjectiveId = suffix + "_objective_c", Progress = 11 }],
            },
        ],
        Completions = [new() { QuestId = suffix + "_completion", CompletedAtEpochMillis = 1_785_542_402_000 }],
        Cooldowns = [new() { QuestId = suffix + "_cooldown", CooldownUntilEpochMillis = 1_785_542_403_000 }],
    };

    private static bool IsSnapshot(AccountQuestStateResponse response, AccountQuestStateUpsertRequest request)
    {
        var activeMatches = response.ActiveQuests.OrderBy(active => active.QuestId).Zip(
            request.ActiveQuests.OrderBy(active => active.QuestId),
            (actual, expected) => actual.QuestId == expected.QuestId
                && actual.AcceptedAtEpochMillis == expected.AcceptedAtEpochMillis
                && actual.AcceptedNpcId == expected.AcceptedNpcId
                && actual.ReadyToTurnIn == expected.ReadyToTurnIn
                && actual.ObjectiveProgress.OrderBy(objective => objective.ObjectiveId).Select(objective => (objective.ObjectiveId, objective.Progress))
                    .SequenceEqual(expected.ObjectiveProgress.OrderBy(objective => objective.ObjectiveId).Select(objective => (objective.ObjectiveId, objective.Progress))))
            .All(match => match);
        var completionMatches = response.Completions.OrderBy(completion => completion.QuestId).Zip(
            request.Completions.OrderBy(completion => completion.QuestId),
            (actual, expected) => actual.QuestId == expected.QuestId
                && actual.CompletedAtEpochMillis == expected.CompletedAtEpochMillis)
            .All(match => match);
        var cooldownMatches = response.Cooldowns.OrderBy(cooldown => cooldown.QuestId).Zip(
            request.Cooldowns.OrderBy(cooldown => cooldown.QuestId),
            (actual, expected) => actual.QuestId == expected.QuestId
                && actual.CooldownUntilEpochMillis == expected.CooldownUntilEpochMillis)
            .All(match => match);

        return response.ActiveQuests.Count == request.ActiveQuests.Count
            && response.Completions.Count == request.Completions.Count
            && response.Cooldowns.Count == request.Cooldowns.Count
            && activeMatches
            && completionMatches
            && cooldownMatches;
    }

    private static string BuildConnectionString(string databaseName)
    {
        EnsureLocalSqlServerInstance(LocalSqlServerInstance);
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

    private static void EnsureLocalSqlServerInstance(string dataSource)
    {
        if (!string.Equals(dataSource, LocalSqlServerInstance, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("SQL Server integration tests may only connect to localhost\\SQLEXPRESS.");
    }

    private static string QuoteIdentifier(string value) => "[" + value.Replace("]", "]]", StringComparison.Ordinal) + "]";

    private static async Task ExecuteAsync(SqlConnection connection, string commandText)
    {
        await using var command = new SqlCommand(commandText, connection);
        await command.ExecuteNonQueryAsync();
    }
}
