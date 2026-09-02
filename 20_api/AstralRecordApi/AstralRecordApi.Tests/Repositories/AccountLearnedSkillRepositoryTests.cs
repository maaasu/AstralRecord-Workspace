using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class AccountLearnedSkillRepositoryTests
{
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: 無条件習得では、同一skillIdを別UUIDの個体として何個でも習得できる。
    /// </summary>
    [Fact]
    public async Task LearnAsync_CreatesIndependentDuplicateInstances()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var repository = new AccountLearnedSkillRepository(fixture.PlayerDb, fixture.MasterDb);

        var first = await repository.LearnAsync(accountId, new AccountLearnedSkillLearnRequest
        {
            SkillId = "adventurer_smash",
            UpdatedBy = accountId,
        });
        var second = await repository.LearnAsync(accountId, new AccountLearnedSkillLearnRequest
        {
            SkillId = "adventurer_smash",
            UpdatedBy = accountId,
        });

        Assert.True(first.Succeeded);
        Assert.True(second.Succeeded);
        Assert.NotEqual(first.Skill!.LearnedSkillId, second.Skill!.LearnedSkillId);
        Assert.All([first.Skill, second.Skill], learned =>
        {
            Assert.Equal("adventurer_smash", learned.SkillId);
            Assert.Equal(1, learned.Level);
        });
        Assert.Equal(2, await fixture.PlayerDb.AccountLearnedSkills.CountAsync(skill => !skill.IsDeleted));
        Assert.Empty(await fixture.PlayerDb.InventoryEntries.ToListAsync());
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: required items は複数stackから原子的に消費し、不足時は習得も消費もしない。
    /// </summary>
    [Fact]
    public async Task LearnAsync_ConsumesRequiredItemsAcrossMultipleStacksAndRejectsShortage()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        var master = await fixture.MasterDb.Entries.SingleAsync(entry => entry.MasterId == "adventurer_smash");
        master.PayloadJson = master.PayloadJson.Replace(
            "\"maxLevel\":5,",
            "\"maxLevel\":5,\"learnRequiredItems\":[{\"itemId\":\"skill_gem_raw\",\"amount\":3}],");
        await fixture.MasterDb.SaveChangesAsync();
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var first = await fixture.AddInventoryEntryAsync(accountId, "material", "skill_gem_raw", 1);
        var second = await fixture.AddInventoryEntryAsync(accountId, "material", "skill_gem_raw", 2);

        var learned = await new AccountLearnedSkillRepository(fixture.PlayerDb, fixture.MasterDb)
            .LearnAsync(accountId, new AccountLearnedSkillLearnRequest { SkillId = "adventurer_smash", UpdatedBy = accountId });

        Assert.True(learned.Succeeded);
        Assert.True((await fixture.PlayerDb.InventoryEntries.SingleAsync(entry => entry.InventoryEntryId == first)).IsDeleted);
        Assert.True((await fixture.PlayerDb.InventoryEntries.SingleAsync(entry => entry.InventoryEntryId == second)).IsDeleted);

        var rejected = await new AccountLearnedSkillRepository(fixture.PlayerDb, fixture.MasterDb)
            .LearnAsync(accountId, new AccountLearnedSkillLearnRequest { SkillId = "adventurer_smash", UpdatedBy = accountId });
        Assert.Equal(AccountLearnedSkillMutationFailure.InvalidMaterial, rejected.Failure);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様/11_3.03-習得済みスキル.md
    /// 検証契約: required items は通常アイテム共通消費順でBAGのslot降順から使い、HOTBARよりBAGを優先し、STORAGEとinstance itemを除外する。
    /// </summary>
    [Fact]
    public async Task LearnAsync_UsesCommonNormalItemConsumptionOrder()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        var master = await fixture.MasterDb.Entries.SingleAsync(entry => entry.MasterId == "adventurer_smash");
        master.PayloadJson = master.PayloadJson.Replace(
            "\"maxLevel\":5,",
            "\"maxLevel\":5,\"learnRequiredItems\":[{\"itemId\":\"skill_gem_raw\",\"amount\":1}],");
        await fixture.MasterDb.SaveChangesAsync();
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var storage = await fixture.AddInventoryEntryAsync(
            accountId, "material", "skill_gem_raw", 1, "STORAGE", 20,
            Guid.Parse("00000000-0000-0000-0000-000000000001"));
        var hotbar = await fixture.AddInventoryEntryAsync(
            accountId, "material", "skill_gem_raw", 1, "HOTBAR", 8,
            Guid.Parse("00000000-0000-0000-0000-000000000002"));
        var instance = await fixture.AddInventoryEntryAsync(
            accountId, "material", "skill_gem_raw", 1, "BAG", 9,
            Guid.Parse("00000000-0000-0000-0000-000000000003"), "ITEM_INSTANCE", Guid.NewGuid());
        var bagLowSlot = await fixture.AddInventoryEntryAsync(
            accountId, "material", "skill_gem_raw", 1, "BAG", 1,
            Guid.Parse("00000000-0000-0000-0000-000000000004"));
        var bagHighSlot = await fixture.AddInventoryEntryAsync(
            accountId, "material", "skill_gem_raw", 2, "BAG", 5,
            Guid.Parse("00000000-0000-0000-0000-000000000005"));

        var learned = await new AccountLearnedSkillRepository(fixture.PlayerDb, fixture.MasterDb)
            .LearnAsync(accountId, new AccountLearnedSkillLearnRequest
            {
                SkillId = "adventurer_smash",
                UpdatedBy = accountId,
            });

        Assert.True(learned.Succeeded);
        var consumedEntry = await fixture.PlayerDb.InventoryEntries.SingleAsync(
            entry => entry.InventoryEntryId == bagHighSlot);
        Assert.False(consumedEntry.IsDeleted);
        Assert.Equal(1, consumedEntry.Quantity);
        Assert.Collection(
            learned.ConsumedMaterials!,
            material =>
            {
                Assert.Equal(bagHighSlot, material.InventoryEntryId);
                Assert.Equal(1, material.ConsumedAmount);
            });
        Assert.All([storage, hotbar, instance, bagLowSlot], entryId =>
        {
            var entry = fixture.PlayerDb.InventoryEntries.Single(candidate => candidate.InventoryEntryId == entryId);
            Assert.False(entry.IsDeleted);
            Assert.Equal(1, entry.Quantity);
        });
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: 指定個体だけを1レベル上げ、同じequipGroupIdのシジルを重複装着しない。
    /// </summary>
    [Fact]
    public async Task UpgradeAndAttachSigil_UseSelectedInstanceAndRejectDuplicateGroup()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        await fixture.SeedMasterAsync("cooldown_sigil", "item", "sigil");
        await fixture.SeedMasterAsync("cooldown_sigil_ii", "item", "sigil");
        await fixture.SeedMasterAsync("bragi_orb", "item", "orb");
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var firstSigil = await fixture.AddInventoryEntryAsync(accountId, "sigil", "cooldown_sigil", 2);
        var secondSigil = await fixture.AddInventoryEntryAsync(accountId, "sigil", "cooldown_sigil_ii", 1);
        var bragiOrb = await fixture.AddInventoryEntryAsync(accountId, "orb", "bragi_orb", 2);
        AccountLearnedSkillResponse learned;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            learned = (await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .LearnAsync(accountId, new AccountLearnedSkillLearnRequest
                {
                    SkillId = "adventurer_smash",
                    UpdatedBy = accountId,
                })).Skill!;
        }
        AccountLearnedSkillMutationResult upgraded;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            upgraded = await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .LevelUpAsync(accountId, learned.LearnedSkillId, new AccountLearnedSkillLevelUpRequest
                {
                    UpdatedBy = accountId,
                });
        }
        AccountLearnedSkillMutationResult attached;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            attached = await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .AttachSigilAsync(accountId, learned.LearnedSkillId,
                new AccountLearnedSkillAttachSigilRequest
                {
                    SigilId = "cooldown_sigil",
                    SigilInventoryEntryId = firstSigil,
                    OrbInventoryEntryId = bragiOrb,
                    UpdatedBy = accountId,
                });
        }
        AccountLearnedSkillMutationResult duplicate;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            duplicate = await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .AttachSigilAsync(accountId, learned.LearnedSkillId,
                new AccountLearnedSkillAttachSigilRequest
                {
                    SigilId = "cooldown_sigil_ii",
                    SigilInventoryEntryId = secondSigil,
                    OrbInventoryEntryId = bragiOrb,
                    UpdatedBy = accountId,
                });
        }

        Assert.True(upgraded.Succeeded);
        Assert.Equal(2, upgraded.Skill!.Level);
        Assert.True(attached.Succeeded);
        Assert.Single(attached.Skill!.Sigils);
        Assert.Equal(AccountLearnedSkillMutationFailure.DuplicateSigilGroup, duplicate.Failure);
        Assert.Equal(1, (await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == firstSigil)).Quantity);
        Assert.False((await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == secondSigil)).IsDeleted);
        Assert.Equal(1, (await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == bragiOrb)).Quantity);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: 装着済みシジルの指定行だけを論理削除し、同一 transaction で BAG へ1個返却する。
    /// </summary>
    [Fact]
    public async Task DetachSigilAsync_DeletesAttachmentAndReturnsSigilToBag()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        await fixture.SeedMasterAsync("cooldown_sigil", "item", "sigil");
        await fixture.SeedMasterAsync("bragi_orb", "item", "orb");
        await fixture.SeedMasterAsync("mimir_orb", "item", "orb");
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var sigilEntryId = await fixture.AddInventoryEntryAsync(accountId, "sigil", "cooldown_sigil", 1);
        var bragiOrb = await fixture.AddInventoryEntryAsync(accountId, "orb", "bragi_orb", 2);
        var mimirOrb = await fixture.AddInventoryEntryAsync(accountId, "orb", "mimir_orb", 2);

        AccountLearnedSkillResponse learned;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            learned = (await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .LearnAsync(accountId, new AccountLearnedSkillLearnRequest
                {
                    SkillId = "adventurer_smash",
                    UpdatedBy = accountId,
                })).Skill!;
        }
        AccountLearnedSkillResponse attached;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            attached = (await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .AttachSigilAsync(accountId, learned.LearnedSkillId, new AccountLearnedSkillAttachSigilRequest
                {
                    SigilId = "cooldown_sigil",
                    SigilInventoryEntryId = sigilEntryId,
                    OrbInventoryEntryId = bragiOrb,
                    UpdatedBy = accountId,
                })).Skill!;
        }

        AccountLearnedSkillMutationResult detached;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            detached = await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .DetachSigilAsync(
                    accountId,
                    learned.LearnedSkillId,
                    attached.Sigils.Single().LearnedSkillSigilId,
                    new AccountLearnedSkillDetachSigilRequest
                    {
                        OrbInventoryEntryId = mimirOrb,
                        UpdatedBy = accountId,
                    });
        }

        Assert.True(detached.Succeeded);
        Assert.Empty(detached.Skill!.Sigils);
        Assert.NotNull(detached.ReturnedInventoryEntryId);
        Assert.True((await fixture.PlayerDb.AccountLearnedSkillSigils.AsNoTracking()
            .SingleAsync(sigil => sigil.LearnedSkillSigilId == attached.Sigils.Single().LearnedSkillSigilId)).IsDeleted);
        var returned = await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == detached.ReturnedInventoryEntryId);
        Assert.False(returned.IsDeleted);
        Assert.Equal("sigil", returned.ItemCategory);
        Assert.Equal("cooldown_sigil", returned.ItemId);
        Assert.Equal(1, returned.Quantity);
        Assert.Equal(1, (await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == bragiOrb)).Quantity);
        Assert.Equal(1, (await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == mimirOrb)).Quantity);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: 許可されていないシジルは消費せず、素材選択段階の拒否と同じ理由で API も拒否する。
    /// </summary>
    [Fact]
    public async Task AttachSigilAsync_RejectsUnsupportedSigilWithoutConsumingIt()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        await fixture.SeedMasterAsync("homing_fireball_sigil", "item", "sigil");
        await fixture.SeedMasterAsync("bragi_orb", "item", "orb");
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var sigilEntryId = await fixture.AddInventoryEntryAsync(
            accountId, "sigil", "homing_fireball_sigil", 1);
        var bragiOrb = await fixture.AddInventoryEntryAsync(accountId, "orb", "bragi_orb", 1);

        AccountLearnedSkillResponse learned;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            learned = (await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .LearnAsync(accountId, new AccountLearnedSkillLearnRequest
                {
                    SkillId = "adventurer_smash",
                    UpdatedBy = accountId,
                })).Skill!;
        }
        AccountLearnedSkillMutationResult rejected;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            rejected = await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .AttachSigilAsync(accountId, learned.LearnedSkillId, new AccountLearnedSkillAttachSigilRequest
                {
                    SigilId = "homing_fireball_sigil",
                    SigilInventoryEntryId = sigilEntryId,
                    OrbInventoryEntryId = bragiOrb,
                    UpdatedBy = accountId,
                });
        }

        Assert.False(rejected.Succeeded);
        Assert.Equal(AccountLearnedSkillMutationFailure.SigilNotAllowed, rejected.Failure);
        var sigilEntry = await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == sigilEntryId);
        Assert.Equal(1, sigilEntry.Quantity);
        Assert.False(sigilEntry.IsDeleted);
        var orbEntry = await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == bragiOrb);
        Assert.Equal(1, orbEntry.Quantity);
        Assert.False(orbEntry.IsDeleted);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: シジル装着では SIGIL_ATTACH 以外のオーブを受け付けず、シジルとオーブを消費しない。
    /// </summary>
    [Fact]
    public async Task AttachSigilAsync_RejectsWrongOrbWithoutConsumingMaterials()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        await fixture.SeedMasterAsync("cooldown_sigil", "item", "sigil");
        await fixture.SeedMasterAsync("mimir_orb", "item", "orb");
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var sigilEntryId = await fixture.AddInventoryEntryAsync(accountId, "sigil", "cooldown_sigil", 1);
        var mimirOrb = await fixture.AddInventoryEntryAsync(accountId, "orb", "mimir_orb", 1);

        AccountLearnedSkillResponse learned;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            learned = (await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .LearnAsync(accountId, new AccountLearnedSkillLearnRequest
                {
                    SkillId = "adventurer_smash",
                    UpdatedBy = accountId,
                })).Skill!;
        }

        AccountLearnedSkillMutationResult rejected;
        await using (var requestDb = fixture.CreatePlayerDb())
        {
            rejected = await new AccountLearnedSkillRepository(requestDb, fixture.MasterDb)
                .AttachSigilAsync(accountId, learned.LearnedSkillId, new AccountLearnedSkillAttachSigilRequest
                {
                    SigilId = "cooldown_sigil",
                    SigilInventoryEntryId = sigilEntryId,
                    OrbInventoryEntryId = mimirOrb,
                    UpdatedBy = accountId,
                });
        }

        Assert.False(rejected.Succeeded);
        Assert.Equal(AccountLearnedSkillMutationFailure.InvalidMaterial, rejected.Failure);
        var sigilEntry = await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == sigilEntryId);
        Assert.Equal(1, sigilEntry.Quantity);
        Assert.False(sigilEntry.IsDeleted);
        var orbEntry = await fixture.PlayerDb.InventoryEntries.AsNoTracking()
            .SingleAsync(entry => entry.InventoryEntryId == mimirOrb);
        Assert.Equal(1, orbEntry.Quantity);
        Assert.False(orbEntry.IsDeleted);
    }

    /// <summary>
    /// 設計入力: 00_docs/40_Database設計書/table-definitions/AstralRecord/dbo.player_mail_delivery.md
    /// 検証契約: ロード時にマスタ上無効な装着シジルを削除し、同じシジルを動的お詫びメールで返却する。
    /// </summary>
    [Fact]
    public async Task GetByAccountIdAsync_ReconcilesInvalidSigilAndCreatesCompensationMail()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        await fixture.SeedMasterAsync("homing_fireball_sigil", "item", "sigil");
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId, userId);
        var now = DateTime.UtcNow;
        var learned = new AccountLearnedSkillEntity
        {
            LearnedSkillId = Guid.NewGuid(),
            AccountId = accountId,
            SkillId = "adventurer_smash",
            Level = 1,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        };
        learned.Sigils.Add(new AccountLearnedSkillSigilEntity
        {
            LearnedSkillSigilId = Guid.NewGuid(),
            LearnedSkillId = learned.LearnedSkillId,
            SigilId = "homing_fireball_sigil",
            EquipGroupId = "fireball_trajectory",
            SlotIndex = 0,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        });
        fixture.PlayerDb.AccountLearnedSkills.Add(learned);
        await fixture.PlayerDb.SaveChangesAsync();
        var sigilMaster = await fixture.MasterDb.Entries
            .SingleAsync(entry => entry.MasterId == "homing_fireball_sigil");
        sigilMaster.IsDeleted = true;
        await fixture.MasterDb.SaveChangesAsync();

        var repository = new AccountLearnedSkillRepository(fixture.PlayerDb, fixture.MasterDb);
        var result = await repository.GetByAccountIdAsync(accountId);

        Assert.Empty(Assert.Single(result).Sigils);
        Assert.True(await fixture.PlayerDb.AccountLearnedSkillSigils
            .AnyAsync(sigil => sigil.SigilId == "homing_fireball_sigil" && sigil.IsDeleted));
        var delivery = await fixture.PlayerDb.PlayerMailDeliveries.AsNoTracking().SingleAsync();
        var mail = JsonSerializer.Deserialize<MailResponse>(delivery.PayloadJson,
            new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        Assert.NotNull(mail);
        var reward = Assert.Single(mail.Rewards);
        Assert.Equal("homing_fireball_sigil", reward.ItemId);
        Assert.Equal("sigil", reward.Category);
        Assert.Equal(1, reward.Amount);
        Assert.NotNull(new ItemRepository(fixture.MasterDb).GetById("homing_fireball_sigil"));
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: skill master削除時は習得個体とそのbindをロード時に無効化する。
    /// </summary>
    [Fact]
    public async Task GetByAccountIdAsync_RemovesDeletedSkillLearnedInstanceAndBindings()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var now = DateTime.UtcNow;
        var learnedSkillId = Guid.NewGuid();
        fixture.PlayerDb.AccountLearnedSkills.Add(new AccountLearnedSkillEntity
        {
            LearnedSkillId = learnedSkillId,
            AccountId = accountId,
            SkillId = "adventurer_smash",
            Level = 3,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        });
        fixture.PlayerDb.SkillBindPresets.Add(new SkillBindPresetEntity
        {
            SkillBindPresetId = Guid.NewGuid(),
            AccountId = accountId,
            PresetIndex = 1,
            ActiveSkillSlotsJson = JsonSerializer.Serialize(new string?[] { learnedSkillId.ToString() }),
            LeftClickSkillId = learnedSkillId.ToString(),
            PassiveSkillSlotsJson = JsonSerializer.Serialize(new string?[] { null, learnedSkillId.ToString() }),
            IsUnlocked = true,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        });
        await fixture.PlayerDb.SaveChangesAsync();
        var skillMaster = await fixture.MasterDb.Entries.SingleAsync(entry => entry.MasterId == "adventurer_smash");
        skillMaster.IsDeleted = true;
        await fixture.MasterDb.SaveChangesAsync();

        var result = await new AccountLearnedSkillRepository(fixture.PlayerDb, fixture.MasterDb)
            .GetByAccountIdAsync(accountId);

        Assert.Empty(result);
        Assert.True((await fixture.PlayerDb.AccountLearnedSkills.AsNoTracking()
            .SingleAsync(skill => skill.LearnedSkillId == learnedSkillId)).IsDeleted);
        var preset = await fixture.PlayerDb.SkillBindPresets.AsNoTracking().SingleAsync();
        Assert.DoesNotContain(learnedSkillId.ToString(), preset.ActiveSkillSlotsJson, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain(learnedSkillId.ToString(), preset.PassiveSkillSlotsJson, StringComparison.OrdinalIgnoreCase);
        Assert.Equal("__weapon_normal_attack__", preset.LeftClickSkillId);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: SQL Server のリトライ実行戦略が有効でも、ログイン時の習得済みスキル照合は
    /// 実行戦略のスコープ内で Serializable transaction を開始して500にならない。
    /// </summary>
    [Fact]
    public async Task GetByAccountIdAsync_ReconcilesInsideRetryExecutionStrategy()
    {
        await using var fixture = await TestDatabase.CreateAsync(useRetryingExecutionStrategy: true);
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);

        var result = await new AccountLearnedSkillRepository(fixture.PlayerDb, fixture.MasterDb)
            .GetByAccountIdAsync(accountId);

        Assert.Empty(result);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: 忘却は指定個体と装着シジルだけを論理削除し、全プリセットの同UUIDバインドは保持する。
    /// </summary>
    [Fact]
    public async Task ForgetAsync_DeletesInstanceAndSigilsButKeepsBindings()
    {
        await using var fixture = await TestDatabase.CreateAsync();
        await fixture.SeedMasterAsync("adventurer_smash", "skill", null);
        var accountId = Guid.NewGuid();
        await fixture.AddAccountAsync(accountId);
        var learnedSkillId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        var learned = new AccountLearnedSkillEntity
        {
            LearnedSkillId = learnedSkillId,
            AccountId = accountId,
            SkillId = "adventurer_smash",
            Level = 1,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        };
        learned.Sigils.Add(new AccountLearnedSkillSigilEntity
        {
            LearnedSkillSigilId = Guid.NewGuid(),
            LearnedSkillId = learnedSkillId,
            SigilId = "cooldown_sigil",
            EquipGroupId = "cooldown",
            SlotIndex = 0,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        });
        fixture.PlayerDb.AccountLearnedSkills.Add(learned);
        fixture.PlayerDb.SkillBindPresets.Add(new SkillBindPresetEntity
        {
            SkillBindPresetId = Guid.NewGuid(),
            AccountId = accountId,
            PresetIndex = 1,
            ActiveSkillSlotsJson = JsonSerializer.Serialize(new string?[] { learnedSkillId.ToString() }),
            LeftClickSkillId = learnedSkillId.ToString(),
            PassiveSkillSlotsJson = JsonSerializer.Serialize(new string?[] { learnedSkillId.ToString() }),
            IsUnlocked = true,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = accountId,
            UpdatedBy = accountId,
        });
        await fixture.PlayerDb.SaveChangesAsync();

        var result = await new AccountLearnedSkillRepository(fixture.PlayerDb, fixture.MasterDb)
            .ForgetAsync(accountId, learnedSkillId, new AccountLearnedSkillForgetRequest
            {
                UpdatedBy = accountId,
            });

        Assert.True(result.Succeeded);
        Assert.True((await fixture.PlayerDb.AccountLearnedSkills.AsNoTracking()
            .SingleAsync(skill => skill.LearnedSkillId == learnedSkillId)).IsDeleted);
        Assert.True(await fixture.PlayerDb.AccountLearnedSkillSigils.AsNoTracking()
            .AllAsync(sigil => sigil.IsDeleted));
        var preset = await fixture.PlayerDb.SkillBindPresets.AsNoTracking().SingleAsync();
        Assert.Contains(learnedSkillId.ToString(), preset.ActiveSkillSlotsJson, StringComparison.OrdinalIgnoreCase);
        Assert.Contains(learnedSkillId.ToString(), preset.PassiveSkillSlotsJson, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(learnedSkillId.ToString(), preset.LeftClickSkillId);
    }

    private sealed class TestDatabase : IAsyncDisposable
    {
        private readonly SqliteConnection playerConnection;
        private readonly SqliteConnection masterConnection;
        public AstralRecordDbContext PlayerDb { get; }
        public MasterDataDbContext MasterDb { get; }

        private TestDatabase(
            SqliteConnection playerConnection,
            SqliteConnection masterConnection,
            AstralRecordDbContext playerDb,
            MasterDataDbContext masterDb)
        {
            this.playerConnection = playerConnection;
            this.masterConnection = masterConnection;
            PlayerDb = playerDb;
            MasterDb = masterDb;
        }

        public static async Task<TestDatabase> CreateAsync(bool useRetryingExecutionStrategy = false)
        {
            var playerConnection = new SqliteConnection("Data Source=:memory:");
            var masterConnection = new SqliteConnection("Data Source=:memory:");
            await playerConnection.OpenAsync();
            await masterConnection.OpenAsync();
            var playerOptions = new DbContextOptionsBuilder<AstralRecordDbContext>();
            if (useRetryingExecutionStrategy)
            {
                playerOptions.UseSqlite(playerConnection, sqlite => sqlite.ExecutionStrategy(
                    dependencies => new RetryingTestExecutionStrategy(dependencies)));
            }
            else
            {
                playerOptions.UseSqlite(playerConnection);
            }
            var playerDb = new AstralRecordDbContext(playerOptions.Options);
            var masterDb = new MasterDataDbContext(
                new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(masterConnection).Options);
            await CreatePlayerSchemaAsync(playerDb);
            await MasterDataTestSeed.CreateSchemaAsync(masterDb);
            return new TestDatabase(playerConnection, masterConnection, playerDb, masterDb);
        }

        private sealed class RetryingTestExecutionStrategy(ExecutionStrategyDependencies dependencies)
            : ExecutionStrategy(dependencies, maxRetryCount: 1, maxRetryDelay: TimeSpan.Zero)
        {
            protected override bool ShouldRetryOn(Exception exception) => false;
        }

        public async Task SeedMasterAsync(string masterId, string masterType, string? category)
            => await MasterDataTestSeed.SeedInlinePayloadAsync(
                MasterDb,
                MasterDataTestFixtures.Get(masterId),
                masterType,
                category);

        public AstralRecordDbContext CreatePlayerDb()
            => new(new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlite(playerConnection)
                .Options);

        public async Task AddAccountAsync(Guid accountId, Guid? userId = null)
            => await PlayerDb.Database.ExecuteSqlInterpolatedAsync(
                $"INSERT INTO account (uuid, user_id, is_deleted) VALUES ({accountId}, {userId ?? Guid.NewGuid()}, {false})");

        public async Task<Guid> AddInventoryEntryAsync(
            Guid accountId,
            string category,
            string itemId,
            long quantity,
            string inventoryType = "BAG",
            int? slotIndex = null,
            Guid? inventoryEntryId = null,
            string? instanceType = null,
            Guid? instanceId = null)
        {
            var now = DateTime.UtcNow;
            var inventory = await PlayerDb.Inventories.FirstOrDefaultAsync(candidate =>
                candidate.AccountId == accountId && candidate.InventoryType == inventoryType);
            if (inventory is null)
            {
                inventory = new InventoryEntity
                {
                    InventoryId = Guid.NewGuid(),
                    AccountId = accountId,
                    InventoryType = inventoryType,
                    InventoryProfile = "GAME",
                    IsEnabled = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                };
                PlayerDb.Inventories.Add(inventory);
            }
            var entry = new InventoryEntryEntity
            {
                InventoryEntryId = inventoryEntryId ?? Guid.NewGuid(),
                InventoryId = inventory.InventoryId,
                SlotIndex = slotIndex,
                ItemCategory = category,
                ItemId = itemId,
                InstanceType = instanceType,
                InstanceId = instanceId,
                Quantity = quantity,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            };
            PlayerDb.InventoryEntries.Add(entry);
            await PlayerDb.SaveChangesAsync();
            return entry.InventoryEntryId;
        }

        public async ValueTask DisposeAsync()
        {
            await PlayerDb.DisposeAsync();
            await MasterDb.DisposeAsync();
            await playerConnection.DisposeAsync();
            await masterConnection.DisposeAsync();
        }

        private static async Task CreatePlayerSchemaAsync(AstralRecordDbContext db)
        {
            await db.Database.ExecuteSqlRawAsync(@"
                CREATE TABLE account (uuid TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, is_deleted INTEGER NOT NULL);
                CREATE TABLE inventory (
                    inventory_id TEXT NOT NULL PRIMARY KEY, account_id TEXT NOT NULL, inventory_type TEXT NOT NULL,
                    inventory_profile TEXT NOT NULL, slot_capacity INTEGER NULL, is_enabled INTEGER NOT NULL,
                    metadata_json TEXT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    created_by TEXT NOT NULL, updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL);
                CREATE TABLE inventory_entry (
                    inventory_entry_id TEXT NOT NULL PRIMARY KEY, inventory_id TEXT NOT NULL, slot_index INTEGER NULL,
                    item_category TEXT NOT NULL, item_id TEXT NULL, instance_type TEXT NULL, instance_id TEXT NULL,
                    quantity INTEGER NOT NULL, metadata_json TEXT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    created_by TEXT NOT NULL, updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL);
                CREATE TABLE account_learned_skill (
                    learned_skill_id TEXT NOT NULL PRIMARY KEY, account_id TEXT NOT NULL, skill_id TEXT NOT NULL,
                    level INTEGER NOT NULL, version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    created_by TEXT NOT NULL, updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL);
                CREATE TABLE account_learned_skill_sigil (
                    learned_skill_sigil_id TEXT NOT NULL PRIMARY KEY, learned_skill_id TEXT NOT NULL,
                    sigil_id TEXT NOT NULL, equip_group_id TEXT NOT NULL, slot_index INTEGER NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, created_by TEXT NOT NULL,
                    updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL);
                CREATE TABLE skill_bind_preset (
                    skill_bind_preset_id TEXT NOT NULL PRIMARY KEY, account_id TEXT NOT NULL, preset_index INTEGER NOT NULL,
                    active_skill_slots_json TEXT NOT NULL, left_click_skill_id TEXT NULL,
                    passive_skill_slots_json TEXT NOT NULL, is_unlocked INTEGER NOT NULL, is_selected INTEGER NOT NULL,
                    version INTEGER NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, created_by TEXT NOT NULL,
                    updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL);
                CREATE TABLE player_mail_delivery (
                    player_mail_delivery_id TEXT NOT NULL PRIMARY KEY, account_id TEXT NOT NULL, mail_id TEXT NOT NULL,
                    payload_json TEXT NOT NULL, version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    created_by TEXT NOT NULL, updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL);");
        }
    }

}
