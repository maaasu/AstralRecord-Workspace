using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage;
using System.Data.Common;
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
        var repository = new AccountSkillTreeStateRepository(dbContext);

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
            var repository = new AccountSkillTreeStateRepository(dbContext);

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
            var repository = new AccountSkillTreeStateRepository(dbContext);

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
    public async Task RepairInvalidStateAsync_RetriesTransactionAndClearsNodesWithoutMail()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var transientInterceptor = new ThrowOnceOnUnlockedNodeDeleteInterceptor();
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

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountSkillTreeStateRepository(dbContext);
        var initial = await repository.UpsertAsync(accountId, new AccountSkillTreeStateUpsertRequest
        {
            UnlockedNodes = [new() { NodeId = "1000" }, new() { NodeId = "1001" }],
            UpdatedBy = accountId,
        });

        var request = new AccountSkillTreeInvalidStateRepairRequest
        {
            UserId = userId,
            ExpectedVersion = initial.Version,
            UpdatedBy = accountId,
        };
        transientInterceptor.Arm();
        var repaired = await repository.RepairInvalidStateAsync(accountId, request);
        Assert.Equal(1, transientInterceptor.TriggerCount);
        var retried = await repository.RepairInvalidStateAsync(accountId, request);

        Assert.Empty(repaired.UnlockedNodes);
        Assert.Equal(initial.Version + 1, repaired.Version);
        Assert.Empty(retried.UnlockedNodes);
        Assert.Equal(repaired.Version, retried.Version);
        var reselected = await repository.UpsertAsync(accountId, new AccountSkillTreeStateUpsertRequest
        {
            UnlockedNodes = [new() { NodeId = "9999" }],
            UpdatedBy = accountId,
        });
        await Assert.ThrowsAsync<DbUpdateConcurrencyException>(() => repository.RepairInvalidStateAsync(accountId,
            new AccountSkillTreeInvalidStateRepairRequest
            {
                UserId = userId,
                ExpectedVersion = repaired.Version,
                UpdatedBy = accountId,
            }));
        Assert.Equal("9999", Assert.Single((await repository.GetByAccountIdAsync(accountId)).UnlockedNodes).NodeId);
        var repairedAgain = await repository.RepairInvalidStateAsync(accountId,
            new AccountSkillTreeInvalidStateRepairRequest
            {
                UserId = userId,
                ExpectedVersion = reselected.Version,
                UpdatedBy = accountId,
            });

        Assert.Empty(repairedAgain.UnlockedNodes);
        Assert.Equal(reselected.Version + 1, repairedAgain.Version);
        await Assert.ThrowsAsync<DbUpdateConcurrencyException>(
            () => repository.RepairInvalidStateAsync(accountId, request));
        Assert.Empty(await dbContext.PlayerMailDeliveries.AsNoTracking().ToListAsync());
    }

    private static async Task CreateSchemaAsync(AstralRecordDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE skilltree_account_session (account_id TEXT NOT NULL);
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
                highest_level INTEGER NOT NULL,
                play_time_seconds INTEGER NOT NULL DEFAULT 0,
                selected_hotbar_slot INTEGER NOT NULL DEFAULT 0,
                view_yaw REAL NOT NULL DEFAULT 0,
                view_pitch REAL NOT NULL DEFAULT 0,
                controls_version INTEGER NOT NULL DEFAULT 0,
                rebirth_original_level INTEGER NULL,
                rebirth_experience_remainder INTEGER NOT NULL,
                class_id TEXT NOT NULL,
                class_level INTEGER NOT NULL,
                class_experience INTEGER NOT NULL,
                progress_version INTEGER NOT NULL,
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
                definition_generation_id TEXT NULL,
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
                account_id TEXT NOT NULL,
                mail_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                version INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE UNIQUE INDEX UX_player_mail_delivery_account_mail
                ON player_mail_delivery (account_id, mail_id);
        ");
    }

    private sealed class RetryingTestExecutionStrategy(ExecutionStrategyDependencies dependencies)
        : ExecutionStrategy(dependencies, maxRetryCount: 1, maxRetryDelay: TimeSpan.Zero)
    {
        protected override bool ShouldRetryOn(Exception exception)
            => exception is TransientTestException;
    }

    private sealed class ThrowOnceOnUnlockedNodeDeleteInterceptor : DbCommandInterceptor
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
                || !command.CommandText.Contains("DELETE FROM", StringComparison.OrdinalIgnoreCase)
                || !command.CommandText.Contains("account_skilltree_unlocked_node", StringComparison.OrdinalIgnoreCase)
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
