using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class AccountRepositoryTests
{
    [Fact]
    public async Task UpdateAsync_UpdatesLevelAndTotalExperienceTogether()
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
            await setupContext.Database.ExecuteSqlRawAsync(@"
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
                CREATE TABLE account_class_progress (
                    account_id TEXT NOT NULL,
                    class_id TEXT NOT NULL,
                    level INTEGER NOT NULL,
                    experience INTEGER NOT NULL,
                    updated_at TEXT NOT NULL,
                    updated_by TEXT NOT NULL,
                    PRIMARY KEY (account_id, class_id)
                );");

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
                IsDeleted = false
            });
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);

        var updated = await repository.UpdateAsync(accountId, new AccountUpdateRequest
        {
            Level = 4,
            TotalExperience = 987,
            UpdatedBy = userId
        });

        Assert.NotNull(updated);
        Assert.Equal(4, updated!.Level);
        Assert.Equal(987, updated.TotalExperience);
    }

    [Fact]
    public async Task UpdateAsync_PreservesProgressForEachClassWhenSwitching()
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
            await setupContext.Database.ExecuteSqlRawAsync(@"
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
                CREATE TABLE account_class_progress (
                    account_id TEXT NOT NULL,
                    class_id TEXT NOT NULL,
                    level INTEGER NOT NULL,
                    experience INTEGER NOT NULL,
                    updated_at TEXT NOT NULL,
                    updated_by TEXT NOT NULL,
                    PRIMARY KEY (account_id, class_id)
                );");

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
                IsDeleted = false
            });
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);

        await repository.UpdateAsync(accountId, new AccountUpdateRequest
        {
            ClassId = "adventurer",
            ClassLevel = 10,
            ClassExperience = 4000,
            UpdatedBy = userId
        });

        var updated = await repository.UpdateAsync(accountId, new AccountUpdateRequest
        {
            ClassId = "warrior",
            ClassLevel = 7,
            ClassExperience = 3210,
            ClassProgresses =
            [
                new AccountClassProgressUpdateRequest
                {
                    ClassId = "adventurer",
                    Level = 10,
                    Experience = 4000,
                },
                new AccountClassProgressUpdateRequest
                {
                    ClassId = "warrior",
                    Level = 7,
                    Experience = 3210,
                },
            ],
            UpdatedBy = userId
        });

        Assert.NotNull(updated);
        Assert.Equal("warrior", updated!.ClassId);
        Assert.Equal(7, updated.ClassLevel);
        Assert.Equal(3210, updated.ClassExperience);
        Assert.Contains(updated.ClassProgresses, progress =>
            progress.ClassId == "warrior" && progress.Level == 7 && progress.Experience == 3210);
        Assert.Contains(updated.ClassProgresses, progress =>
            progress.ClassId == "adventurer" && progress.Level == 10 && progress.Experience == 4000);

        var switchedBack = await repository.UpdateAsync(accountId, new AccountUpdateRequest
        {
            ClassId = "adventurer",
            UpdatedBy = userId
        });

        Assert.NotNull(switchedBack);
        Assert.Equal("adventurer", switchedBack!.ClassId);
        Assert.Equal(10, switchedBack.ClassLevel);
        Assert.Equal(4000, switchedBack.ClassExperience);
    }

    [Fact]
    public async Task UpdateAsync_Throws_WhenOnlyLevelIsProvided()
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
            await setupContext.Database.ExecuteSqlRawAsync(@"
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
                CREATE TABLE account_class_progress (
                    account_id TEXT NOT NULL,
                    class_id TEXT NOT NULL,
                    level INTEGER NOT NULL,
                    experience INTEGER NOT NULL,
                    updated_at TEXT NOT NULL,
                    updated_by TEXT NOT NULL,
                    PRIMARY KEY (account_id, class_id)
                );");

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
                IsDeleted = false
            });
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);

        await Assert.ThrowsAsync<ArgumentException>(() => repository.UpdateAsync(accountId, new AccountUpdateRequest
        {
            Level = 2,
            UpdatedBy = userId
        }));
    }
}
