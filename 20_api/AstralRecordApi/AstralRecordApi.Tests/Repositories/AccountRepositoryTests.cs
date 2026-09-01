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

public class AccountRepositoryTests
{
    [Fact]
    public async Task DeleteAsync_SelectsLowestRemainingAccountAndSoftDeletesTheTarget()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new RetryingTestExecutionStrategy(dependencies)))
            .Options;

        var userId = Guid.NewGuid();
        var deletedAccountId = Guid.NewGuid();
        var selectedAccountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.Add(CreateUser(userId, deletedAccountId, now));
            setupContext.Accounts.AddRange(
                CreateAccount(deletedAccountId, userId, 4, true, now),
                CreateAccount(selectedAccountId, userId, 1, false, now));
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        var result = await repository.DeleteAsync(deletedAccountId, new AccountDeleteRequest { DeletedBy = userId });

        Assert.NotNull(result);
        Assert.False(result!.CreatedReplacement);
        Assert.Equal(selectedAccountId, result.SelectedAccountId);
        var deleted = await dbContext.Accounts.SingleAsync(account => account.Uuid == deletedAccountId);
        var selected = await dbContext.Accounts.SingleAsync(account => account.Uuid == selectedAccountId);
        var user = await dbContext.Users.SingleAsync(user => user.Uuid == userId);
        Assert.True(deleted.IsDeleted);
        Assert.False(deleted.IsActive);
        Assert.True(selected.IsActive);
        Assert.Equal(selectedAccountId, user.AccountId);
    }

    [Fact]
    public async Task DeleteAsync_CreatesReplacementInDeletedSlotWhenNoAccountRemains()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new RetryingTestExecutionStrategy(dependencies)))
            .Options;

        var userId = Guid.NewGuid();
        var deletedAccountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.Add(CreateUser(userId, deletedAccountId, now));
            setupContext.Accounts.Add(CreateAccount(deletedAccountId, userId, 3, true, now));
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        var result = await repository.DeleteAsync(deletedAccountId, new AccountDeleteRequest { DeletedBy = userId });

        Assert.NotNull(result);
        Assert.True(result!.CreatedReplacement);
        var replacement = await dbContext.Accounts.SingleAsync(account => account.Uuid == result.SelectedAccountId);
        Assert.NotEqual(deletedAccountId, replacement.Uuid);
        Assert.Equal(3, replacement.SlotIndex);
        Assert.True(replacement.IsActive);
        Assert.False(replacement.IsDeleted);
    }

    [Fact]
    public async Task UpdateAsync_ActivatingAccountUpdatesUserSelectionAndDeactivatesOtherAccounts()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new RetryingTestExecutionStrategy(dependencies)))
            .Options;

        var userId = Guid.NewGuid();
        var currentAccountId = Guid.NewGuid();
        var targetAccountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.Add(CreateUser(userId, currentAccountId, now));
            setupContext.Accounts.AddRange(
                CreateAccount(currentAccountId, userId, 0, true, now),
                CreateAccount(targetAccountId, userId, 1, false, now));
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        var updated = await repository.UpdateAsync(targetAccountId, new AccountUpdateRequest
        {
            IsActive = true,
            UpdatedBy = userId,
        });

        Assert.NotNull(updated);
        Assert.Equal(targetAccountId, updated!.Uuid);
        var currentAccount = await dbContext.Accounts.SingleAsync(account => account.Uuid == currentAccountId);
        var targetAccount = await dbContext.Accounts.SingleAsync(account => account.Uuid == targetAccountId);
        var user = await dbContext.Users.SingleAsync(candidate => candidate.Uuid == userId);
        Assert.False(currentAccount.IsActive);
        Assert.True(targetAccount.IsActive);
        Assert.Equal(targetAccountId, user.AccountId);
    }

    [Fact]
    public async Task CreateAsync_LeavesAdditionalAccountInactiveUntilItIsSelected()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var interceptor = new CommitResultUnknownInterceptor();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new CommitResultUnknownRetryingExecutionStrategy(dependencies)))
            .AddInterceptors(interceptor)
            .Options;

        var userId = Guid.NewGuid();
        var existingAccountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.Add(CreateUser(userId, existingAccountId, now));
            setupContext.Accounts.Add(CreateAccount(existingAccountId, userId, 0, true, now));
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        interceptor.Arm();
        var created = await repository.CreateAsync(new AccountCreateRequest
        {
            UserId = userId,
            AccountName = "second",
            SlotIndex = 1,
            Mode = 0,
            CreatedBy = userId,
        });

        Assert.False(created.IsActive);
        Assert.True(interceptor.WasThrown);
        Assert.Equal(2, await dbContext.Accounts.CountAsync(account => account.UserId == userId));
    }

    [Fact]
    public async Task DeleteAsync_CommitResultUnknownReturnsCommittedDeleteResult()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var interceptor = new CommitResultUnknownInterceptor();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new CommitResultUnknownRetryingExecutionStrategy(dependencies)))
            .AddInterceptors(interceptor)
            .Options;

        var userId = Guid.NewGuid();
        var deletedAccountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.Add(CreateUser(userId, deletedAccountId, now));
            setupContext.Accounts.Add(CreateAccount(deletedAccountId, userId, 0, true, now));
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        interceptor.Arm();
        var result = await repository.DeleteAsync(
            deletedAccountId,
            new AccountDeleteRequest { DeletedBy = userId });

        Assert.NotNull(result);
        Assert.True(interceptor.WasThrown);
        Assert.True(result!.CreatedReplacement);
        Assert.NotEqual(deletedAccountId, result.SelectedAccountId);
        Assert.Equal(1, await dbContext.Accounts.CountAsync(account =>
            account.UserId == userId && !account.IsDeleted));
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.04-削除系.md
    /// 検証契約: 削除確定後に選択先が変わっても、commit結果不明の再送は初回削除の確定応答を返す。
    /// </summary>
    [Fact]
    public async Task DeleteAsync_ReplayReturnsOriginalSelectionAfterAnotherAccountIsActivated()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var userId = Guid.NewGuid();
        var deletedAccountId = Guid.NewGuid();
        var originalSelectedAccountId = Guid.NewGuid();
        var laterSelectedAccountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.Add(CreateUser(userId, deletedAccountId, now));
            setupContext.Accounts.AddRange(
                CreateAccount(deletedAccountId, userId, 4, true, now),
                CreateAccount(originalSelectedAccountId, userId, 1, false, now),
                CreateAccount(laterSelectedAccountId, userId, 2, false, now));
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        var request = new AccountDeleteRequest { DeletedBy = userId };
        var deleted = await repository.DeleteAsync(deletedAccountId, request);
        Assert.NotNull(deleted);
        Assert.Equal(originalSelectedAccountId, deleted!.SelectedAccountId);

        var switched = await repository.UpdateAsync(laterSelectedAccountId, new AccountUpdateRequest
        {
            IsActive = true,
            UpdatedBy = userId,
        });
        Assert.NotNull(switched);

        var replay = await repository.DeleteAsync(deletedAccountId, request);

        Assert.NotNull(replay);
        Assert.Equal(originalSelectedAccountId, replay!.SelectedAccountId);
        Assert.False(replay.CreatedReplacement);
    }

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

    private static UserEntity CreateUser(Guid userId, Guid accountId, DateTime now) => new()
    {
        Uuid = userId,
        Mcid = "tester",
        JoinDate = now,
        LastJoinDate = now,
        GlobalIp = "127.0.0.1",
        AccountId = accountId,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = userId,
        UpdatedBy = userId,
    };

    private static AccountEntity CreateAccount(Guid accountId, Guid userId, int slotIndex, bool isActive, DateTime now) => new()
    {
        Uuid = accountId,
        UserId = userId,
        AccountName = "tester",
        SlotIndex = slotIndex,
        IsActive = isActive,
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

    private sealed class RetryingTestExecutionStrategy(ExecutionStrategyDependencies dependencies)
        : ExecutionStrategy(dependencies, maxRetryCount: 1, maxRetryDelay: TimeSpan.Zero)
    {
        protected override bool ShouldRetryOn(Exception exception) => false;
    }

    private sealed class CommitResultUnknownRetryingExecutionStrategy(
        ExecutionStrategyDependencies dependencies)
        : ExecutionStrategy(dependencies, maxRetryCount: 1, maxRetryDelay: TimeSpan.Zero)
    {
        protected override bool ShouldRetryOn(Exception exception) =>
            exception is CommitResultUnknownException;
    }

    private sealed class CommitResultUnknownInterceptor : DbTransactionInterceptor
    {
        private bool armed;

        public bool WasThrown { get; private set; }

        public void Arm() => armed = true;

        public override Task TransactionCommittedAsync(
            DbTransaction transaction,
            TransactionEndEventData eventData,
            CancellationToken cancellationToken = default)
        {
            if (armed && !WasThrown)
            {
                armed = false;
                WasThrown = true;
                throw new CommitResultUnknownException();
            }
            return Task.CompletedTask;
        }
    }

    private sealed class CommitResultUnknownException : Exception;
}
