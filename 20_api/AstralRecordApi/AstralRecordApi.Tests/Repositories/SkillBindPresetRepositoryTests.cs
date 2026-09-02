using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class SkillBindPresetRepositoryTests
{
    [Fact]
    public async Task GetByAccountIdAsync_UsesWeaponNormalAttackForLegacyNullLeftClickBinding()
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
            await setupContext.SkillBindPresets.AddAsync(new SkillBindPresetEntity
            {
                SkillBindPresetId = Guid.NewGuid(),
                AccountId = accountId,
                PresetIndex = 1,
                ActiveSkillSlotsJson = "[]",
                LeftClickSkillId = null,
                PassiveSkillSlotsJson = "[]",
                IsUnlocked = true,
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new SkillBindPresetRepository(dbContext);

        var presets = await repository.GetByAccountIdAsync(accountId);

        Assert.Equal(6, presets.Count);
        Assert.All(presets, preset =>
        {
            Assert.Equal(6, preset.ActiveSkillSlots.Count);
            Assert.Equal(9, preset.PassiveSkillSlots.Count);
        });
        Assert.All(presets.Where(preset => preset.PresetIndex <= 3), preset => Assert.True(preset.IsUnlocked));
        Assert.All(presets.Where(preset => preset.PresetIndex > 3), preset => Assert.False(preset.IsUnlocked));
        Assert.Equal("__weapon_normal_attack__", presets.Single(x => x.PresetIndex == 1).LeftClickSkillId);
    }

    [Fact]
    public async Task SelectAsync_SwitchesExistingPresetsUnderSelectedUniqueIndex()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var saveChangesCounter = new SaveChangesCounterInterceptor();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection, sqlite => sqlite.ExecutionStrategy(
                dependencies => new RetryingTestExecutionStrategy(dependencies)))
            .AddInterceptors(saveChangesCounter)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        await dbContext.Database.ExecuteSqlInterpolatedAsync(
            $"INSERT INTO account (uuid, is_deleted) VALUES ({accountId}, {false})");

        var repository = new SkillBindPresetRepository(dbContext);

        Assert.True(await repository.SelectAsync(accountId, 1, new SkillBindPresetSelectionRequest
        {
            PresetIndex = 1,
            UpdatedBy = userId,
        }));
        Assert.True(await repository.SelectAsync(accountId, 2, new SkillBindPresetSelectionRequest
        {
            PresetIndex = 2,
            UpdatedBy = userId,
        }));
        saveChangesCounter.Reset();
        Assert.True(await repository.SelectAsync(accountId, 1, new SkillBindPresetSelectionRequest
        {
            PresetIndex = 1,
            UpdatedBy = userId,
        }));

        var reloaded = await new SkillBindPresetRepository(dbContext).GetByAccountIdAsync(accountId);

        Assert.Equal(1, reloaded.Single(preset => preset.IsSelected).PresetIndex);
        Assert.All(reloaded.Where(preset => preset.PresetIndex != 1), preset => Assert.False(preset.IsSelected));
        Assert.Equal(2, saveChangesCounter.SavingChangesAsyncCount);
    }

    [Fact]
    public async Task GetByAccountIdAsync_NormalizesLegacySkillIdsToTheOldestOwnedLearnedSkill()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var firstLearnedSkillId = Guid.NewGuid();
        var secondLearnedSkillId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        dbContext.AccountLearnedSkills.AddRange(
            new AccountLearnedSkillEntity
            {
                LearnedSkillId = firstLearnedSkillId,
                AccountId = accountId,
                SkillId = "adventurer_smash",
                Level = 1,
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
            },
            new AccountLearnedSkillEntity
            {
                LearnedSkillId = secondLearnedSkillId,
                AccountId = accountId,
                SkillId = "adventurer_smash",
                Level = 1,
                Version = 1,
                CreatedAt = now.AddMinutes(1),
                UpdatedAt = now.AddMinutes(1),
                CreatedBy = userId,
                UpdatedBy = userId,
            });
        dbContext.SkillBindPresets.Add(new SkillBindPresetEntity
        {
            SkillBindPresetId = Guid.NewGuid(),
            AccountId = accountId,
            PresetIndex = 1,
            ActiveSkillSlotsJson = "[\"adventurer_smash\",\"00000000-0000-0000-0000-000000000000\"]",
            LeftClickSkillId = "adventurer_smash",
            PassiveSkillSlotsJson = "[\"unknown_legacy_skill\"]",
            IsUnlocked = true,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = userId,
            UpdatedBy = userId,
        });
        await dbContext.SaveChangesAsync();

        var preset = (await new SkillBindPresetRepository(dbContext).GetByAccountIdAsync(accountId))
            .Single(candidate => candidate.PresetIndex == 1);

        Assert.Equal(firstLearnedSkillId.ToString(), preset.ActiveSkillSlots[0]);
        Assert.Null(preset.ActiveSkillSlots[1]);
        Assert.Equal(firstLearnedSkillId.ToString(), preset.LeftClickSkillId);
        Assert.Null(preset.PassiveSkillSlots[0]);
    }

    [Fact]
    public async Task UpsertAsync_PreservesNullLeftClickBindingAsUnbound()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        await dbContext.Database.ExecuteSqlInterpolatedAsync(
            $"INSERT INTO account (uuid, is_deleted) VALUES ({accountId}, {false})");
        var repository = new SkillBindPresetRepository(dbContext);

        var saved = await repository.UpsertAsync(accountId, 1, new SkillBindPresetUpsertRequest
        {
            LeftClickSkillId = null,
            UpdatedBy = userId,
        });
        var loaded = await repository.GetByAccountIdAsync(accountId);

        Assert.NotNull(saved);
        Assert.Null(saved.LeftClickSkillId);
        Assert.Null(loaded.Single(x => x.PresetIndex == 1).LeftClickSkillId);
    }

    /// <summary>
    /// 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
    /// 検証契約: 武器通常攻撃の予約バインドはアクションスロットへ保存でき、所持済みスキル個体としての検証を要求しない。
    /// </summary>
    [Fact]
    public async Task UpsertAsync_AllowsWeaponNormalAttackInActiveSlots()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        await dbContext.Database.ExecuteSqlInterpolatedAsync(
            $"INSERT INTO account (uuid, is_deleted) VALUES ({accountId}, {false})");

        var saved = await new SkillBindPresetRepository(dbContext).UpsertAsync(accountId, 1,
            new SkillBindPresetUpsertRequest
            {
                ActiveSkillSlots = ["__weapon_normal_attack__"],
                LeftClickSkillId = "__weapon_normal_attack__",
                UpdatedBy = userId,
            });

        Assert.NotNull(saved);
        Assert.Equal("__weapon_normal_attack__", saved.ActiveSkillSlots[0]);
        Assert.Equal("__weapon_normal_attack__", saved.LeftClickSkillId);
        var reloaded = (await new SkillBindPresetRepository(dbContext).GetByAccountIdAsync(accountId))
            .Single(preset => preset.PresetIndex == 1);
        Assert.Equal("__weapon_normal_attack__", reloaded.ActiveSkillSlots[0]);
    }

    [Fact]
    public async Task UpsertAsync_PreservesAllNineOwnedPassiveBindingsAndRejectsUnownedIds()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await dbContext.Database.ExecuteSqlInterpolatedAsync(
            $"INSERT INTO account (uuid, is_deleted) VALUES ({accountId}, {false})");
        var learned = Enumerable.Range(0, 9).Select(_ => new AccountLearnedSkillEntity
        {
            LearnedSkillId = Guid.NewGuid(),
            AccountId = accountId,
            SkillId = "adventurer_meditation",
            Level = 1,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = userId,
            UpdatedBy = userId,
        }).ToArray();
        dbContext.AccountLearnedSkills.AddRange(learned);
        await dbContext.SaveChangesAsync();
        var repository = new SkillBindPresetRepository(dbContext);

        var saved = await repository.UpsertAsync(accountId, 1, new SkillBindPresetUpsertRequest
        {
            PassiveSkillSlots = learned.Select(skill => (string?)skill.LearnedSkillId.ToString()).ToArray(),
            UpdatedBy = userId,
        });
        var rejected = await repository.UpsertAsync(accountId, 2, new SkillBindPresetUpsertRequest
        {
            ActiveSkillSlots = [Guid.NewGuid().ToString()],
            UpdatedBy = userId,
        });

        Assert.NotNull(saved);
        Assert.Equal(9, saved.PassiveSkillSlots.Count);
        Assert.Equal(learned.Select(skill => skill.LearnedSkillId.ToString()), saved.PassiveSkillSlots);
        Assert.Null(rejected);
        var reloaded = (await repository.GetByAccountIdAsync(accountId)).Single(preset => preset.PresetIndex == 1);
        Assert.Equal(saved.PassiveSkillSlots, reloaded.PassiveSkillSlots);
    }

    [Fact]
    public async Task UpsertAsync_RejectsDuplicatePassiveBindingIds()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var learnedSkillId = Guid.NewGuid();
        await dbContext.Database.ExecuteSqlInterpolatedAsync(
            $"INSERT INTO account (uuid, is_deleted) VALUES ({accountId}, {false})");
        dbContext.AccountLearnedSkills.Add(new AccountLearnedSkillEntity
        {
            LearnedSkillId = learnedSkillId,
            AccountId = accountId,
            SkillId = "adventurer_meditation",
            Level = 1,
            Version = 1,
            CreatedAt = DateTime.UtcNow,
            UpdatedAt = DateTime.UtcNow,
            CreatedBy = userId,
            UpdatedBy = userId,
        });
        await dbContext.SaveChangesAsync();

        var saved = await new SkillBindPresetRepository(dbContext).UpsertAsync(accountId, 1,
            new SkillBindPresetUpsertRequest
            {
                PassiveSkillSlots = [learnedSkillId.ToString(), learnedSkillId.ToString()],
                UpdatedBy = userId,
            });

        Assert.Null(saved);
    }

    [Fact]
    public async Task UpsertAsync_NormalizesNonCanonicalLearnedSkillIds()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(dbContext);
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var learnedSkillId = Guid.NewGuid();
        await dbContext.Database.ExecuteSqlInterpolatedAsync(
            $"INSERT INTO account (uuid, is_deleted) VALUES ({accountId}, {false})");
        dbContext.AccountLearnedSkills.Add(new AccountLearnedSkillEntity
        {
            LearnedSkillId = learnedSkillId,
            AccountId = accountId,
            SkillId = "adventurer_meditation",
            Level = 1,
            Version = 1,
            CreatedAt = DateTime.UtcNow,
            UpdatedAt = DateTime.UtcNow,
            CreatedBy = userId,
            UpdatedBy = userId,
        });
        await dbContext.SaveChangesAsync();
        var repository = new SkillBindPresetRepository(dbContext);

        var noHyphen = await repository.UpsertAsync(accountId, 1, new SkillBindPresetUpsertRequest
        {
            PassiveSkillSlots = [learnedSkillId.ToString("N")],
            UpdatedBy = userId,
        });
        var braces = await repository.UpsertAsync(accountId, 2, new SkillBindPresetUpsertRequest
        {
            PassiveSkillSlots = [$"{{{learnedSkillId}}}"],
            UpdatedBy = userId,
        });

        Assert.NotNull(noHyphen);
        Assert.Equal(learnedSkillId.ToString(), noHyphen.PassiveSkillSlots[0]);
        Assert.NotNull(braces);
        Assert.Equal(learnedSkillId.ToString(), braces.PassiveSkillSlots[0]);
    }

    [Fact]
    public async Task UpsertAsync_AllowsRequiredPassiveAndRejectsAlwaysOnPassive()
    {
        await using var playerConnection = new SqliteConnection("Data Source=:memory:");
        await using var masterConnection = new SqliteConnection("Data Source=:memory:");
        await playerConnection.OpenAsync();
        await masterConnection.OpenAsync();
        var playerOptions = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(playerConnection)
            .Options;
        var masterOptions = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(masterConnection)
            .Options;
        await using var dbContext = new AstralRecordDbContext(playerOptions);
        await using var masterDataDbContext = new MasterDataDbContext(masterOptions);
        await CreateSchemaAsync(dbContext);
        await MasterDataTestSeed.CreateSchemaAsync(masterDataDbContext);

        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        var requiredPassiveId = Guid.NewGuid();
        var alwaysOnPassiveId = Guid.NewGuid();
        await dbContext.Database.ExecuteSqlInterpolatedAsync(
            $"INSERT INTO account (uuid, is_deleted) VALUES ({accountId}, {false})");
        dbContext.AccountLearnedSkills.AddRange(
            new AccountLearnedSkillEntity
            {
                LearnedSkillId = requiredPassiveId,
                AccountId = accountId,
                SkillId = "adventurer_meditation",
                Level = 1,
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
            },
            new AccountLearnedSkillEntity
            {
                LearnedSkillId = alwaysOnPassiveId,
                AccountId = accountId,
                SkillId = "adventurer_always_on",
                Level = 1,
                Version = 1,
                CreatedAt = now.AddMinutes(1),
                UpdatedAt = now.AddMinutes(1),
                CreatedBy = userId,
                UpdatedBy = userId,
            });
        await dbContext.SaveChangesAsync();
        await MasterDataTestSeed.SeedInlinePayloadAsync(
            masterDataDbContext, MasterDataTestFixtures.AdventurerMeditation, "skill", null);
        await MasterDataTestSeed.SeedInlinePayloadAsync(
            masterDataDbContext, MasterDataTestFixtures.AdventurerAlwaysOn, "skill", null);

        var repository = new SkillBindPresetRepository(dbContext, masterDataDbContext);
        var saved = await repository.UpsertAsync(accountId, 1, new SkillBindPresetUpsertRequest
        {
            PassiveSkillSlots = [requiredPassiveId.ToString()],
            UpdatedBy = userId,
        });
        var rejected = await repository.UpsertAsync(accountId, 2, new SkillBindPresetUpsertRequest
        {
            PassiveSkillSlots = [alwaysOnPassiveId.ToString()],
            UpdatedBy = userId,
        });

        Assert.NotNull(saved);
        Assert.Null(rejected);
    }

    private static async Task CreateSchemaAsync(AstralRecordDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE account (
                uuid TEXT NOT NULL PRIMARY KEY,
                is_deleted INTEGER NOT NULL
            );

            CREATE TABLE skill_bind_preset (
                skill_bind_preset_id TEXT NOT NULL PRIMARY KEY,
                account_id TEXT NOT NULL,
                preset_index INTEGER NOT NULL,
                active_skill_slots_json TEXT NOT NULL,
                left_click_skill_id TEXT NULL,
                passive_skill_slots_json TEXT NOT NULL,
                is_unlocked INTEGER NOT NULL,
                is_selected INTEGER NOT NULL,
                version INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE UNIQUE INDEX UX_skill_bind_preset_account_selected
                ON skill_bind_preset (account_id)
                WHERE is_deleted = 0 AND is_selected = 1;

            CREATE TABLE account_learned_skill (
                learned_skill_id TEXT NOT NULL PRIMARY KEY,
                account_id TEXT NOT NULL,
                skill_id TEXT NOT NULL,
                level INTEGER NOT NULL,
                version INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");
    }

    private sealed class RetryingTestExecutionStrategy(ExecutionStrategyDependencies dependencies)
        : ExecutionStrategy(dependencies, maxRetryCount: 1, maxRetryDelay: TimeSpan.Zero)
    {
        protected override bool ShouldRetryOn(Exception exception) => false;
    }

    private sealed class SaveChangesCounterInterceptor : SaveChangesInterceptor
    {
        public int SavingChangesAsyncCount { get; private set; }

        public override ValueTask<InterceptionResult<int>> SavingChangesAsync(
            DbContextEventData eventData,
            InterceptionResult<int> result,
            CancellationToken cancellationToken = default)
        {
            SavingChangesAsyncCount++;
            return base.SavingChangesAsync(eventData, result, cancellationToken);
        }

        public void Reset() => SavingChangesAsyncCount = 0;
    }
}
