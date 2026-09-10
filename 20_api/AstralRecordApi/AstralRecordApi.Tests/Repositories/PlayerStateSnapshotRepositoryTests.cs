using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed partial class PlayerStateSnapshotRepositoryTests
{
    [Fact]
    public async Task FindCompletedAsync_ReturnsOnlyMatchingCommittedSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var snapshotId = Guid.NewGuid();
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);

        Assert.Null(await repository.FindCompletedAsync(snapshotId, fixture.AccountId));
        var saved = await repository.SaveAsync(fixture.CreateMoveRequest(snapshotId));
        Assert.True(saved.Succeeded, saved.Detail);

        var found = await repository.FindCompletedAsync(snapshotId, fixture.AccountId);
        Assert.NotNull(found);
        Assert.Equal(saved.Ack!.SnapshotId, found!.SnapshotId);
        Assert.Null(await repository.FindCompletedAsync(snapshotId, Guid.NewGuid()));
    }

    [Fact]
    public async Task FindCompletedAsync_RejectsStoredAcknowledgementForAnotherIdentity()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var snapshotId = Guid.NewGuid();
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);
        var saved = await repository.SaveAsync(fixture.CreateMoveRequest(snapshotId));
        Assert.True(saved.Succeeded, saved.Detail);

        var stored = await fixture.DbContext.PlayerStateSnapshots.SingleAsync();
        stored.AckPayloadJson = JsonSerializer.Serialize(new PlayerStateSnapshotAck
        {
            SnapshotId = Guid.NewGuid(),
            AccountId = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();
        fixture.DbContext.ChangeTracker.Clear();

        Assert.Null(await repository.FindCompletedAsync(snapshotId, fixture.AccountId));
    }

    [Fact]
    public async Task SaveAsync_MovesEntryAcrossSnapshotParents_AndReplaysFixedAck()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var snapshotId = Guid.NewGuid();
        var request = fixture.CreateMoveRequest(snapshotId);

        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(first.Succeeded);
        Assert.Equal(fixture.SecondInventoryId, (await fixture.DbContext.InventoryEntries.SingleAsync()).InventoryId);
        var firstAck = first.Ack!;
        Assert.Single(firstAck.Entries);
        Assert.False(firstAck.Entries.Single().IsDeleted);

        var entry = await fixture.DbContext.InventoryEntries.SingleAsync();
        entry.UpdatedAt = entry.UpdatedAt.AddSeconds(1);
        await fixture.DbContext.SaveChangesAsync();

        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(replay.Succeeded);
        Assert.Equal(firstAck.Entries.Single().UpdatedAt, replay.Ack!.Entries.Single().UpdatedAt);
        var differentPayload = fixture.CreateMoveRequest(snapshotId, quantity: 2);
        var collision = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(differentPayload);
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, collision.Failure);
    }

    [Fact]
    public async Task SaveAsync_PreservesUnchangedEntryWhenDeltaOmitsIt()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var untouchedEntryId = Guid.NewGuid();
        fixture.DbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = untouchedEntryId,
            InventoryId = fixture.FirstInventoryId,
            ItemCategory = "CURRENCY",
            ItemId = "silver",
            Quantity = 1,
            CreatedAt = fixture.BaseTime,
            UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId,
            UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext)
            .SaveAsync(fixture.CreateMoveRequest(Guid.NewGuid()));

        Assert.True(result.Succeeded, result.Detail);
        var untouched = await fixture.DbContext.InventoryEntries.SingleAsync(entry => entry.InventoryEntryId == untouchedEntryId);
        Assert.False(untouched.IsDeleted);
        Assert.Equal(fixture.FirstInventoryId, untouched.InventoryId);
    }

    [Fact]
    public async Task SaveAsync_RejectsDeletionWhenBaselineEntryWasChangedExternally()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var entry = await fixture.DbContext.InventoryEntries.SingleAsync();
        entry.Quantity = 11;
        entry.UpdatedAt = entry.UpdatedAt.AddSeconds(1);
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                    Entries = [],
                },
            ],
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        var unchanged = await fixture.DbContext.InventoryEntries.SingleAsync();
        Assert.False(unchanged.IsDeleted);
        Assert.Equal(11, unchanged.Quantity);
    }

    [Fact]
    public async Task SaveAsync_AppliesMaterialEquipmentLearnedSkillsAndTreeInOneTransaction()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var materialEntryId = Guid.NewGuid();
        var equipmentId = Guid.NewGuid();
        var firstEnchantId = Guid.NewGuid();
        var secondEnchantId = Guid.NewGuid();
        var firstRuneId = Guid.NewGuid();
        var secondRuneId = Guid.NewGuid();
        var learnedSkillId = Guid.NewGuid();
        fixture.DbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = materialEntryId, InventoryId = fixture.FirstInventoryId, ItemCategory = "MATERIAL", ItemId = "iron_ore", Quantity = 4,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        fixture.DbContext.EquipmentInstances.Add(new EquipmentInstanceEntity
        {
            EquipmentInstanceId = equipmentId, AccountId = fixture.AccountId, ItemId = "iron_sword", RuneMaxSlots = 2,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        fixture.DbContext.EquipmentInstanceEnchants.AddRange(
            CreateEnchant(firstEnchantId, equipmentId, 0, "effect_one", fixture),
            CreateEnchant(secondEnchantId, equipmentId, 1, "effect_two", fixture));
        fixture.DbContext.EquipmentInstanceRunes.AddRange(
            CreateRune(firstRuneId, equipmentId, 0, "rune_one", fixture),
            CreateRune(secondRuneId, equipmentId, 1, "rune_two", fixture));
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries =
                    [
                        new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime },
                        new PlayerStateExpectedInventoryEntry { InventoryEntryId = materialEntryId, UpdatedAt = fixture.BaseTime },
                    ],
                    Entries =
                    [
                        new PlayerStateInventoryEntrySnapshot { InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 10 },
                        new PlayerStateInventoryEntrySnapshot { InventoryEntryId = materialEntryId, ExpectedUpdatedAt = fixture.BaseTime, ItemCategory = "MATERIAL", ItemId = "iron_ore", Quantity = 3 },
                    ],
                },
            ],
            Equipment =
            [
                new PlayerStateEquipmentSnapshot
                {
                    EquipmentInstanceId = equipmentId, ExpectedUpdatedAt = fixture.BaseTime, RuneMaxSlots = 2,
                    Enchants =
                    [
                        new PlayerStateEquipmentEnchantSnapshot { EnchantId = firstEnchantId, SlotIndex = 1, EnchantMasterId = "master", EffectId = "effect_two", Status = "FIXED", Type = "FLAT", Value = 2 },
                        new PlayerStateEquipmentEnchantSnapshot { EnchantId = secondEnchantId, SlotIndex = 0, EnchantMasterId = "master", EffectId = "effect_one", Status = "FIXED", Type = "FLAT", Value = 1 },
                    ],
                    Runes =
                    [
                        new PlayerStateEquipmentRuneSnapshot { RuneId = firstRuneId, SlotIndex = 1, ItemId = "rune_two" },
                        new PlayerStateEquipmentRuneSnapshot { RuneId = secondRuneId, SlotIndex = 0, ItemId = "rune_one" },
                    ],
                },
            ],
            LearnedSkills = Section(new PlayerStateLearnedSkillsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 11,
                Skills = [new PlayerStateLearnedSkillSnapshot { LearnedSkillId = learnedSkillId, SkillId = "slash", Level = 1 }],
            }),
            SkillTree = Section(new PlayerStateSkillTreeSection
            {
                AccountId = fixture.AccountId, ClientRevision = 12, ExpectedVersion = 0,
                UnlockedNodes = [new AccountSkillTreeUnlockedNodeModel { NodeId = "starter" }],
            }),
        });

        Assert.True(result.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(3, await fixture.DbContext.InventoryEntries.AsNoTracking().Where(entry => entry.InventoryEntryId == materialEntryId).Select(entry => entry.Quantity).SingleAsync());
        var enchants = await fixture.DbContext.EquipmentInstanceEnchants.AsNoTracking().Where(enchant => enchant.EquipmentInstanceId == equipmentId).ToDictionaryAsync(enchant => enchant.EnchantId);
        Assert.Equal(1, enchants[firstEnchantId].SlotIndex);
        Assert.Equal("effect_two", enchants[firstEnchantId].EffectId);
        Assert.Equal(0, enchants[secondEnchantId].SlotIndex);
        var runes = await fixture.DbContext.EquipmentInstanceRunes.AsNoTracking().Where(rune => rune.EquipmentInstanceId == equipmentId).ToDictionaryAsync(rune => rune.RuneId);
        Assert.Equal(1, runes[firstRuneId].SlotIndex);
        Assert.Equal(0, runes[secondRuneId].SlotIndex);
        Assert.True(await fixture.DbContext.AccountLearnedSkills.AsNoTracking().AnyAsync(skill => skill.LearnedSkillId == learnedSkillId && !skill.IsDeleted));
        Assert.True(await fixture.DbContext.AccountSkillTreeUnlockedNodes.AsNoTracking().AnyAsync(node => node.NodeId == "starter"));
    }

    [Fact]
    public async Task SaveAsync_CreatesEquipmentRollInventoryEntryAndLoadoutInOneIdempotentSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var snapshotId = Guid.NewGuid();
        var equipmentId = Guid.NewGuid();
        var statRollId = Guid.NewGuid();
        var equipmentEntryId = Guid.NewGuid();
        var loadoutId = Guid.NewGuid();
        fixture.DbContext.EquipmentLoadouts.Add(new EquipmentLoadoutEntity
        {
            EquipmentLoadoutId = loadoutId, AccountId = fixture.AccountId, LoadoutProfile = "GAME",
            LoadoutName = "snapshot-new-equipment", SortOrder = 0, IsActive = true,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = snapshotId, AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries =
                    [
                        new PlayerStateExpectedInventoryEntry
                            { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime },
                    ],
                    Entries =
                    [
                        new PlayerStateInventoryEntrySnapshot
                        {
                            InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime,
                            ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 10,
                        },
                        new PlayerStateInventoryEntrySnapshot
                        {
                            InventoryEntryId = equipmentEntryId, SlotIndex = 0, ItemCategory = "EQUIPMENT",
                            ItemId = "iron_sword", InstanceType = "EQUIPMENT", InstanceId = equipmentId, Quantity = 1,
                        },
                    ],
                },
            ],
            Equipment =
            [
                new PlayerStateEquipmentSnapshot
                {
                    EquipmentInstanceId = equipmentId, IsNew = true, ItemId = "iron_sword",
                    RuneMaxSlots = 2, DurabilityMax = 100, DurabilityValue = 100,
                    StatRolls =
                    [
                        new PlayerStateEquipmentStatRollSnapshot
                        {
                            StatRollId = statRollId, Status = "PHYSICAL_ATTACK", Min = "12.5", Max = "18", SortOrder = 0,
                        },
                    ],
                },
            ],
            Loadouts =
            [
                new PlayerStateLoadoutSnapshot
                {
                    EquipmentLoadoutId = loadoutId, ExpectedUpdatedAt = fixture.BaseTime,
                    Slots =
                    [
                        new PlayerStateLoadoutSlotSnapshot
                            { SlotType = "WEAPON", SlotIndex = 0, EquipmentInstanceId = equipmentId },
                    ],
                },
            ],
        };

        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(first.Succeeded, first.Detail);
        Assert.True(replay.Succeeded, replay.Detail);
        Assert.Equal(first.Ack!.Equipment.Single().UpdatedAt, replay.Ack!.Equipment.Single().UpdatedAt);
        fixture.DbContext.ChangeTracker.Clear();
        var equipment = await fixture.DbContext.EquipmentInstances.AsNoTracking().SingleAsync(e => e.EquipmentInstanceId == equipmentId);
        Assert.Equal("iron_sword", equipment.ItemId);
        Assert.Equal(100, equipment.DurabilityValue);
        var roll = await fixture.DbContext.EquipmentInstanceStatRolls.AsNoTracking().SingleAsync(r => r.StatRollId == statRollId);
        Assert.Equal("12.5", roll.RandomMin);
        Assert.Equal("18", roll.RandomMax);
        Assert.True(await fixture.DbContext.InventoryEntries.AsNoTracking().AnyAsync(e =>
            e.InventoryEntryId == equipmentEntryId && e.InstanceId == equipmentId && e.ItemId == "iron_sword"));
        Assert.True(await fixture.DbContext.EquipmentLoadoutSlots.AsNoTracking().AnyAsync(s =>
            s.EquipmentLoadoutId == loadoutId && s.EquipmentInstanceId == equipmentId && !s.IsDeleted));
        Assert.Single(await fixture.DbContext.EquipmentInstances.AsNoTracking().Where(e => e.EquipmentInstanceId == equipmentId).ToListAsync());
        Assert.Single(await fixture.DbContext.EquipmentInstanceStatRolls.AsNoTracking().Where(r => r.StatRollId == statRollId).ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_CreatesNewInventoryLoadoutEquipmentEntryAndSlotInOneIdempotentSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var snapshotId = Guid.NewGuid();
        var inventoryId = Guid.NewGuid();
        var loadoutId = Guid.NewGuid();
        var equipmentId = Guid.NewGuid();
        var entryId = Guid.NewGuid();
        var statRollId = Guid.NewGuid();
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = snapshotId, AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = inventoryId, IsNew = true, InventoryType = "MATERIAL", InventoryProfile = "GAME",
                    SlotCapacity = 36, IsEnabled = true, MetadataJson = "{}", ExpectedEntries = [],
                    Entries =
                    [
                        new PlayerStateInventoryEntrySnapshot
                        {
                            InventoryEntryId = entryId, SlotIndex = 0, ItemCategory = "EQUIPMENT", ItemId = "iron_sword",
                            InstanceType = "EQUIPMENT", InstanceId = equipmentId, Quantity = 1,
                        },
                    ],
                },
            ],
            Equipment =
            [
                new PlayerStateEquipmentSnapshot
                {
                    EquipmentInstanceId = equipmentId, IsNew = true, ItemId = "iron_sword", RuneMaxSlots = 1,
                    StatRolls = [new PlayerStateEquipmentStatRollSnapshot
                    {
                        StatRollId = statRollId, Status = "PHYSICAL_ATTACK", Min = "1", Max = "2", SortOrder = 0,
                    }],
                },
            ],
            Loadouts =
            [
                new PlayerStateLoadoutSnapshot
                {
                    EquipmentLoadoutId = loadoutId, IsNew = true, LoadoutProfile = "GAME", LoadoutName = "main",
                    SortOrder = 0, IsActive = true, MetadataJson = "{}",
                    Slots = [new PlayerStateLoadoutSlotSnapshot
                    {
                        SlotType = "WEAPON", SlotIndex = 0, EquipmentInstanceId = equipmentId,
                    }],
                },
            ],
        };

        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(first.Succeeded, first.Detail);
        Assert.True(replay.Succeeded, replay.Detail);
        Assert.Equal(first.Ack!.Inventories.Single().UpdatedAt, replay.Ack!.Inventories.Single().UpdatedAt);
        Assert.Equal(first.Ack.Loadouts.Single().UpdatedAt, replay.Ack.Loadouts.Single().UpdatedAt);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.True(await fixture.DbContext.Inventories.AsNoTracking().AnyAsync(inventory =>
            inventory.InventoryId == inventoryId && inventory.InventoryType == "MATERIAL" && inventory.SlotCapacity == 36));
        Assert.True(await fixture.DbContext.EquipmentLoadouts.AsNoTracking().AnyAsync(loadout =>
            loadout.EquipmentLoadoutId == loadoutId && loadout.IsActive && loadout.LoadoutName == "main"));
        Assert.True(await fixture.DbContext.InventoryEntries.AsNoTracking().AnyAsync(entry =>
            entry.InventoryEntryId == entryId && entry.InventoryId == inventoryId && entry.InstanceId == equipmentId));
        Assert.True(await fixture.DbContext.EquipmentLoadoutSlots.AsNoTracking().AnyAsync(slot =>
            slot.EquipmentLoadoutId == loadoutId && slot.EquipmentInstanceId == equipmentId && !slot.IsDeleted));
        Assert.Single(await fixture.DbContext.EquipmentInstanceStatRolls.AsNoTracking()
            .Where(roll => roll.StatRollId == statRollId).ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_RollsBackNewInventoryLoadoutAndEquipmentWhenSectionConflicts()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var inventoryId = Guid.NewGuid();
        var loadoutId = Guid.NewGuid();
        var equipmentId = Guid.NewGuid();
        var entryId = Guid.NewGuid();
        fixture.DbContext.AccountSkillTreeStates.Add(new AccountSkillTreeStateEntity
        {
            AccountSkillTreeStateId = Guid.NewGuid(), AccountId = fixture.AccountId, Version = 2,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories = [new PlayerStateInventorySnapshot
            {
                InventoryId = inventoryId, IsNew = true, InventoryType = "MATERIAL", InventoryProfile = "GAME",
                SlotCapacity = 36, IsEnabled = true, ExpectedEntries = [],
                Entries = [new PlayerStateInventoryEntrySnapshot
                {
                    InventoryEntryId = entryId, SlotIndex = 0, ItemCategory = "EQUIPMENT", ItemId = "iron_sword",
                    InstanceType = "EQUIPMENT", InstanceId = equipmentId, Quantity = 1,
                }],
            }],
            Equipment = [new PlayerStateEquipmentSnapshot
            {
                EquipmentInstanceId = equipmentId, IsNew = true, ItemId = "iron_sword", RuneMaxSlots = 1,
            }],
            Loadouts = [new PlayerStateLoadoutSnapshot
            {
                EquipmentLoadoutId = loadoutId, IsNew = true, LoadoutProfile = "GAME", LoadoutName = "main",
                SortOrder = 0, IsActive = true,
                Slots = [new PlayerStateLoadoutSlotSnapshot { SlotType = "WEAPON", SlotIndex = 0, EquipmentInstanceId = equipmentId }],
            }],
            SkillTree = Section(new PlayerStateSkillTreeSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1, ExpectedVersion = 1, TargetVersion = 2,
            }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.False(await fixture.DbContext.Inventories.AsNoTracking().AnyAsync(inventory => inventory.InventoryId == inventoryId));
        Assert.False(await fixture.DbContext.EquipmentLoadouts.AsNoTracking().AnyAsync(loadout => loadout.EquipmentLoadoutId == loadoutId));
        Assert.False(await fixture.DbContext.EquipmentInstances.AsNoTracking().AnyAsync(equipment => equipment.EquipmentInstanceId == equipmentId));
        Assert.False(await fixture.DbContext.InventoryEntries.AsNoTracking().AnyAsync(entry => entry.InventoryEntryId == entryId));
    }

    [Fact]
    public async Task SaveAsync_RejectsIncompleteOrDuplicateNewInventoryAndLoadoutAttributes()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = Guid.NewGuid(), IsNew = true, InventoryType = "MATERIAL", InventoryProfile = "GAME",
                    SlotCapacity = 10, IsEnabled = true, ExpectedEntries = [],
                },
                new PlayerStateInventorySnapshot
                {
                    InventoryId = Guid.NewGuid(), IsNew = true, InventoryType = "material", InventoryProfile = "game",
                    SlotCapacity = 10, IsEnabled = true, ExpectedEntries = [],
                },
            ],
            Loadouts =
            [
                new PlayerStateLoadoutSnapshot
                {
                    EquipmentLoadoutId = Guid.NewGuid(), IsNew = true, LoadoutProfile = "GAME", LoadoutName = "main",
                    SortOrder = 0, IsActive = true,
                },
                new PlayerStateLoadoutSnapshot
                {
                    EquipmentLoadoutId = Guid.NewGuid(), IsNew = true, LoadoutProfile = "game", LoadoutName = "secondary",
                    SortOrder = 1, IsActive = true,
                },
            ],
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        Assert.Empty(await fixture.DbContext.Inventories.AsNoTracking().Where(inventory => inventory.InventoryType == "MATERIAL").ToListAsync());
        Assert.Empty(await fixture.DbContext.EquipmentLoadouts.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_RollsBackNewEquipmentWhenSectionConflicts()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var equipmentId = Guid.NewGuid();
        var equipmentEntryId = Guid.NewGuid();
        fixture.DbContext.AccountSkillTreeStates.Add(new AccountSkillTreeStateEntity
        {
            AccountSkillTreeStateId = Guid.NewGuid(), AccountId = fixture.AccountId, Version = 2,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries =
                    [
                        new PlayerStateExpectedInventoryEntry
                            { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime },
                    ],
                    Entries =
                    [
                        new PlayerStateInventoryEntrySnapshot
                        {
                            InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime,
                            ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 10,
                        },
                        new PlayerStateInventoryEntrySnapshot
                        {
                            InventoryEntryId = equipmentEntryId, SlotIndex = 0, ItemCategory = "EQUIPMENT",
                            ItemId = "iron_sword", InstanceType = "EQUIPMENT", InstanceId = equipmentId, Quantity = 1,
                        },
                    ],
                },
            ],
            Equipment =
            [
                new PlayerStateEquipmentSnapshot
                {
                    EquipmentInstanceId = equipmentId, IsNew = true, ItemId = "iron_sword", RuneMaxSlots = 1,
                },
            ],
            SkillTree = Section(new PlayerStateSkillTreeSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1, ExpectedVersion = 1, TargetVersion = 2,
            }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.False(await fixture.DbContext.EquipmentInstances.AsNoTracking().AnyAsync(e => e.EquipmentInstanceId == equipmentId));
        Assert.False(await fixture.DbContext.InventoryEntries.AsNoTracking().AnyAsync(e => e.InventoryEntryId == equipmentEntryId));
        Assert.Empty(await fixture.DbContext.PlayerStateSnapshots.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_RollsBackCoreEquipmentAndLearnedSkillWhenTreeSectionConflicts()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var equipmentId = Guid.NewGuid();
        var existingEnchantId = Guid.NewGuid();
        var learnedSkillId = Guid.NewGuid();
        fixture.DbContext.EquipmentInstances.Add(new EquipmentInstanceEntity
        {
            EquipmentInstanceId = equipmentId, AccountId = fixture.AccountId, ItemId = "iron_sword", RuneMaxSlots = 1,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        fixture.DbContext.EquipmentInstanceEnchants.Add(CreateEnchant(existingEnchantId, equipmentId, 0, "effect_one", fixture));
        fixture.DbContext.AccountSkillTreeStates.Add(new AccountSkillTreeStateEntity
        {
            AccountSkillTreeStateId = Guid.NewGuid(), AccountId = fixture.AccountId, Version = 2,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                    Entries = [new PlayerStateInventoryEntrySnapshot { InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 9 }],
                },
            ],
            Equipment =
            [
                new PlayerStateEquipmentSnapshot
                {
                    EquipmentInstanceId = equipmentId, ExpectedUpdatedAt = fixture.BaseTime, RuneMaxSlots = 1,
                    Enchants = [new PlayerStateEquipmentEnchantSnapshot { EnchantId = Guid.NewGuid(), SlotIndex = 0, EnchantMasterId = "master", EffectId = "effect_two", Status = "FIXED", Type = "FLAT", Value = 2 }],
                },
            ],
            LearnedSkills = Section(new PlayerStateLearnedSkillsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1,
                Skills = [new PlayerStateLearnedSkillSnapshot { LearnedSkillId = learnedSkillId, SkillId = "slash", Level = 1 }],
            }),
            SkillTree = Section(new PlayerStateSkillTreeSection
            {
                AccountId = fixture.AccountId, ClientRevision = 2, ExpectedVersion = 1,
                UnlockedNodes = [new AccountSkillTreeUnlockedNodeModel { NodeId = "starter" }],
            }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(10, await fixture.DbContext.InventoryEntries.AsNoTracking().Where(entry => entry.InventoryEntryId == fixture.EntryId).Select(entry => entry.Quantity).SingleAsync());
        Assert.Equal(fixture.BaseTime, await fixture.DbContext.EquipmentInstances.AsNoTracking().Where(entity => entity.EquipmentInstanceId == equipmentId).Select(entity => entity.UpdatedAt).SingleAsync());
        Assert.Equal("effect_one", await fixture.DbContext.EquipmentInstanceEnchants.AsNoTracking().Where(enchant => enchant.EquipmentInstanceId == equipmentId).Select(enchant => enchant.EffectId).SingleAsync());
        Assert.False(await fixture.DbContext.AccountLearnedSkills.AsNoTracking().AnyAsync(skill => skill.LearnedSkillId == learnedSkillId));
        Assert.Equal(2, await fixture.DbContext.AccountSkillTreeStates.AsNoTracking().Where(state => state.AccountId == fixture.AccountId).Select(state => state.Version).SingleAsync());
    }

    [Fact]
    public async Task SaveAsync_SwapsLoadoutSlotsWithoutFilteredUniqueCollision()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var firstEquipmentId = Guid.NewGuid();
        var secondEquipmentId = Guid.NewGuid();
        var loadoutId = Guid.NewGuid();
        fixture.DbContext.EquipmentInstances.AddRange(
            CreateEquipment(firstEquipmentId, fixture),
            CreateEquipment(secondEquipmentId, fixture));
        fixture.DbContext.EquipmentLoadouts.Add(new EquipmentLoadoutEntity
        {
            EquipmentLoadoutId = loadoutId, AccountId = fixture.AccountId, LoadoutName = "main", IsActive = true,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        fixture.DbContext.EquipmentLoadoutSlots.AddRange(
            CreateLoadoutSlot(loadoutId, "WEAPON", 0, firstEquipmentId, fixture),
            CreateLoadoutSlot(loadoutId, "ACCESSORY", 0, secondEquipmentId, fixture));
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Loadouts =
            [
                new PlayerStateLoadoutSnapshot
                {
                    EquipmentLoadoutId = loadoutId, ExpectedUpdatedAt = fixture.BaseTime,
                    Slots =
                    [
                        new PlayerStateLoadoutSlotSnapshot { SlotType = "WEAPON", SlotIndex = 0, EquipmentInstanceId = secondEquipmentId },
                        new PlayerStateLoadoutSlotSnapshot { SlotType = "ACCESSORY", SlotIndex = 0, EquipmentInstanceId = firstEquipmentId },
                    ],
                },
            ],
        });

        Assert.True(result.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        var slots = await fixture.DbContext.EquipmentLoadoutSlots.AsNoTracking().Where(slot => slot.EquipmentLoadoutId == loadoutId && !slot.IsDeleted).ToDictionaryAsync(slot => slot.SlotType);
        Assert.Equal(secondEquipmentId, slots["WEAPON"].EquipmentInstanceId);
        Assert.Equal(firstEquipmentId, slots["ACCESSORY"].EquipmentInstanceId);
    }

    [Fact]
    public async Task SaveAsync_AdvancesEntryMetadataAndLoadoutTimestampsBeyondBaseline()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var equipmentId = Guid.NewGuid();
        var loadoutId = Guid.NewGuid();
        var futureBaseline = RoundToMilliseconds(DateTime.UtcNow.AddMinutes(1));
        var equipment = CreateEquipment(equipmentId, fixture);
        equipment.UpdatedAt = futureBaseline;
        fixture.DbContext.EquipmentInstances.Add(equipment);
        fixture.DbContext.EquipmentLoadouts.Add(new EquipmentLoadoutEntity
        {
            EquipmentLoadoutId = loadoutId, AccountId = fixture.AccountId, LoadoutName = "main", IsActive = true,
            CreatedAt = fixture.BaseTime, UpdatedAt = futureBaseline, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        fixture.DbContext.EquipmentLoadoutSlots.Add(CreateLoadoutSlot(loadoutId, "WEAPON", 0, equipmentId, fixture));
        var inventory = await fixture.DbContext.Inventories.SingleAsync(entity => entity.InventoryId == fixture.FirstInventoryId);
        var entry = await fixture.DbContext.InventoryEntries.SingleAsync(entity => entity.InventoryEntryId == fixture.EntryId);
        inventory.UpdatedAt = futureBaseline;
        entry.UpdatedAt = futureBaseline;
        await fixture.DbContext.SaveChangesAsync();
        var slot = await fixture.DbContext.EquipmentLoadoutSlots.SingleAsync(entity => entity.EquipmentLoadoutId == loadoutId);
        slot.UpdatedAt = futureBaseline;
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId, MetadataDirty = true, ExpectedUpdatedAt = futureBaseline, MetadataJson = "{}",
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = futureBaseline }],
                    Entries = [new PlayerStateInventoryEntrySnapshot { InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = futureBaseline, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 10 }],
                },
            ],
            Loadouts =
            [
                new PlayerStateLoadoutSnapshot
                {
                    EquipmentLoadoutId = loadoutId, ExpectedUpdatedAt = futureBaseline,
                    Slots = [new PlayerStateLoadoutSlotSnapshot { SlotType = "WEAPON", SlotIndex = 0, EquipmentInstanceId = equipmentId }],
                },
            ],
            Equipment =
            [
                new PlayerStateEquipmentSnapshot
                {
                    EquipmentInstanceId = equipmentId,
                    ExpectedUpdatedAt = futureBaseline,
                    RuneMaxSlots = 2,
                },
            ],
        });

        Assert.True(result.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        var persistedEntry = await fixture.DbContext.InventoryEntries.AsNoTracking().SingleAsync(entity => entity.InventoryEntryId == fixture.EntryId);
        var persistedInventory = await fixture.DbContext.Inventories.AsNoTracking().SingleAsync(entity => entity.InventoryId == fixture.FirstInventoryId);
        var persistedLoadout = await fixture.DbContext.EquipmentLoadouts.AsNoTracking().SingleAsync(entity => entity.EquipmentLoadoutId == loadoutId);
        var persistedEquipment = await fixture.DbContext.EquipmentInstances.AsNoTracking().SingleAsync(entity => entity.EquipmentInstanceId == equipmentId);
        Assert.True(persistedEntry.UpdatedAt > futureBaseline);
        Assert.True(persistedInventory.UpdatedAt > futureBaseline);
        Assert.True(persistedLoadout.UpdatedAt > futureBaseline);
        Assert.True(persistedEquipment.UpdatedAt > futureBaseline);
        Assert.Equal(persistedEntry.UpdatedAt, result.Ack!.Entries.Single().UpdatedAt);
        Assert.Equal(persistedInventory.UpdatedAt, result.Ack.Inventories.Single().UpdatedAt);
        Assert.Equal(persistedLoadout.UpdatedAt, result.Ack.Loadouts.Single().UpdatedAt);
        Assert.Equal(persistedEquipment.UpdatedAt, result.Ack.Equipment.Single().UpdatedAt);
    }

    [Fact]
    public async Task SaveAsync_AppliesWaystoneUnlockWithGoldAndReplayDoesNotConsumeTwice()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        const string waystoneId = "ws-20260906-eriva";
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                    Entries = [new PlayerStateInventoryEntrySnapshot { InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 9 }],
                },
            ],
            Waystones = Section(new PlayerStateWaystonesSection { ClientRevision = 7, UnlockedWaystoneIds = [waystoneId] }),
        };

        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        Assert.True(first.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(9, await fixture.DbContext.InventoryEntries.AsNoTracking().Where(entry => entry.InventoryEntryId == fixture.EntryId).Select(entry => entry.Quantity).SingleAsync());
        Assert.Equal(1, await fixture.DbContext.AccountWaystoneUnlocks.AsNoTracking().CountAsync(unlock => unlock.AccountId == fixture.AccountId && unlock.WaystoneId == waystoneId && !unlock.IsDeleted));

        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        Assert.True(replay.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(9, await fixture.DbContext.InventoryEntries.AsNoTracking().Where(entry => entry.InventoryEntryId == fixture.EntryId).Select(entry => entry.Quantity).SingleAsync());
        Assert.Equal(1, await fixture.DbContext.AccountWaystoneUnlocks.AsNoTracking().CountAsync(unlock => unlock.AccountId == fixture.AccountId && unlock.WaystoneId == waystoneId && !unlock.IsDeleted));
        Assert.Equal(7, replay.Ack!.Waystones!.Value.GetProperty("clientRevision").GetInt64());
        Assert.Equal([waystoneId], replay.Ack.Waystones.Value.GetProperty("unlockedWaystoneIds").EnumerateArray().Select(id => id.GetString()!).ToArray());

        var collision = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = request.SnapshotId, AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Waystones = Section(new PlayerStateWaystonesSection { ClientRevision = 7, UnlockedWaystoneIds = ["ws-different"] }),
        });
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, collision.Failure);

        var existingIdNoOp = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Waystones = Section(new PlayerStateWaystonesSection { ClientRevision = 8, UnlockedWaystoneIds = [waystoneId.ToUpperInvariant()] }),
        });
        Assert.True(existingIdNoOp.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(1, await fixture.DbContext.AccountWaystoneUnlocks.AsNoTracking().CountAsync(unlock => unlock.AccountId == fixture.AccountId && !unlock.IsDeleted));
        Assert.Equal([waystoneId.ToUpperInvariant()], existingIdNoOp.Ack!.Waystones!.Value.GetProperty("unlockedWaystoneIds").EnumerateArray().Select(id => id.GetString()!).ToArray());
    }

    [Fact]
    public async Task SaveAsync_RejectsDuplicateWaystoneIdsIgnoringCase()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Waystones = Section(new PlayerStateWaystonesSection
            {
                ClientRevision = 1,
                UnlockedWaystoneIds = ["ws-20260906-eriva", "WS-20260906-ERIVA"],
            }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        Assert.Empty(await fixture.DbContext.AccountWaystoneUnlocks.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_RejectsInvalidWaystoneIdsWithoutNormalizingThem()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var invalidIds = new[] { string.Empty, " ", " ws-20260906-eriva", "ws-20260906-eriva ", new string('w', 101) };

        foreach (var invalidId in invalidIds)
        {
            var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
            {
                SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
                Waystones = Section(new PlayerStateWaystonesSection { ClientRevision = 1, UnlockedWaystoneIds = [invalidId] }),
            });

            Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        }

        var nullListResult = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Waystones = JsonSerializer.SerializeToElement(new
            {
                clientRevision = 1,
                unlockedWaystoneIds = (string[]?)null,
            }),
        });
        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, nullListResult.Failure);
        Assert.Empty(await fixture.DbContext.AccountWaystoneUnlocks.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_RollsBackCoreAndEarlierSectionsWhenWaystonesSectionIsInvalid()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var learnedSkillId = Guid.NewGuid();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                    Entries = [new PlayerStateInventoryEntrySnapshot { InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 9 }],
                },
            ],
            LearnedSkills = Section(new PlayerStateLearnedSkillsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1,
                Skills = [new PlayerStateLearnedSkillSnapshot { LearnedSkillId = learnedSkillId, SkillId = "slash", Level = 1 }],
            }),
            Waystones = Section(new PlayerStateWaystonesSection { ClientRevision = 2, UnlockedWaystoneIds = [" ws-invalid"] }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(10, await fixture.DbContext.InventoryEntries.AsNoTracking().Where(entry => entry.InventoryEntryId == fixture.EntryId).Select(entry => entry.Quantity).SingleAsync());
        Assert.False(await fixture.DbContext.AccountLearnedSkills.AsNoTracking().AnyAsync(skill => skill.LearnedSkillId == learnedSkillId));
        Assert.Empty(await fixture.DbContext.AccountWaystoneUnlocks.AsNoTracking().ToListAsync());
        Assert.Empty(await fixture.DbContext.PlayerStateSnapshots.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_UsesProgressVersionAndDoesNotOverwriteModeWhenModeIsNotDirty()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var account = await fixture.DbContext.Accounts.SingleAsync();
        account.Mode = 2;
        account.MenuShortcutsJson = "[\"STATUS\"]";
        account.UpdatedAt = account.UpdatedAt.AddSeconds(5); // position/menu系の別更新を模擬する。
        await fixture.DbContext.SaveChangesAsync();
        var section = new PlayerStateAccountProgressSection
        {
            AccountId = fixture.AccountId,
            ClientRevision = 9,
            ExpectedProgressVersion = 1,
            Level = 2,
            TotalExperience = 100,
            ClassId = "adventurer",
            ClassLevel = 2,
            ClassExperience = 100,
            ClassProgresses = [new AccountClassProgressUpdateRequest { ClassId = "adventurer", Level = 2, Experience = 100 }],
            Mode = null,
        };

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            AccountProgress = JsonSerializer.SerializeToElement(section, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        });

        Assert.True(result.Succeeded);
        var saved = await fixture.DbContext.Accounts.SingleAsync();
        Assert.Equal(2, saved.Mode);
        Assert.Equal(2, saved.ProgressVersion);
        Assert.Equal(100, saved.TotalExperience);
        Assert.Equal(2, result.Ack!.AccountProgress!.Value.GetProperty("progressVersion").GetInt32());
    }

    [Fact]
    public async Task SaveAsync_PersistsQuestLoginClaimAndInventoryInOneReplayableSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var claimDate = new DateOnly(2026, 9, 5);
        var snapshotId = Guid.NewGuid();
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = snapshotId,
            AccountId = fixture.AccountId,
            UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId,
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                    Entries = [new PlayerStateInventoryEntrySnapshot
                    {
                        InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime,
                        ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 11,
                    }],
                },
            ],
            QuestState = Section(new PlayerStateQuestStateSection
            {
                AccountId = fixture.AccountId,
                ClientRevision = 4,
                ExpectedVersion = 0,
                ActiveQuests = [new AccountQuestActiveRequest
                {
                    QuestId = "alpha-quest", AcceptedAtEpochMillis = 1_700_000_000_000,
                    ReadyToTurnIn = false,
                    ObjectiveProgress = [new AccountQuestObjectiveProgressRequest { ObjectiveId = "kill", Progress = 2 }],
                }],
                Completions = [],
                Cooldowns = [],
            }),
            LoginBonusClaims = Section(new PlayerStateLoginBonusClaimsSection
            {
                ClientRevision = 7,
                ClaimDates = [claimDate],
            }),
        };

        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(first.Succeeded, first.Detail);
        Assert.True(replay.Succeeded, replay.Detail);
        Assert.Equal(11, await fixture.DbContext.InventoryEntries.AsNoTracking()
            .Where(entry => entry.InventoryEntryId == fixture.EntryId).Select(entry => entry.Quantity).SingleAsync());
        Assert.Equal(1, await fixture.DbContext.AccountQuestStates.AsNoTracking()
            .Where(state => state.AccountId == fixture.AccountId).Select(state => state.Version).SingleAsync());
        Assert.Single(await fixture.DbContext.AccountQuestActives.AsNoTracking().ToListAsync());
        Assert.Single(await fixture.DbContext.LoginBonusClaims.AsNoTracking()
            .Where(claim => claim.AccountId == fixture.AccountId && claim.ClaimDate == claimDate && !claim.IsDeleted)
            .ToListAsync());
        Assert.Equal(first.Ack!.QuestState?.GetRawText(), replay.Ack!.QuestState?.GetRawText());
        Assert.Equal(first.Ack.LoginBonusClaims?.GetRawText(), replay.Ack.LoginBonusClaims?.GetRawText());
    }

    [Fact]
    public async Task SaveAsync_RollsBackQuestAndInventoryWhenLoginClaimAlreadyExists()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var claimDate = new DateOnly(2026, 9, 5);
        fixture.DbContext.LoginBonusClaims.Add(new LoginBonusClaimEntity
        {
            LoginBonusClaimId = Guid.NewGuid(), AccountId = fixture.AccountId, ClaimDate = claimDate,
            ClaimedAt = fixture.BaseTime, CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories = [new PlayerStateInventorySnapshot
            {
                InventoryId = fixture.FirstInventoryId,
                ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                Entries = [new PlayerStateInventoryEntrySnapshot
                {
                    InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime,
                    ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 12,
                }],
            }],
            QuestState = Section(new PlayerStateQuestStateSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1, ExpectedVersion = 0,
                ActiveQuests = [],
                Completions = [new AccountQuestCompletionRequest
                    { QuestId = "alpha-quest", CompletedAtEpochMillis = 1_700_000_000_000 }],
                Cooldowns = [],
            }),
            LoginBonusClaims = Section(new PlayerStateLoginBonusClaimsSection
                { ClientRevision = 1, ClaimDates = [claimDate] }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(10, await fixture.DbContext.InventoryEntries.AsNoTracking()
            .Where(entry => entry.InventoryEntryId == fixture.EntryId).Select(entry => entry.Quantity).SingleAsync());
        Assert.Empty(await fixture.DbContext.AccountQuestStates.AsNoTracking().ToListAsync());
        Assert.Single(await fixture.DbContext.LoginBonusClaims.AsNoTracking().ToListAsync());
        Assert.Empty(await fixture.DbContext.PlayerStateSnapshots.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_ClaimsMailAndInventoryInOneReplayableSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        const string mailId = "alpha-reward-mail";
        fixture.DbContext.PlayerMailDeliveries.Add(CreateMailDelivery(mailId, fixture));
        await fixture.DbContext.SaveChangesAsync();
        var clientRevision = Guid.NewGuid();
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories = [new PlayerStateInventorySnapshot
            {
                InventoryId = fixture.FirstInventoryId,
                ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                Entries = [new PlayerStateInventoryEntrySnapshot
                {
                    InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime,
                    ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 11,
                }],
            }],
            MailClaim = Section(new PlayerStateMailClaimSection
            {
                AccountId = fixture.AccountId, ClientRevision = clientRevision, MailId = mailId,
            }),
        };

        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(first.Succeeded, first.Detail);
        Assert.True(replay.Succeeded, replay.Detail);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(11, await fixture.DbContext.InventoryEntries.AsNoTracking()
            .Where(entry => entry.InventoryEntryId == fixture.EntryId).Select(entry => entry.Quantity).SingleAsync());
        var state = await fixture.DbContext.PlayerMailStates.AsNoTracking()
            .SingleAsync(value => value.AccountId == fixture.AccountId && value.MailId == mailId);
        Assert.True(state.IsRead);
        Assert.Equal(2, state.Version);
        Assert.Equal(mailId, first.Ack!.MailClaim!.Value.GetProperty("mailId").GetString());
        Assert.Equal(clientRevision, first.Ack.MailClaim.Value.GetProperty("clientRevision").GetGuid());
        Assert.Equal(first.Ack.MailClaim?.GetRawText(), replay.Ack!.MailClaim?.GetRawText());
    }

    [Fact]
    public async Task SaveAsync_RollsBackInventoryWhenMailWasAlreadyClaimed()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        const string mailId = "already-claimed-mail";
        fixture.DbContext.PlayerMailDeliveries.Add(CreateMailDelivery(mailId, fixture));
        fixture.DbContext.PlayerMailStates.Add(new PlayerMailStateEntity
        {
            PlayerMailStateId = Guid.NewGuid(), AccountId = fixture.AccountId, MailId = mailId,
            IsRead = true, ReadAt = fixture.BaseTime, Version = 2,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories = [new PlayerStateInventorySnapshot
            {
                InventoryId = fixture.FirstInventoryId,
                ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                Entries = [new PlayerStateInventoryEntrySnapshot
                {
                    InventoryEntryId = fixture.EntryId, ExpectedUpdatedAt = fixture.BaseTime,
                    ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 12,
                }],
            }],
            MailClaim = Section(new PlayerStateMailClaimSection
            {
                AccountId = fixture.AccountId, ClientRevision = Guid.NewGuid(), MailId = mailId,
            }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(10, await fixture.DbContext.InventoryEntries.AsNoTracking()
            .Where(entry => entry.InventoryEntryId == fixture.EntryId).Select(entry => entry.Quantity).SingleAsync());
        Assert.Single(await fixture.DbContext.PlayerMailStates.AsNoTracking().ToListAsync());
        Assert.Empty(await fixture.DbContext.PlayerStateSnapshots.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_AppliesGuideProgressAndAdventureDeltasOnceOnReplay()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            GuideProgress = Section(new PlayerStateGuideProgressSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1, IsFullSnapshot = false,
                CompletedStepKeys = [new PlayerStateGuideStepKey { GuideId = "intro", StepId = "welcome" }],
            }),
            AdventureRecords = Section(new PlayerStateAdventureRecordsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 2,
                MobDefeatDeltas = [new PlayerStateMobDefeatDelta { MobId = "slime", MobCategory = "ENEMY", Delta = 2 }],
                DungeonClearDeltas = [new PlayerStateDungeonClearDelta { DungeonId = "cave", Delta = 1 }],
            }),
        };
        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(first.Succeeded, first.Detail);
        Assert.True(replay.Succeeded, replay.Detail);
        Assert.Equal(2, await fixture.DbContext.AccountMobRecords.Where(record => record.MobId == "slime")
            .Select(record => record.DefeatCount).SingleAsync());
        Assert.Equal(1, await fixture.DbContext.AccountDungeonRecords.Where(record => record.DungeonId == "cave")
            .Select(record => record.ClearCount).SingleAsync());
        Assert.Single(await fixture.DbContext.AccountGuideStepProgresses.ToListAsync());
        Assert.Equal(first.Ack!.AdventureRecords?.GetRawText(), replay.Ack!.AdventureRecords?.GetRawText());
    }

    [Fact]
    public async Task SaveAsync_RejectsInvalidAdventureDelta()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            AdventureRecords = Section(new PlayerStateAdventureRecordsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1,
                MobDefeatDeltas = [new PlayerStateMobDefeatDelta { MobId = "slime", MobCategory = "ENEMY", Delta = 0 }],
            }),
        });
        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        Assert.Empty(await fixture.DbContext.AccountMobRecords.ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_RejectsUnsupportedAdventureMobCategory()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            AdventureRecords = Section(new PlayerStateAdventureRecordsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1,
                MobDefeatDeltas = [new PlayerStateMobDefeatDelta { MobId = "slime", MobCategory = "NPC", Delta = 1 }],
            }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        Assert.Empty(await fixture.DbContext.AccountMobRecords.ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_MatchesFullGuideProgressWithoutCaseSensitiveDuplicateInsert()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        fixture.DbContext.AccountGuideStepProgresses.Add(new AccountGuideStepProgressEntity
        {
            AccountGuideStepProgressId = Guid.NewGuid(), AccountId = fixture.AccountId,
            GuideId = "Intro", StepId = "Welcome", CompletedAt = fixture.BaseTime,
            CreatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            GuideProgress = Section(new PlayerStateGuideProgressSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1, IsFullSnapshot = true,
                CompletedStepKeys = [new PlayerStateGuideStepKey { GuideId = "intro", StepId = "welcome" }],
            }),
        });

        Assert.True(result.Succeeded, result.Detail);
        Assert.Single(await fixture.DbContext.AccountGuideStepProgresses.ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_RejectsPlayerSettingVersionConflict()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var userId = (await fixture.DbContext.Accounts.SingleAsync()).UserId;
        var settingId = Guid.NewGuid();
        fixture.DbContext.PlayerSettings.Add(new PlayerSettingEntity
        {
            UserSettingId = settingId, UserId = userId, SettingKey = "ui", SettingValueJson = "{}", Version = 2,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            PlayerSettings = Section(new PlayerStatePlayerSettingsSection
            {
                UserId = userId, ClientRevision = 1,
                Settings = [new PlayerStatePlayerSettingSnapshot { UserSettingId = settingId, SettingKey = "ui", SettingValueJson = "{\"a\":1}", ExpectedVersion = 1 }],
            }),
        });
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        Assert.Equal("{}", await fixture.DbContext.PlayerSettings.Where(setting => setting.UserSettingId == settingId).Select(setting => setting.SettingValueJson).SingleAsync());
    }

    [Fact]
    public async Task SaveAsync_RejectsRenamingExistingPlayerSettingKey()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var userId = (await fixture.DbContext.Accounts.SingleAsync()).UserId;
        var settingId = Guid.NewGuid();
        fixture.DbContext.PlayerSettings.Add(new PlayerSettingEntity
        {
            UserSettingId = settingId, UserId = userId, SettingKey = "ui.locale", SettingValueJson = "{}", Version = 2,
            CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            PlayerSettings = Section(new PlayerStatePlayerSettingsSection
            {
                UserId = userId, ClientRevision = 1,
                Settings = [new PlayerStatePlayerSettingSnapshot
                {
                    UserSettingId = settingId, SettingKey = "ui.language", SettingValueJson = "{}", ExpectedVersion = 2,
                }],
            }),
        });

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        Assert.Equal("ui.locale", await fixture.DbContext.PlayerSettings.Where(setting => setting.UserSettingId == settingId)
            .Select(setting => setting.SettingKey).SingleAsync());
    }

    [Fact]
    public async Task SaveAsync_RollsBackGuideAdventureAndSettingsWhenLaterMailSectionConflicts()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var userId = (await fixture.DbContext.Accounts.SingleAsync()).UserId;
        var settingId = Guid.NewGuid();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            GuideProgress = Section(new PlayerStateGuideProgressSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1, CompletedStepKeys = [new PlayerStateGuideStepKey { GuideId = "intro", StepId = "welcome" }],
            }),
            AdventureRecords = Section(new PlayerStateAdventureRecordsSection
            {
                AccountId = fixture.AccountId, ClientRevision = 1,
                MobDefeatDeltas = [new PlayerStateMobDefeatDelta { MobId = "slime", MobCategory = "ENEMY", Delta = 1 }],
            }),
            PlayerSettings = Section(new PlayerStatePlayerSettingsSection
            {
                UserId = userId, ClientRevision = 1,
                Settings = [new PlayerStatePlayerSettingSnapshot { UserSettingId = settingId, SettingKey = "ui", SettingValueJson = "{}" }],
            }),
            MailClaim = Section(new PlayerStateMailClaimSection { AccountId = fixture.AccountId, ClientRevision = Guid.NewGuid(), MailId = "not-delivered" }),
        });
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Empty(await fixture.DbContext.AccountGuideStepProgresses.AsNoTracking().ToListAsync());
        Assert.Empty(await fixture.DbContext.AccountMobRecords.AsNoTracking().ToListAsync());
        Assert.Empty(await fixture.DbContext.PlayerSettings.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task SaveAsync_DeletesMailInReplayableSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        const string mailId = "alpha-delete-mail";
        fixture.DbContext.PlayerMailDeliveries.Add(CreateMailDelivery(mailId, fixture));
        await fixture.DbContext.SaveChangesAsync();
        var clientRevision = Guid.NewGuid();
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            MailDelete = Section(new PlayerStateMailDeleteSection
            {
                AccountId = fixture.AccountId, ClientRevision = clientRevision, MailId = mailId,
            }),
        };

        var first = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        var replay = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);

        Assert.True(first.Succeeded, first.Detail);
        Assert.True(replay.Succeeded, replay.Detail);
        fixture.DbContext.ChangeTracker.Clear();
        var state = await fixture.DbContext.PlayerMailStates.AsNoTracking()
            .SingleAsync(value => value.AccountId == fixture.AccountId && value.MailId == mailId);
        Assert.True(state.IsDeleted);
        Assert.NotNull(state.DeletedAt);
        Assert.Equal(mailId, first.Ack!.MailDelete!.Value.GetProperty("mailId").GetString());
        Assert.Equal(clientRevision, first.Ack.MailDelete.Value.GetProperty("clientRevision").GetGuid());
        Assert.Equal(first.Ack.MailDelete?.GetRawText(), replay.Ack!.MailDelete?.GetRawText());
    }

    private static JsonElement Section<T>(T section) => JsonSerializer.SerializeToElement(section, new JsonSerializerOptions(JsonSerializerDefaults.Web));

    private static DateTime RoundToMilliseconds(DateTime value)
        => new(value.Ticks - value.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);

    private static EquipmentInstanceEntity CreateEquipment(Guid equipmentId, SnapshotFixture fixture) => new()
    {
        EquipmentInstanceId = equipmentId, AccountId = fixture.AccountId, ItemId = "iron_sword", RuneMaxSlots = 2,
        CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
    };

    private static EquipmentInstanceEnchantEntity CreateEnchant(Guid enchantId, Guid equipmentId, int slotIndex, string effectId, SnapshotFixture fixture) => new()
    {
        EnchantId = enchantId, EquipmentInstanceId = equipmentId, SlotIndex = slotIndex, EnchantMasterId = "master", EffectId = effectId,
        Status = "FIXED", Type = "FLAT", Value = slotIndex + 1,
        CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
    };

    private static EquipmentInstanceRuneEntity CreateRune(Guid runeId, Guid equipmentId, int slotIndex, string itemId, SnapshotFixture fixture) => new()
    {
        RuneId = runeId, EquipmentInstanceId = equipmentId, SlotIndex = slotIndex, ItemId = itemId,
        CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
    };

    private static EquipmentLoadoutSlotEntity CreateLoadoutSlot(Guid loadoutId, string slotType, int slotIndex, Guid equipmentId, SnapshotFixture fixture) => new()
    {
        EquipmentLoadoutSlotId = Guid.NewGuid(), EquipmentLoadoutId = loadoutId, SlotType = slotType, SlotIndex = slotIndex, EquipmentInstanceId = equipmentId,
        CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime, CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
    };

    private static PlayerMailDeliveryEntity CreateMailDelivery(string mailId, SnapshotFixture fixture) => new()
    {
        PlayerMailDeliveryId = Guid.NewGuid(), AccountId = fixture.AccountId, MailId = mailId,
        PayloadJson = JsonSerializer.Serialize(new MailResponse
        {
            SchemaVersion = 1, Id = mailId, Icon = "CHEST", Title = "Reward", Body = "Alpha reward",
            PublishFrom = fixture.BaseTime.AddMinutes(-1), PublishTo = fixture.BaseTime.AddHours(1),
            FirstLoginOnly = false, ReceiveOnRead = true,
            Rewards = [new MailRewardResponse { ItemId = "gold", Category = "CURRENCY", Amount = 1 }],
        }, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        Version = 1, CreatedAt = fixture.BaseTime, UpdatedAt = fixture.BaseTime,
        CreatedBy = fixture.AccountId, UpdatedBy = fixture.AccountId,
    };

    private sealed class SnapshotFixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection;
        public AstralRecordDbContext DbContext { get; }
        public Guid AccountId { get; }
        public Guid FirstInventoryId { get; }
        public Guid SecondInventoryId { get; }
        public Guid EntryId { get; }
        public DateTime BaseTime { get; }

        private SnapshotFixture(SqliteConnection connection, AstralRecordDbContext dbContext,
            Guid accountId, Guid firstInventoryId, Guid secondInventoryId, Guid entryId, DateTime baseTime)
        {
            this.connection = connection;
            DbContext = dbContext;
            AccountId = accountId;
            FirstInventoryId = firstInventoryId;
            SecondInventoryId = secondInventoryId;
            EntryId = entryId;
            BaseTime = baseTime;
        }

        public static async Task<SnapshotFixture> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
            var dbContext = new AstralRecordDbContext(options);
            await dbContext.Database.EnsureCreatedAsync();
            await dbContext.Database.ExecuteSqlRawAsync("""
                CREATE UNIQUE INDEX test_loadout_position ON equipment_loadout_slot
                    (equipment_loadout_id, slot_type COLLATE NOCASE, slot_index) WHERE is_deleted = 0;
                CREATE UNIQUE INDEX test_loadout_equipment ON equipment_loadout_slot
                    (equipment_loadout_id, equipment_instance_id) WHERE is_deleted = 0;
                CREATE UNIQUE INDEX test_rune_slot ON equipment_instance_rune
                    (equipment_instance_id, slot_index);
                CREATE UNIQUE INDEX test_inventory_slot ON inventory_entry
                    (inventory_id, slot_index) WHERE is_deleted = 0 AND slot_index IS NOT NULL;
                CREATE UNIQUE INDEX test_inventory_stack ON inventory_entry
                    (inventory_id, item_id COLLATE NOCASE) WHERE is_deleted = 0 AND slot_index IS NULL
                    AND item_id IS NOT NULL AND instance_type IS NULL AND instance_id IS NULL;
                """);
            var accountId = Guid.NewGuid();
            var firstInventoryId = Guid.NewGuid();
            var secondInventoryId = Guid.NewGuid();
            var entryId = Guid.NewGuid();
            var time = DateTime.SpecifyKind(DateTime.UtcNow.AddMinutes(-1), DateTimeKind.Utc);
            dbContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId, UserId = Guid.NewGuid(), AccountName = "snapshot-test",
                Level = 1, ClassId = "adventurer", ClassLevel = 1, ProgressVersion = 1,
                CreatedAt = time, UpdatedAt = time, CreatedBy = accountId, UpdatedBy = accountId,
            });
            dbContext.Inventories.AddRange(
                new InventoryEntity { InventoryId = firstInventoryId, AccountId = accountId, InventoryType = "CURRENCY", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = time, UpdatedAt = time, CreatedBy = accountId, UpdatedBy = accountId },
                new InventoryEntity { InventoryId = secondInventoryId, AccountId = accountId, InventoryType = "CURRENCY", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = time, UpdatedAt = time, CreatedBy = accountId, UpdatedBy = accountId });
            dbContext.InventoryEntries.Add(new InventoryEntryEntity
            {
                InventoryEntryId = entryId, InventoryId = firstInventoryId, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 10,
                CreatedAt = time, UpdatedAt = time, CreatedBy = accountId, UpdatedBy = accountId,
            });
            await dbContext.SaveChangesAsync();
            return new SnapshotFixture(connection, dbContext, accountId, firstInventoryId, secondInventoryId, entryId, time);
        }

        public PlayerStateSnapshotSaveRequest CreateMoveRequest(Guid snapshotId, long quantity = 10) => new()
        {
            SnapshotId = snapshotId,
            AccountId = AccountId,
            UpdatedBy = AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = FirstInventoryId,
                    EntryMode = "DELTA",
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = EntryId, UpdatedAt = BaseTime }],
                    Entries = [],
                },
                new PlayerStateInventorySnapshot
                {
                    InventoryId = SecondInventoryId, EntryMode = "DELTA", ExpectedEntries = [],
                    Entries = [new PlayerStateInventoryEntrySnapshot { InventoryEntryId = EntryId, ExpectedUpdatedAt = BaseTime, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = quantity }],
                },
            ],
        };

        public async ValueTask DisposeAsync()
        {
            await DbContext.DisposeAsync();
            await connection.DisposeAsync();
        }
    }
}
