using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage;
using System.Data.Common;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class AccountSkillTreeStateRepositoryTests
{
    [Fact]
    public async Task GetByAccountIdAsync_ReturnsUnsavedState_WhenNoPersistedRowExists()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateSchemaAsync(setupContext);
            setupContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = userId,
                AccountName = "tester",
                SlotIndex = 0,
                IsActive = true,
                Mode = 0,
                MenuShortcutsJson = "{}",
                Level = 1,
                TotalExperience = 0,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        await using var masterDataDbContext = CreateUnusedMasterDataDbContext();
        var repository = new AccountSkillTreeStateRepository(dbContext, masterDataDbContext);

        var state = await repository.GetByAccountIdAsync(accountId);

        Assert.Null(state.AccountSkillTreeStateId);
        Assert.Equal(accountId, state.AccountId);
        Assert.Empty(state.UnlockedNodes);
        Assert.False(state.IsSaved);
        Assert.Equal(0, state.Version);
    }

    [Fact]
    public async Task UpsertAsync_CreatesAndReplacesUnlockedNodes_WhileIncrementingVersion()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateSchemaAsync(setupContext);
            setupContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = userId,
                AccountName = "tester",
                SlotIndex = 0,
                IsActive = true,
                Mode = 0,
                MenuShortcutsJson = "{}",
                Level = 1,
                TotalExperience = 0,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });
            await setupContext.SaveChangesAsync();
        }

        await using (var dbContext = new AstralRecordDbContext(options))
        {
            await using var masterDataDbContext = CreateUnusedMasterDataDbContext();
            var repository = new AccountSkillTreeStateRepository(dbContext, masterDataDbContext);

            var created = await repository.UpsertAsync(accountId, new AccountSkillTreeStateUpsertRequest
            {
                UnlockedNodes =
                [
                    new() { NodeId = " starter_power ", ConsumedClassId = " Adventurer " },
                    new() { NodeId = "", ConsumedClassId = "hunter" },
                    new() { NodeId = "starter_vital" },
                    new() { NodeId = "starter_power", ConsumedClassId = "hunter" },
                ],
                UpdatedBy = userId,
            });

            Assert.True(created.IsSaved);
            Assert.Equal(1, created.Version);
            Assert.Collection(
                created.UnlockedNodes,
                node =>
                {
                    Assert.Equal("starter_power", node.NodeId);
                    Assert.Equal("adventurer", node.ConsumedClassId);
                },
                node =>
                {
                    Assert.Equal("starter_vital", node.NodeId);
                    Assert.Null(node.ConsumedClassId);
                });
        }

        await using (var dbContext = new AstralRecordDbContext(options))
        {
            await using var masterDataDbContext = CreateUnusedMasterDataDbContext();
            var repository = new AccountSkillTreeStateRepository(dbContext, masterDataDbContext);

            var updated = await repository.UpsertAsync(accountId, new AccountSkillTreeStateUpsertRequest
            {
                UnlockedNodes = [new() { NodeId = "hybrid_guard", ConsumedClassId = "hunter" }],
                UpdatedBy = userId,
            });

            Assert.True(updated.IsSaved);
            Assert.Equal(2, updated.Version);
            var node = Assert.Single(updated.UnlockedNodes);
            Assert.Equal("hybrid_guard", node.NodeId);
            Assert.Equal("hunter", node.ConsumedClassId);
        }
    }

    [Fact]
    public async Task RepairInvalidStateAsync_RetriesTransactionAndDeliversTemplateMailOnlyOncePerRepairKey()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var transientInterceptor = new ThrowOnceOnMailInsertInterceptor();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new RetryingTestExecutionStrategy(dependencies)))
            .AddInterceptors(transientInterceptor)
            .Options;
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateSchemaAsync(setupContext);
            setupContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = userId,
                AccountName = "tester",
                SlotIndex = 0,
                IsActive = true,
                Mode = 0,
                MenuShortcutsJson = "{}",
                Level = 1,
                TotalExperience = 0,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });
            await setupContext.SaveChangesAsync();
        }

        await using var masterConnection = new SqliteConnection("Data Source=:memory:");
        await masterConnection.OpenAsync();
        var masterOptions = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(masterConnection)
            .Options;
        await using var masterDataDbContext = new MasterDataDbContext(masterOptions);
        await MasterDataTestSeed.CreateSchemaAsync(masterDataDbContext);
        await MasterDataTestSeed.SeedInlinePayloadAsync(
            masterDataDbContext,
            MasterDataTestFixtures.CompensationMail,
            "mail",
            null);

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountSkillTreeStateRepository(dbContext, masterDataDbContext);
        await repository.UpsertAsync(accountId, new AccountSkillTreeStateUpsertRequest
        {
            UnlockedNodes = [new() { NodeId = "1000" }, new() { NodeId = "1001" }],
            UpdatedBy = accountId,
        });

        const string repairKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        transientInterceptor.Arm();
        var repaired = await repository.RepairInvalidStateAsync(accountId,
            new AccountSkillTreeInvalidStateRepairRequest
            {
                UserId = userId,
                RepairKey = repairKey,
                UpdatedBy = accountId,
            });
        Assert.Equal(1, transientInterceptor.TriggerCount);
        var retried = await repository.RepairInvalidStateAsync(accountId,
            new AccountSkillTreeInvalidStateRepairRequest
            {
                UserId = userId,
                RepairKey = repairKey,
                UpdatedBy = accountId,
            });

        Assert.Empty(repaired.UnlockedNodes);
        Assert.Empty(retried.UnlockedNodes);
        Assert.Equal(repaired.Version, retried.Version);
        await repository.UpsertAsync(accountId, new AccountSkillTreeStateUpsertRequest
        {
            UnlockedNodes = [new() { NodeId = "9999" }],
            UpdatedBy = accountId,
        });
        var repairedAgain = await repository.RepairInvalidStateAsync(accountId,
            new AccountSkillTreeInvalidStateRepairRequest
            {
                UserId = userId,
                RepairKey = repairKey,
                UpdatedBy = accountId,
            });

        Assert.Empty(repairedAgain.UnlockedNodes);
        Assert.True(repairedAgain.Version > retried.Version);
        var delivery = await dbContext.PlayerMailDeliveries.AsNoTracking().SingleAsync();
        Assert.Equal(userId, delivery.UserId);
        Assert.Equal("skilltree-structure-reset-" + repairKey, delivery.MailId);
        var deliveredMail = JsonSerializer.Deserialize<MailResponse>(delivery.PayloadJson,
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        Assert.NotNull(deliveredMail);
        Assert.Equal(delivery.MailId, deliveredMail.Id);
        Assert.Equal("スキルツリー選択状態のリセットについて", deliveredMail.Title);
    }

    private static async Task CreateSchemaAsync(AstralRecordDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE account (
                uuid TEXT NOT NULL PRIMARY KEY,
                user_id TEXT NOT NULL,
                account_name TEXT NOT NULL,
                slot_index INTEGER NOT NULL,
                is_active INTEGER NOT NULL,
                mode INTEGER NOT NULL,
                menu_shortcuts_json TEXT NOT NULL,
                level INTEGER NOT NULL,
                total_experience INTEGER NOT NULL,
                class_id TEXT NOT NULL,
                class_level INTEGER NOT NULL,
                class_experience INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE TABLE account_skilltree_state (
                account_skilltree_state_id TEXT NOT NULL PRIMARY KEY,
                account_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE UNIQUE INDEX UX_account_skilltree_state_account
                ON account_skilltree_state (account_id);

            CREATE TABLE account_skilltree_unlocked_node (
                account_skilltree_unlocked_node_id TEXT NOT NULL PRIMARY KEY,
                account_skilltree_state_id TEXT NOT NULL,
                node_id TEXT NOT NULL,
                consumed_class_id TEXT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL
            );

            CREATE UNIQUE INDEX UX_account_skilltree_unlocked_node_state_node
                ON account_skilltree_unlocked_node (account_skilltree_state_id, node_id);

            CREATE TABLE player_mail_delivery (
                player_mail_delivery_id TEXT NOT NULL PRIMARY KEY,
                user_id TEXT NOT NULL,
                mail_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                version INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE UNIQUE INDEX UX_player_mail_delivery_user_mail
                ON player_mail_delivery (user_id, mail_id);
        ");
    }

    private static MasterDataDbContext CreateUnusedMasterDataDbContext()
        => new(new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite("Data Source=:memory:")
            .Options);

    private sealed class RetryingTestExecutionStrategy(ExecutionStrategyDependencies dependencies)
        : ExecutionStrategy(dependencies, maxRetryCount: 1, maxRetryDelay: TimeSpan.Zero)
    {
        protected override bool ShouldRetryOn(Exception exception)
            => exception is TransientTestException;
    }

    private sealed class ThrowOnceOnMailInsertInterceptor : DbCommandInterceptor
    {
        private int isArmed;
        private int triggerCount;

        public int TriggerCount => Volatile.Read(ref triggerCount);

        public void Arm() => Interlocked.Exchange(ref isArmed, 1);

        public override ValueTask<InterceptionResult<int>> NonQueryExecutingAsync(
            DbCommand command,
            CommandEventData eventData,
            InterceptionResult<int> result,
            CancellationToken cancellationToken = default)
        {
            if (ShouldThrow(command))
            {
                throw new TransientTestException();
            }

            return base.NonQueryExecutingAsync(command, eventData, result, cancellationToken);
        }

        public override ValueTask<InterceptionResult<DbDataReader>> ReaderExecutingAsync(
            DbCommand command,
            CommandEventData eventData,
            InterceptionResult<DbDataReader> result,
            CancellationToken cancellationToken = default)
        {
            if (ShouldThrow(command))
            {
                throw new TransientTestException();
            }

            return base.ReaderExecutingAsync(command, eventData, result, cancellationToken);
        }

        private bool ShouldThrow(DbCommand command)
        {
            if (Volatile.Read(ref isArmed) != 1
                || !command.CommandText.Contains("INSERT INTO", StringComparison.OrdinalIgnoreCase)
                || !command.CommandText.Contains("player_mail_delivery", StringComparison.OrdinalIgnoreCase)
                || Interlocked.Exchange(ref isArmed, 0) != 1)
            {
                return false;
            }

            Interlocked.Increment(ref triggerCount);
            return true;
        }
    }

    private sealed class TransientTestException : Exception
    {
    }
}
