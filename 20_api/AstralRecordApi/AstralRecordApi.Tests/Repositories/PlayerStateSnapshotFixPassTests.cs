using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using System.Text.Json.Nodes;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed partial class PlayerStateSnapshotRepositoryTests
{
    [Fact]
    public async Task SaveAsync_RebasedExpectedVersionMayReachOrExceedLocalTargetHint()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);
        var skillId = Guid.NewGuid();
        foreach (var expected in new int?[] { null, 1, 2 })
        {
            var result = await repository.SaveAsync(new PlayerStateSnapshotSaveRequest
            {
                SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
                LearnedSkills = Section(new PlayerStateLearnedSkillsSection
                {
                    AccountId = fixture.AccountId,
                    Skills = [new PlayerStateLearnedSkillSnapshot
                    {
                        LearnedSkillId = skillId, SkillId = "slash", Level = 1,
                        ExpectedVersion = expected, TargetVersion = 1,
                    }],
                }),
                SkillBindPresets = Section(new PlayerStateSkillBindPresetsSection
                {
                    AccountId = fixture.AccountId, SelectedPresetIndex = 1,
                    Presets = Enumerable.Range(1, SkillBindPresetRepository.PresetCount)
                        .Select(index => new PlayerStateSkillBindPresetSnapshot
                        {
                            PresetIndex = index, ExpectedVersion = expected, TargetVersion = 1,
                            ActiveSkillSlots = [skillId.ToString()],
                        }).ToArray(),
                }),
                SkillTree = Section(new PlayerStateSkillTreeSection
                {
                    AccountId = fixture.AccountId, ExpectedVersion = expected, TargetVersion = 1,
                    UnlockedNodes = [new() { NodeId = "starter" }],
                }),
            });
            Assert.True(result.Succeeded, result.Detail);
            Assert.Equal((expected ?? 0) + 1,
                (await fixture.DbContext.AccountLearnedSkills.AsNoTracking().SingleAsync()).Version);
            Assert.All(await fixture.DbContext.SkillBindPresets.AsNoTracking().ToListAsync(),
                preset => Assert.Equal((expected ?? 0) + 1, preset.Version));
            Assert.Equal((expected ?? 0) + 1,
                (await fixture.DbContext.AccountSkillTreeStates.AsNoTracking().SingleAsync()).Version);
        }
    }

    // AR-CODE-011/012: completed learned state is the binding authority within one transaction.
    [Fact]
    public async Task SaveAsync_NewLearnedSkillCanBeBound_DeletedSkillCannotRemainBound()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var skillId = Guid.NewGuid();
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);
        var first = await repository.SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            LearnedSkills = Section(new PlayerStateLearnedSkillsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1,
                Skills = [new PlayerStateLearnedSkillSnapshot { LearnedSkillId = skillId, SkillId = "slash", Level = 1 }],
            }),
            SkillBindPresets = Section(BindSection(fixture.AccountId, 1, null, skillId.ToString())),
        });
        Assert.True(first.Succeeded, first.Detail);

        PlayerStateSnapshotSaveRequest DeleteRequest(string? binding) => new()
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            LearnedSkills = Section(new PlayerStateLearnedSkillsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 2,
                DeletedSkills = [new PlayerStateDeletedLearnedSkillSnapshot { LearnedSkillId = skillId, ExpectedVersion = 1 }],
            }),
            SkillBindPresets = Section(BindSection(fixture.AccountId, 2, 1, binding)),
        };
        var invalidBinding = await repository.SaveAsync(DeleteRequest(skillId.ToString()));
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, invalidBinding.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.False((await fixture.DbContext.AccountLearnedSkills.SingleAsync()).IsDeleted);
        Assert.Equal(1, (await fixture.DbContext.SkillBindPresets.SingleAsync(p => p.IsSelected)).PresetIndex);

        var deleted = await repository.SaveAsync(DeleteRequest(null));
        Assert.True(deleted.Succeeded, deleted.Detail);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.True((await fixture.DbContext.AccountLearnedSkills.SingleAsync()).IsDeleted);
        Assert.Equal(2, (await fixture.DbContext.SkillBindPresets.SingleAsync(p => p.IsSelected)).PresetIndex);
        Assert.Equal(2, await fixture.DbContext.PlayerStateSnapshots.CountAsync());
    }

    [Fact]
    public async Task SaveAsync_SelectionSwitchBothDirections_AndLaterConflictRollsBackDeselection()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);
        foreach (var (selected, version) in new[] { (1, (int?)null), (2, (int?)1), (1, (int?)2) })
        {
            var result = await repository.SaveAsync(new PlayerStateSnapshotSaveRequest
            {
                SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
                SkillBindPresets = Section(BindSection(fixture.AccountId, selected, version, null)),
            });
            Assert.True(result.Succeeded, result.Detail);
            Assert.Equal(selected, (await fixture.DbContext.SkillBindPresets.AsNoTracking().SingleAsync(p => p.IsSelected)).PresetIndex);
        }
        var rejected = await repository.SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            SkillBindPresets = Section(BindSection(fixture.AccountId, 2, 3, null)),
            AccountProgress = Section(ProgressSection(fixture.AccountId, 99)),
        });
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, rejected.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(1, (await fixture.DbContext.SkillBindPresets.SingleAsync(p => p.IsSelected)).PresetIndex);
        Assert.All(await fixture.DbContext.SkillBindPresets.ToListAsync(), p => Assert.Equal(3, p.Version));
    }

    // AR-CODE-013: an already tracked stale account must be reread within the update transaction.
    [Fact]
    public async Task AccountUpdate_AfterSnapshot_RereadsVersionAndInvalidatesEarlierSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var staleAccount = await fixture.DbContext.Accounts.SingleAsync();
        await using var snapshotContext = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(fixture.DbContext.Database.GetDbConnection()).Options);
        var saved = await new PlayerStateSnapshotRepository(snapshotContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            AccountProgress = Section(ProgressSection(fixture.AccountId, 1)),
        });
        Assert.True(saved.Succeeded, saved.Detail);
        Assert.Equal(1, staleAccount.ProgressVersion);
        var updated = await new AccountRepository(fixture.DbContext).UpdateAsync(fixture.AccountId,
            new AccountUpdateRequest { Level = 3, TotalExperience = 300, UpdatedBy = fixture.AccountId });
        Assert.Equal(3, updated!.ProgressVersion);
        var rejected = await new PlayerStateSnapshotRepository(snapshotContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            AccountProgress = Section(ProgressSection(fixture.AccountId, 2)),
        });
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, rejected.Failure);
        Assert.Equal(300, (await snapshotContext.Accounts.AsNoTracking().SingleAsync()).TotalExperience);
    }

    [Fact]
    public async Task EquipmentDurability_ReturnsMillisecondVersion_UsableByNextSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var equipment = CreateEquipment(Guid.NewGuid(), fixture);
        equipment.DurabilityMax = 100;
        equipment.DurabilityValue = 100;
        equipment.UpdatedAt = DateTime.UtcNow.AddMinutes(1).AddTicks(123);
        var baseline = equipment.UpdatedAt;
        fixture.DbContext.EquipmentInstances.Add(equipment);
        await fixture.DbContext.SaveChangesAsync();
        var updated = await new EquipmentRepository(fixture.DbContext).UpdateDurabilityAsync(equipment.EquipmentInstanceId, 90, fixture.AccountId);
        Assert.NotNull(updated);
        Assert.True(updated.UpdatedAt > baseline);
        Assert.Equal(0, updated.UpdatedAt.Ticks % TimeSpan.TicksPerMillisecond);
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Equipment = [new PlayerStateEquipmentSnapshot
            {
                EquipmentInstanceId = equipment.EquipmentInstanceId, ExpectedUpdatedAt = updated.UpdatedAt,
                DurabilityMax = 100, DurabilityValue = 80, RuneMaxSlots = 2,
            }],
        });
        Assert.True(result.Succeeded, result.Detail);
    }

    // AR-CODE-015: SQL Server DECIMAL(18,4) overflow must be rejected as an invalid payload.
    [Theory]
    [InlineData(100_000_000_000_000L)]
    [InlineData(-100_000_000_000_000L)]
    public async Task SaveAsync_EnchantValueOutsideSqlDecimalRangeIsInvalidBeforeAnyDatabaseMutation(long value)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var equipment = CreateEquipment(Guid.NewGuid(), fixture);
        fixture.DbContext.EquipmentInstances.Add(equipment);
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Equipment = [new PlayerStateEquipmentSnapshot
            {
                EquipmentInstanceId = equipment.EquipmentInstanceId, ExpectedUpdatedAt = equipment.UpdatedAt,
                RuneMaxSlots = equipment.RuneMaxSlots,
                Enchants = [new PlayerStateEquipmentEnchantSnapshot
                {
                    EnchantId = Guid.NewGuid(), SlotIndex = 0, EnchantMasterId = "master", EffectId = "effect",
                    Status = "FIXED", Type = "FLAT", Value = value,
                }],
            }],
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        Assert.Empty(await fixture.DbContext.EquipmentInstanceEnchants.AsNoTracking().ToListAsync());
        Assert.Empty(await fixture.DbContext.PlayerStateSnapshots.AsNoTracking().ToListAsync());
        Assert.False(fixture.DbContext.ChangeTracker.HasChanges());
    }

    [Theory]
    [InlineData("duplicateEquipment")]
    [InlineData("negativeEquipment")]
    [InlineData("duplicateLoadout")]
    [InlineData("invalidLoadoutSlot")]
    [InlineData("inventorySlot")]
    [InlineData("inventoryStack")]
    [InlineData("negativeQuantity")]
    [InlineData("duplicateLearned")]
    [InlineData("duplicateTree")]
    [InlineData("negativeRevision")]
    [InlineData("nullEntries")]
    public async Task SaveAsync_MalformedPayloadIsInvalidBeforeAnyDatabaseMutation(string malformed)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var json = JsonSerializer.SerializeToNode(fixture.CreateMoveRequest(Guid.NewGuid()), new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        switch (malformed)
        {
            case "duplicateEquipment":
            case "negativeEquipment":
                var equipment = JsonSerializer.SerializeToNode(new PlayerStateEquipmentSnapshot
                {
                    EquipmentInstanceId = Guid.NewGuid(), EnhanceLevel = malformed == "negativeEquipment" ? -1 : 0,
                }, new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
                json["equipment"] = malformed == "duplicateEquipment"
                    ? new JsonArray(equipment, equipment.DeepClone()) : new JsonArray(equipment);
                break;
            case "duplicateLoadout":
            case "invalidLoadoutSlot":
                var loadout = JsonSerializer.SerializeToNode(new PlayerStateLoadoutSnapshot
                {
                    EquipmentLoadoutId = Guid.NewGuid(),
                    Slots = malformed == "invalidLoadoutSlot"
                        ? [new PlayerStateLoadoutSlotSnapshot { SlotType = "WEAPON", SlotIndex = -1, EquipmentInstanceId = Guid.NewGuid() }] : [],
                }, new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
                json["loadouts"] = malformed == "duplicateLoadout"
                    ? new JsonArray(loadout, loadout.DeepClone()) : new JsonArray(loadout);
                break;
            case "inventorySlot":
            case "inventoryStack":
                var entry = json["inventories"]![1]!["entries"]![0]!;
                var duplicate = entry.DeepClone();
                duplicate["inventoryEntryId"] = Guid.NewGuid();
                duplicate["expectedUpdatedAt"] = null;
                if (malformed == "inventorySlot") { entry["slotIndex"] = 1; duplicate["slotIndex"] = 1; }
                ((JsonArray)json["inventories"]![1]!["entries"]!).Add(duplicate);
                break;
            case "negativeQuantity": json["inventories"]![1]!["entries"]![0]!["quantity"] = -1; break;
            case "nullEntries": json["inventories"]![0]!["entries"] = null; break;
            case "negativeRevision": json["waystones"] = JsonNode.Parse("{\"clientRevision\":-1,\"unlockedWaystoneIds\":[]}"); break;
            case "duplicateLearned":
                var skill = new PlayerStateLearnedSkillSnapshot { LearnedSkillId = Guid.NewGuid(), SkillId = "slash", Level = 1 };
                json["learnedSkills"] = JsonNode.Parse(Section(new PlayerStateLearnedSkillsSection
                { AccountId = fixture.AccountId, Skills = [skill, skill] }).GetRawText());
                break;
            case "duplicateTree":
                json["skillTree"] = JsonNode.Parse(Section(new PlayerStateSkillTreeSection
                { AccountId = fixture.AccountId, UnlockedNodes = [new() { NodeId = "starter" }, new() { NodeId = " STARTER " }] }).GetRawText());
                break;
        }
        var request = json.Deserialize<PlayerStateSnapshotSaveRequest>(new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        Assert.Equal(10, (await fixture.DbContext.InventoryEntries.AsNoTracking().SingleAsync()).Quantity);
        Assert.Empty(await fixture.DbContext.PlayerStateSnapshots.ToListAsync());
        Assert.False(fixture.DbContext.ChangeTracker.HasChanges());
    }

    private static PlayerStateSkillBindPresetsSection BindSection(Guid accountId, int selected, int? version, string? binding) => new()
    {
        AccountId = accountId, ClientRevision = 1, SelectedPresetIndex = selected,
        Presets = Enumerable.Range(1, SkillBindPresetRepository.PresetCount).Select(index => new PlayerStateSkillBindPresetSnapshot
        { PresetIndex = index, ExpectedVersion = version, ActiveSkillSlots = [binding] }).ToArray(),
    };

    private static PlayerStateAccountProgressSection ProgressSection(Guid accountId, int version) => new()
    {
        AccountId = accountId, ClientRevision = 1, ExpectedProgressVersion = version,
        Level = 2, TotalExperience = 100, ClassId = "adventurer", ClassLevel = 1, ClassExperience = 0,
    };
}
