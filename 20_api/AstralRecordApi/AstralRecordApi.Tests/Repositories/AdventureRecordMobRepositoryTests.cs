using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class AdventureRecordMobRepositoryTests
{
    [Fact]
    public async Task GetMobRecordsByAccountIdAsync_WithoutCategory_ReturnsEnemyAndBossRecords()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        var accountId = Guid.NewGuid();
        var otherAccountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using var context = new AstralRecordDbContext(options);
        await context.Database.EnsureCreatedAsync();
        context.AccountMobRecords.AddRange(
            NewRecord(accountId, "slime", "ENEMY", now.AddMinutes(-2), false),
            NewRecord(accountId, "ancient_dragon", "BOSS", now, false),
            NewRecord(accountId, "deleted_boss", "BOSS", now.AddMinutes(1), true),
            NewRecord(otherAccountId, "other_boss", "BOSS", now.AddMinutes(2), false));
        await context.SaveChangesAsync();

        var records = await new AdventureRecordRepository(context)
            .GetMobRecordsByAccountIdAsync(accountId, null);

        Assert.Equal(["ancient_dragon", "slime"], records.Select(record => record.MobId).ToArray());
        Assert.Equal(["BOSS", "ENEMY"], records.Select(record => record.MobCategory).ToArray());
    }

    [Fact]
    public async Task GetMobRecordsByAccountIdAsync_WithCategory_ReturnsOnlyRequestedCategory()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        var accountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using var context = new AstralRecordDbContext(options);
        await context.Database.EnsureCreatedAsync();
        context.AccountMobRecords.AddRange(
            NewRecord(accountId, "slime", "ENEMY", now.AddMinutes(-1), false),
            NewRecord(accountId, "ancient_dragon", "BOSS", now, false));
        await context.SaveChangesAsync();

        var records = await new AdventureRecordRepository(context)
            .GetMobRecordsByAccountIdAsync(accountId, " boss ");

        var record = Assert.Single(records);
        Assert.Equal("ancient_dragon", record.MobId);
        Assert.Equal("BOSS", record.MobCategory);
    }

    private static AccountMobRecordEntity NewRecord(
        Guid accountId,
        string mobId,
        string mobCategory,
        DateTime defeatedAt,
        bool isDeleted) => new()
    {
        AccountMobRecordId = Guid.NewGuid(),
        AccountId = accountId,
        MobId = mobId,
        MobCategory = mobCategory,
        DefeatCount = 1,
        FirstDefeatedAt = defeatedAt,
        LastDefeatedAt = defeatedAt,
        CreatedAt = defeatedAt,
        UpdatedAt = defeatedAt,
        CreatedBy = Guid.NewGuid(),
        UpdatedBy = Guid.NewGuid(),
        IsDeleted = isDeleted,
    };
}
