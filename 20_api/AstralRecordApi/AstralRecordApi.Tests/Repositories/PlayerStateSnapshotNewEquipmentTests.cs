using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using System.Text.Json.Nodes;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed partial class PlayerStateSnapshotRepositoryTests
{
    [Theory]
    [InlineData(1, 0, false, false)]
    [InlineData(0, 1, false, false)]
    [InlineData(0, 0, true, false)]
    [InlineData(0, 0, false, true)]
    public async Task SaveAsync_CreatesLocallyModifiedEquipmentWithPaymentAndReplaysAck(
        int enhanceLevel, int transcendenceRank, bool enchanted, bool runeAttached)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var payment = await fixture.DbContext.InventoryEntries.SingleAsync();
        payment.ItemCategory = "orb";
        payment.ItemId = "test_orb";
        payment.Quantity = 1;
        await fixture.DbContext.SaveChangesAsync();
        var request = CreateLocallyModifiedEquipmentRequest(fixture, enhanceLevel, transcendenceRank, enchanted, runeAttached);
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);

        var first = await repository.SaveAsync(request);
        Assert.True(first.Succeeded, first.Detail);
        var replay = await repository.SaveAsync(request);
        Assert.True(replay.Succeeded, replay.Detail);
        Assert.Equal(first.Ack!.Equipment.Single().UpdatedAt, replay.Ack!.Equipment.Single().UpdatedAt);

        fixture.DbContext.ChangeTracker.Clear();
        var equipment = await fixture.DbContext.EquipmentInstances.SingleAsync();
        Assert.Equal(enhanceLevel, equipment.EnhanceLevel);
        Assert.Equal(transcendenceRank, equipment.TranscendenceRank);
        Assert.Equal(200, equipment.DurabilityMax);
        Assert.Equal(200, equipment.DurabilityValue);
        Assert.Equal("48", (await fixture.DbContext.EquipmentInstanceStatRolls.SingleAsync()).RandomMin);
        Assert.Equal(enchanted ? 1 : 0, await fixture.DbContext.EquipmentInstanceEnchants.CountAsync());
        Assert.Equal(runeAttached ? 1 : 0, await fixture.DbContext.EquipmentInstanceRunes.CountAsync());
        Assert.True((await fixture.DbContext.InventoryEntries.SingleAsync(e => e.InventoryEntryId == fixture.EntryId)).IsDeleted);
        Assert.Equal(equipment.EquipmentInstanceId,
            (await fixture.DbContext.InventoryEntries.SingleAsync(e => !e.IsDeleted)).InstanceId);
        Assert.Single(await fixture.DbContext.PlayerStateSnapshots.ToListAsync());
    }

    [Theory]
    [InlineData("negativeEnhance")]
    [InlineData("negativeRank")]
    [InlineData("invalidRuneSlot")]
    [InlineData("duplicateEnchantEffect")]
    [InlineData("missingItem")]
    [InlineData("unexpectedVersion")]
    public async Task SaveAsync_RejectsInvalidNewEquipmentWithoutConsumingPayment(string invalidField)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var options = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        var json = JsonSerializer.SerializeToNode(
            CreateLocallyModifiedEquipmentRequest(fixture, 1, 1, true, true), options)!;
        var equipment = json["equipment"]![0]!;
        switch (invalidField)
        {
            case "negativeEnhance": equipment["enhanceLevel"] = -1; break;
            case "negativeRank": equipment["transcendenceRank"] = -1; break;
            case "invalidRuneSlot": equipment["runes"]![0]!["slotIndex"] = 1; break;
            case "duplicateEnchantEffect":
                var duplicate = equipment["enchants"]![0]!.DeepClone();
                duplicate["enchantId"] = Guid.NewGuid();
                duplicate["slotIndex"] = 1;
                duplicate["effectId"] = " TEST_EFFECT ";
                equipment["enchants"]!.AsArray().Add(duplicate);
                break;
            case "missingItem": equipment["itemId"] = null; break;
            case "unexpectedVersion": equipment["expectedUpdatedAt"] = fixture.BaseTime; break;
        }

        var result = await new PlayerStateSnapshotRepository(fixture.DbContext)
            .SaveAsync(json.Deserialize<PlayerStateSnapshotSaveRequest>(options)!);

        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.False((await fixture.DbContext.InventoryEntries.SingleAsync()).IsDeleted);
        Assert.Equal(10, (await fixture.DbContext.InventoryEntries.SingleAsync()).Quantity);
        Assert.Empty(await fixture.DbContext.EquipmentInstances.ToListAsync());
        Assert.Empty(await fixture.DbContext.PlayerStateSnapshots.ToListAsync());
    }

    private static PlayerStateSnapshotSaveRequest CreateLocallyModifiedEquipmentRequest(
        SnapshotFixture fixture, int enhanceLevel, int transcendenceRank, bool enchanted, bool runeAttached)
    {
        var equipmentId = Guid.NewGuid();
        return new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories =
            [
                new PlayerStateInventorySnapshot
                {
                    InventoryId = fixture.FirstInventoryId, EntryMode = "DELTA",
                    ExpectedEntries = [new PlayerStateExpectedInventoryEntry
                        { InventoryEntryId = fixture.EntryId, UpdatedAt = fixture.BaseTime }],
                    DeletedEntryIds = [fixture.EntryId],
                    Entries = [new PlayerStateInventoryEntrySnapshot
                    {
                        InventoryEntryId = Guid.NewGuid(), SlotIndex = 0, ItemCategory = "equipment",
                        ItemId = "test_spear", InstanceType = "EQUIPMENT", InstanceId = equipmentId, Quantity = 1,
                    }],
                },
            ],
            Equipment =
            [
                new PlayerStateEquipmentSnapshot
                {
                    EquipmentInstanceId = equipmentId, IsNew = true, ItemId = "test_spear",
                    EnhanceLevel = enhanceLevel, TranscendenceRank = transcendenceRank,
                    RuneMaxSlots = 1, DurabilityMax = 200, DurabilityValue = 200,
                    StatRolls = [new PlayerStateEquipmentStatRollSnapshot
                    {
                        StatRollId = Guid.NewGuid(), Status = "MELEE_ATTACK", Min = "48", Max = "60", SortOrder = 0,
                    }],
                    Enchants = enchanted ? [new PlayerStateEquipmentEnchantSnapshot
                    {
                        EnchantId = Guid.NewGuid(), SlotIndex = 0, EnchantMasterId = "test_enchant",
                        EffectId = "test_effect", Status = "MELEE_ATTACK", Type = "FLAT", Value = 3,
                    }] : [],
                    Runes = runeAttached ? [new PlayerStateEquipmentRuneSnapshot
                    {
                        RuneId = Guid.NewGuid(), SlotIndex = 0, ItemId = "test_rune",
                    }] : [],
                },
            ],
        };
    }
}
