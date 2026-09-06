using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class PlayerStateSnapshotRepositoryTests
{
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
    public async Task SaveAsync_RejectsInventoryWhenExpectedEntrySetOmitsConcurrentAddition()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        fixture.DbContext.InventoryEntries.Add(new InventoryEntryEntity
        {
            InventoryEntryId = Guid.NewGuid(),
            InventoryId = fixture.FirstInventoryId,
            ItemCategory = "CURRENCY",
            ItemId = "gold",
            Quantity = 1,
            CreatedAt = fixture.BaseTime,
            UpdatedAt = fixture.BaseTime,
            CreatedBy = fixture.AccountId,
            UpdatedBy = fixture.AccountId,
        });
        await fixture.DbContext.SaveChangesAsync();

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext)
            .SaveAsync(fixture.CreateMoveRequest(Guid.NewGuid()));

        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, result.Failure);
        Assert.Contains("baseline", result.Detail!, StringComparison.OrdinalIgnoreCase);
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
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = EntryId, UpdatedAt = BaseTime }],
                    Entries = [],
                },
                new PlayerStateInventorySnapshot
                {
                    InventoryId = SecondInventoryId, ExpectedEntries = [],
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
