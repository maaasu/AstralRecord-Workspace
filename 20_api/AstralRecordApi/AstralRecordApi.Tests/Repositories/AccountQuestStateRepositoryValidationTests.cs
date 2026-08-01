using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class AccountQuestStateRepositoryValidationTests
{
    [Fact]
    public async Task UpsertAsync_RejectsDuplicateQuestIdsBeforeStartingDatabaseWork()
    {
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite("Data Source=:memory:")
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountQuestStateRepository(dbContext);
        var request = new AccountQuestStateUpsertRequest
        {
            ActiveQuests =
            [
                new()
                {
                    QuestId = "windwait_field_patrol",
                    AcceptedAtEpochMillis = 0,
                    ObjectiveProgress = [],
                },
                new()
                {
                    QuestId = "WINDWAIT_FIELD_PATROL",
                    AcceptedAtEpochMillis = 0,
                    ObjectiveProgress = [],
                },
            ],
            Completions = [],
            Cooldowns = [],
            UpdatedBy = Guid.NewGuid(),
        };

        await Assert.ThrowsAsync<ArgumentException>(() => repository.UpsertAsync(Guid.NewGuid(), request));
    }

    [Fact]
    public async Task GetByAccountIdAsync_MapsUnspecifiedDatabaseTimesAsUtcEpochMillis()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await dbContext.Database.EnsureCreatedAsync();

        var accountId = Guid.NewGuid();
        var updatedBy = Guid.NewGuid();
        const long epochMillis = 1785542400000L;
        var databaseTime = DateTime.SpecifyKind(
            DateTimeOffset.FromUnixTimeMilliseconds(epochMillis).UtcDateTime,
            DateTimeKind.Unspecified
        );
        var stateId = Guid.NewGuid();
        dbContext.Accounts.Add(new AccountEntity
        {
            Uuid = accountId,
            UserId = Guid.NewGuid(),
            AccountName = "quest-time-test",
            CreatedAt = databaseTime,
            UpdatedAt = databaseTime,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
        });
        dbContext.AccountQuestStates.Add(new AccountQuestStateEntity
        {
            AccountQuestStateId = stateId,
            AccountId = accountId,
            CreatedAt = databaseTime,
            UpdatedAt = databaseTime,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
            ActiveQuests =
            [
                new AccountQuestActiveEntity
                {
                    AccountQuestActiveId = Guid.NewGuid(),
                    AccountQuestStateId = stateId,
                    QuestId = "active_quest",
                    AcceptedAt = databaseTime,
                    CreatedAt = databaseTime,
                    UpdatedAt = databaseTime,
                    CreatedBy = updatedBy,
                    UpdatedBy = updatedBy,
                },
            ],
            Completions =
            [
                new AccountQuestCompletionEntity
                {
                    AccountQuestCompletionId = Guid.NewGuid(),
                    AccountQuestStateId = stateId,
                    QuestId = "completed_quest",
                    CompletedAt = databaseTime,
                    CreatedAt = databaseTime,
                    UpdatedAt = databaseTime,
                    CreatedBy = updatedBy,
                    UpdatedBy = updatedBy,
                },
            ],
            Cooldowns =
            [
                new AccountQuestCooldownEntity
                {
                    AccountQuestCooldownId = Guid.NewGuid(),
                    AccountQuestStateId = stateId,
                    QuestId = "cooldown_quest",
                    CooldownUntil = databaseTime,
                    CreatedAt = databaseTime,
                    UpdatedAt = databaseTime,
                    CreatedBy = updatedBy,
                    UpdatedBy = updatedBy,
                },
            ],
        });
        await dbContext.SaveChangesAsync();

        var response = await new AccountQuestStateRepository(dbContext).GetByAccountIdAsync(accountId);

        Assert.Equal(epochMillis, response.ActiveQuests.Single().AcceptedAtEpochMillis);
        Assert.Equal(epochMillis, response.Completions.Single().CompletedAtEpochMillis);
        Assert.Equal(epochMillis, response.Cooldowns.Single().CooldownUntilEpochMillis);
    }
}
