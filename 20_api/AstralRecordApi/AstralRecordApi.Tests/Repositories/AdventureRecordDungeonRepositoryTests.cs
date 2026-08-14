using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class AdventureRecordDungeonRepositoryTests
{
    [Fact]
    public async Task RecordDungeonClearAsync_InsertsThenIncrementsSameAccountDungeon()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        await using var context = new AstralRecordDbContext(options);
        await context.Database.EnsureCreatedAsync();
        context.Accounts.Add(NewAccount(accountId, userId));
        await context.SaveChangesAsync();
        var repository = new AdventureRecordRepository(context);
        var request = new AccountDungeonClearRequest
        {
            AccountId = accountId,
            DungeonId = "  twilight_mine  ",
            UpdatedBy = userId,
        };

        var first = await repository.RecordDungeonClearAsync(request);
        var second = await repository.RecordDungeonClearAsync(request);

        Assert.Equal(first.AccountDungeonRecordId, second.AccountDungeonRecordId);
        Assert.Equal("twilight_mine", second.DungeonId);
        Assert.Equal(2, second.ClearCount);
        Assert.Equal(first.FirstClearedAt, second.FirstClearedAt);
        Assert.True(second.LastClearedAt >= first.LastClearedAt);
        Assert.Single(await context.AccountDungeonRecords.ToListAsync());
    }

    [Fact]
    public async Task RecordDungeonClearAsync_UsesTransactionInsideRetryingExecutionStrategy()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new RetryingTestExecutionStrategy(dependencies)))
            .Options;
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        await using var context = new AstralRecordDbContext(options);
        await context.Database.EnsureCreatedAsync();
        context.Accounts.Add(NewAccount(accountId, userId));
        await context.SaveChangesAsync();

        var record = await new AdventureRecordRepository(context).RecordDungeonClearAsync(
            new AccountDungeonClearRequest
            {
                AccountId = accountId,
                DungeonId = "twilight_mine",
                UpdatedBy = userId,
            });

        Assert.Equal(accountId, record.AccountId);
        Assert.Equal("twilight_mine", record.DungeonId);
        Assert.Equal(1, record.ClearCount);
    }

    [Fact]
    public async Task GetDungeonRecordsByAccountIdAsync_ReturnsOnlyActiveAccountRecordsInLatestOrder()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        var accountId = Guid.NewGuid();
        var otherAccountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using var context = new AstralRecordDbContext(options);
        await context.Database.EnsureCreatedAsync();
        context.Accounts.AddRange(
            NewAccount(accountId, userId),
            NewAccount(otherAccountId, Guid.NewGuid()));
        context.AccountDungeonRecords.AddRange(
            NewRecord(accountId, "older", now.AddDays(-2), false),
            NewRecord(accountId, "newer", now, false),
            NewRecord(accountId, "deleted", now.AddDays(1), true),
            NewRecord(otherAccountId, "other", now.AddDays(2), false));
        await context.SaveChangesAsync();

        var records = await new AdventureRecordRepository(context)
            .GetDungeonRecordsByAccountIdAsync(accountId);

        Assert.Equal(["newer", "older"], records.Select(record => record.DungeonId).ToArray());
    }

    private static AccountEntity NewAccount(Guid accountId, Guid userId)
    {
        var now = DateTime.UtcNow;
        return new AccountEntity
        {
            Uuid = accountId,
            UserId = userId,
            AccountName = accountId.ToString("N"),
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
        };
    }

    private static AccountDungeonRecordEntity NewRecord(
        Guid accountId,
        string dungeonId,
        DateTime clearedAt,
        bool isDeleted) => new()
    {
        AccountDungeonRecordId = Guid.NewGuid(),
        AccountId = accountId,
        DungeonId = dungeonId,
        ClearCount = 1,
        FirstClearedAt = clearedAt,
        LastClearedAt = clearedAt,
        CreatedAt = clearedAt,
        UpdatedAt = clearedAt,
        CreatedBy = accountId,
        UpdatedBy = accountId,
        IsDeleted = isDeleted,
    };

    private sealed class RetryingTestExecutionStrategy(ExecutionStrategyDependencies dependencies)
        : ExecutionStrategy(dependencies, maxRetryCount: 1, maxRetryDelay: TimeSpan.Zero)
    {
        protected override bool ShouldRetryOn(Exception exception) => false;
    }
}
