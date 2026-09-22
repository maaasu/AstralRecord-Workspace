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
    public void RebirthExperienceRemainder_UsesSqlServerSmallIntProviderMapping()
    {
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlServer("Server=localhost;Database=unused;Integrated Security=True;TrustServerCertificate=True")
            .Options;

        using var dbContext = new AstralRecordDbContext(options);
        var property = dbContext.Model
            .FindEntityType(typeof(AccountEntity))!
            .FindProperty(nameof(AccountEntity.RebirthExperienceRemainder))!;

        Assert.Equal(typeof(int), property.ClrType);
        Assert.Equal(typeof(short), property.GetTypeMapping().Converter?.ProviderClrType);
        Assert.Equal("smallint", property.GetColumnType());
    }

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

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/02-account/1-モデル定義/02_1.00-モデル定義.md
    /// 検証契約: 自動作成名は ASCII 英数字とアンダースコアを保持し、大小文字を区別せず重複を避け、連番の空き名を返す。
    /// </summary>
    [Fact]
    public async Task CreateAsync_GeneratesCaseInsensitiveUniqueNamesWithIncrementingSuffix()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        var now = DateTime.UtcNow;
        var userIds = Enumerable.Range(0, 3).Select(_ => Guid.NewGuid()).ToArray();

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.AddRange(userIds.Select(userId => new UserEntity
            {
                Uuid = userId,
                Mcid = $"player-{userId:N}",
                JoinDate = now,
                LastJoinDate = now,
                GlobalIp = "127.0.0.1",
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            }));
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);

        var first = await repository.CreateAsync(new AccountCreateRequest
        {
            UserId = userIds[0], AccountName = "Alice_Test", SlotIndex = 0, Mode = 0, CreatedBy = userIds[0],
        });
        var second = await repository.CreateAsync(new AccountCreateRequest
        {
            UserId = userIds[1], AccountName = "alice_test", SlotIndex = 0, Mode = 0, CreatedBy = userIds[1],
        });
        var third = await repository.CreateAsync(new AccountCreateRequest
        {
            UserId = userIds[2], AccountName = "Alice_Test", SlotIndex = 0, Mode = 0, CreatedBy = userIds[2],
        });

        Assert.Equal("Alice_Test", first.AccountName);
        Assert.Equal("alice_test1", second.AccountName);
        Assert.Equal("Alice_Test2", third.AccountName);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/02-account/3-エンドポイント仕様/02_3.03-更新系.md
    /// 検証契約: 手動変更名は ASCII 英数字またはアンダースコアを含む3〜50文字を受理し、許可外文字と既存名との大小無視重複は拒否する。
    /// </summary>
    [Fact]
    public async Task UpdateAsync_RejectsInvalidOrDuplicateManualAccountName()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        var userId = Guid.NewGuid();
        var firstAccountId = Guid.NewGuid();
        var secondAccountId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.Add(CreateUser(userId, firstAccountId, now));
            var firstAccount = CreateAccount(firstAccountId, userId, 0, true, now);
            firstAccount.AccountName = "Alice_Name";
            var secondAccount = CreateAccount(secondAccountId, userId, 1, false, now);
            secondAccount.AccountName = "Bob_Name";
            setupContext.Accounts.AddRange(
                firstAccount,
                secondAccount);
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);

        await Assert.ThrowsAsync<AccountNameConflictException>(() => repository.UpdateAsync(
            secondAccountId,
            new AccountUpdateRequest { AccountName = "alice_name", UpdatedBy = userId }));
        var renamed = await repository.UpdateAsync(
            secondAccountId,
            new AccountUpdateRequest { AccountName = "Bob_2", UpdatedBy = userId });
        Assert.Equal("Bob_2", renamed!.AccountName);
        await Assert.ThrowsAsync<ArgumentException>(() => repository.UpdateAsync(
            secondAccountId, new AccountUpdateRequest { AccountName = "Bob-2", UpdatedBy = userId }));
        await Assert.ThrowsAsync<ArgumentException>(() => repository.UpdateAsync(
            secondAccountId, new AccountUpdateRequest { AccountName = "Bo", UpdatedBy = userId }));

        var unchanged = await dbContext.Accounts.SingleAsync(account => account.Uuid == secondAccountId);
        Assert.Equal("Bob_2", unchanged.AccountName);
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
                    highest_level INTEGER NOT NULL,
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
                    highest_level INTEGER NOT NULL,
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
                    highest_level INTEGER NOT NULL,
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

    [Fact]
    public async Task CreateAndCloneAsync_AssignsLowestSlotAndCopiesReidentifiedPlayerState()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
        var sourceUserId = Guid.NewGuid();
        var targetUserId = Guid.NewGuid();
        var sourceAccountId = Guid.NewGuid();
        var sourceLearnedSkillId = Guid.NewGuid();
        var sourceDeletedLearnedSkillId = Guid.NewGuid();
        var equipmentId = Guid.NewGuid();
        var inventoryId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setup = new AstralRecordDbContext(options))
        {
            await setup.Database.EnsureCreatedAsync();
            setup.Users.AddRange(CreateUser(sourceUserId, sourceAccountId, now), new UserEntity
            {
                Uuid = targetUserId, Mcid = "target_user", JoinDate = now, LastJoinDate = now,
                GlobalIp = "127.0.0.2", CreatedAt = now, UpdatedAt = now,
                CreatedBy = targetUserId, UpdatedBy = targetUserId,
            });
            var source = CreateAccount(sourceAccountId, sourceUserId, 3, true, now);
            source.AccountName = "Source_1";
            source.Level = 12;
            source.TotalExperience = 3456;
            source.ClassProgresses.Add(new AccountClassProgressEntity
            {
                AccountId = sourceAccountId, ClassId = "warrior", Level = 7, Experience = 987,
                UpdatedAt = now, UpdatedBy = sourceUserId,
            });
            setup.Accounts.Add(source);
            setup.AccountLearnedSkills.Add(new AccountLearnedSkillEntity
            {
                LearnedSkillId = sourceLearnedSkillId, AccountId = sourceAccountId, SkillId = "slash", Level = 2,
                Version = 3, CreatedAt = now, UpdatedAt = now, CreatedBy = sourceUserId, UpdatedBy = sourceUserId,
            });
            setup.AccountLearnedSkills.Add(new AccountLearnedSkillEntity
            {
                LearnedSkillId = sourceDeletedLearnedSkillId, AccountId = sourceAccountId, SkillId = "forgotten", Level = 1,
                Version = 1, CreatedAt = now, UpdatedAt = now, CreatedBy = sourceUserId, UpdatedBy = sourceUserId,
                IsDeleted = true,
            });
            setup.SkillBindPresets.Add(new SkillBindPresetEntity
            {
                SkillBindPresetId = Guid.NewGuid(), AccountId = sourceAccountId, PresetIndex = 1,
                ActiveSkillSlotsJson = $"[\"{sourceLearnedSkillId:D}\",\"{sourceDeletedLearnedSkillId:D}\"]",
                LeftClickSkillId = sourceDeletedLearnedSkillId.ToString("D"),
                PassiveSkillSlotsJson = $"[\"{sourceLearnedSkillId:D}\",\"{sourceDeletedLearnedSkillId:D}\"]",
                IsUnlocked = true, IsSelected = true,
                Version = 1, CreatedAt = now, UpdatedAt = now, CreatedBy = sourceUserId, UpdatedBy = sourceUserId,
            });
            setup.Inventories.Add(new InventoryEntity
            {
                InventoryId = inventoryId, AccountId = sourceAccountId, InventoryType = "PLAYER", SlotCapacity = 36,
                IsEnabled = true, CreatedAt = now, UpdatedAt = now, CreatedBy = sourceUserId, UpdatedBy = sourceUserId,
            });
            setup.EquipmentInstances.Add(new EquipmentInstanceEntity
            {
                EquipmentInstanceId = equipmentId, AccountId = sourceAccountId, ItemId = "sword", EnhanceLevel = 2,
                CreatedAt = now, UpdatedAt = now, CreatedBy = sourceUserId, UpdatedBy = sourceUserId,
            });
            setup.InventoryEntries.Add(new InventoryEntryEntity
            {
                InventoryEntryId = Guid.NewGuid(), InventoryId = inventoryId, SlotIndex = 0, ItemCategory = "EQUIPMENT",
                InstanceType = "EQUIPMENT", InstanceId = equipmentId, Quantity = 1,
                CreatedAt = now, UpdatedAt = now, CreatedBy = sourceUserId, UpdatedBy = sourceUserId,
            });
            var treeStateId = Guid.NewGuid();
            setup.AccountSkillTreeStates.Add(new AccountSkillTreeStateEntity
            {
                AccountSkillTreeStateId = treeStateId, AccountId = sourceAccountId, Version = 4,
                CreatedAt = now, UpdatedAt = now, CreatedBy = sourceUserId, UpdatedBy = sourceUserId,
            });
            setup.AccountSkillTreeUnlockedNodes.Add(new AccountSkillTreeUnlockedNodeEntity
            {
                AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = treeStateId, NodeId = "node-a",
                CreatedAt = now, UpdatedAt = now, CreatedBy = sourceUserId, UpdatedBy = sourceUserId,
            });
            await setup.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        var cloned = await repository.CloneAsync(sourceAccountId, new AccountCloneRequest
        {
            TargetUserId = targetUserId, TargetSlotIndex = 0, CreatedBy = sourceUserId,
        });

        Assert.NotNull(cloned);
        Assert.NotEqual(sourceAccountId, cloned!.Account.Uuid);
        Assert.Equal(targetUserId, cloned.Account.UserId);
        Assert.Equal(0, cloned.Account.SlotIndex);
        Assert.Equal(12, cloned.Account.Level);
        Assert.Equal("Source_11", cloned.Account.AccountName);
        Assert.Equal(0, cloned.Account.Mode);
        Assert.Equal(cloned.Account.Uuid, (await dbContext.Users.SingleAsync(row => row.Uuid == targetUserId)).AccountId);
        var cloneInventory = await dbContext.Inventories.SingleAsync(row => row.AccountId == cloned.Account.Uuid);
        var cloneEntry = await dbContext.InventoryEntries.SingleAsync(row => row.InventoryId == cloneInventory.InventoryId);
        Assert.NotEqual(equipmentId, cloneEntry.InstanceId);
        Assert.True(await dbContext.EquipmentInstances.AnyAsync(row => row.AccountId == cloned.Account.Uuid));
        var cloneState = await dbContext.AccountSkillTreeStates.SingleAsync(row => row.AccountId == cloned.Account.Uuid);
        Assert.True(await dbContext.AccountSkillTreeUnlockedNodes.AnyAsync(row => row.AccountSkillTreeStateId == cloneState.AccountSkillTreeStateId));
        var cloneLearnedSkillId = await dbContext.AccountLearnedSkills
            .Where(row => row.AccountId == cloned.Account.Uuid).Select(row => row.LearnedSkillId).SingleAsync();
        var clonePreset = await dbContext.SkillBindPresets.SingleAsync(row => row.AccountId == cloned.Account.Uuid);
        Assert.DoesNotContain(sourceLearnedSkillId.ToString("D"), clonePreset.ActiveSkillSlotsJson);
        Assert.DoesNotContain(sourceDeletedLearnedSkillId.ToString("D"), clonePreset.ActiveSkillSlotsJson);
        Assert.Contains(cloneLearnedSkillId.ToString("D"), clonePreset.ActiveSkillSlotsJson);
        Assert.Null(clonePreset.LeftClickSkillId);
        Assert.Contains(cloneLearnedSkillId.ToString("D"), clonePreset.PassiveSkillSlotsJson);
        Assert.DoesNotContain(sourceDeletedLearnedSkillId.ToString("D"), clonePreset.PassiveSkillSlotsJson);

        var autoCreated = await repository.CreateAsync(new AccountCreateRequest
        {
            UserId = targetUserId, AccountName = "x_", Mode = 0, CreatedBy = sourceUserId,
        });
        Assert.Equal(1, autoCreated.SlotIndex);
        Assert.Equal("Player", autoCreated.AccountName);
    }

    [Fact]
    public async Task CloneAsync_RequiresMatchingConfirmationBeforeReplacingSelectedTarget()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
        var sourceUserId = Guid.NewGuid();
        var targetUserId = Guid.NewGuid();
        var sourceId = Guid.NewGuid();
        var targetId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setup = new AstralRecordDbContext(options))
        {
            await setup.Database.EnsureCreatedAsync();
            setup.Users.AddRange(CreateUser(sourceUserId, sourceId, now), CreateUser(targetUserId, targetId, now));
            var source = CreateAccount(sourceId, sourceUserId, 0, true, now);
            source.AccountName = "Source2";
            source.Level = 20;
            var target = CreateAccount(targetId, targetUserId, 4, true, now);
            target.AccountName = "Target2";
            setup.Accounts.AddRange(source, target);
            await setup.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        var conflict = await Assert.ThrowsAsync<AccountCloneConflictException>(() => repository.CloneAsync(sourceId,
            new AccountCloneRequest { TargetUserId = targetUserId, TargetSlotIndex = 4, CreatedBy = sourceUserId }));
        Assert.Equal("TARGET_ACCOUNT_EXISTS", conflict.Code);
        Assert.False((await dbContext.Accounts.SingleAsync(account => account.Uuid == targetId)).IsDeleted);

        var cloned = await repository.CloneAsync(sourceId, new AccountCloneRequest
        {
            TargetUserId = targetUserId, TargetSlotIndex = 4, ExpectedTargetAccountId = targetId,
            Overwrite = true, CreatedBy = sourceUserId,
        });
        Assert.NotNull(cloned);
        Assert.Equal(targetId, cloned!.ReplacedAccountId);
        Assert.True((await dbContext.Accounts.SingleAsync(account => account.Uuid == targetId)).IsDeleted);
        var targetUser = await dbContext.Users.SingleAsync(user => user.Uuid == targetUserId);
        Assert.Equal(cloned.Account.Uuid, targetUser.AccountId);
        Assert.True(cloned.Account.IsActive);
    }

    [Fact]
    public async Task CloneAsync_RejectsSourceWithAnActiveRuntimeSession()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
        var sourceUserId = Guid.NewGuid();
        var targetUserId = Guid.NewGuid();
        var sourceId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setup = new AstralRecordDbContext(options))
        {
            await setup.Database.EnsureCreatedAsync();
            setup.Users.AddRange(CreateUser(sourceUserId, sourceId, now), new UserEntity
            {
                Uuid = targetUserId, Mcid = "target2", JoinDate = now, LastJoinDate = now, GlobalIp = "127.0.0.3",
                CreatedAt = now, UpdatedAt = now, CreatedBy = targetUserId, UpdatedBy = targetUserId,
            });
            setup.Accounts.Add(CreateAccount(sourceId, sourceUserId, 0, true, now));
            setup.SkillTreeAccountSessions.Add(new SkillTreeAccountSessionEntity
            {
                AccountSessionId = Guid.NewGuid(), AccountId = sourceId, ServerId = "rpg-1",
                ServerSessionId = Guid.NewGuid(), DefinitionGenerationId = "test", LeaseTokenHash = "hash",
                CreatedAtUtc = now, ExpiresAtUtc = now.AddMinutes(1), Closed = false,
            });
            await setup.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        var conflict = await Assert.ThrowsAsync<AccountCloneConflictException>(() => repository.CloneAsync(sourceId,
            new AccountCloneRequest { TargetUserId = targetUserId, TargetSlotIndex = 0, CreatedBy = sourceUserId }));
        Assert.Equal("SOURCE_ACCOUNT_SESSION_ACTIVE", conflict.Code);
        Assert.Empty(await dbContext.Accounts.Where(account => account.UserId == targetUserId).ToListAsync());
    }

    [Fact]
    public async Task CloneAsync_CommitResultUnknownReturnsTheCommittedClone()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var interceptor = new CommitResultUnknownInterceptor();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new CommitResultUnknownRetryingExecutionStrategy(dependencies)))
            .AddInterceptors(interceptor)
            .Options;
        var sourceUserId = Guid.NewGuid();
        var targetUserId = Guid.NewGuid();
        var sourceId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await using (var setup = new AstralRecordDbContext(options))
        {
            await setup.Database.EnsureCreatedAsync();
            setup.Users.AddRange(CreateUser(sourceUserId, sourceId, now), new UserEntity
            {
                Uuid = targetUserId, Mcid = "target3", JoinDate = now, LastJoinDate = now, GlobalIp = "127.0.0.4",
                CreatedAt = now, UpdatedAt = now, CreatedBy = targetUserId, UpdatedBy = targetUserId,
            });
            setup.Accounts.Add(CreateAccount(sourceId, sourceUserId, 0, true, now));
            await setup.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new AccountRepository(dbContext);
        interceptor.Arm();
        var cloned = await repository.CloneAsync(sourceId, new AccountCloneRequest
        {
            TargetUserId = targetUserId, TargetSlotIndex = 0, CreatedBy = sourceUserId,
        });

        Assert.NotNull(cloned);
        Assert.True(interceptor.WasThrown);
        Assert.Single(await dbContext.Accounts.Where(account => account.UserId == targetUserId && !account.IsDeleted).ToListAsync());
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
