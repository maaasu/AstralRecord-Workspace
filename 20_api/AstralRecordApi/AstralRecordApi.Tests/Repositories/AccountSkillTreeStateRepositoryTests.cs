using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
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
        ");
    }
}
