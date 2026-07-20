using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class AccountGuideProgressRepositoryTests
{
    [Fact]
    public async Task CompleteStepAsync_IsIdempotentAndReturnedByAccountState()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        await using (var setup = new AstralRecordDbContext(options))
        {
            await CreateSchemaAsync(setup);
            setup.Accounts.Add(NewAccount(accountId, userId));
            await setup.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountGuideProgressRepository(dbContext);
        var request = new AccountGuideStepCompleteRequest
        {
            GuideId = " action_ring_skill_cast ",
            StepId = " open_action_ring ",
            UpdatedBy = userId,
        };

        var first = await repository.CompleteStepAsync(accountId, request);
        var second = await repository.CompleteStepAsync(accountId, request);
        var state = await repository.GetByAccountIdAsync(accountId);

        Assert.Equal(first.AccountGuideStepProgressId, second.AccountGuideStepProgressId);
        Assert.Single(state.CompletedSteps);
        Assert.Equal("action_ring_skill_cast", state.CompletedSteps[0].GuideId);
        Assert.Equal("open_action_ring", state.CompletedSteps[0].StepId);
    }

    private static AccountEntity NewAccount(Guid accountId, Guid userId)
    {
        var now = DateTime.UtcNow;
        return new AccountEntity
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
            ClassId = "adventurer",
            ClassLevel = 1,
            ClassExperience = 0,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = userId,
            UpdatedBy = userId,
            IsDeleted = false,
        };
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

            CREATE TABLE account_guide_step_progress (
                account_guide_step_progress_id TEXT NOT NULL PRIMARY KEY,
                account_id TEXT NOT NULL,
                guide_id TEXT NOT NULL,
                step_id TEXT NOT NULL,
                completed_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                created_by TEXT NOT NULL
            );

            CREATE UNIQUE INDEX UX_account_guide_step_progress_account_guide_step
                ON account_guide_step_progress (account_id, guide_id, step_id);
        ");
    }
}
