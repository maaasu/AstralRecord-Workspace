using System.Globalization;
using AstralRecordApi.Models;
using AstralRecordApi.Utilities;
using Xunit;

namespace AstralRecordApi.Tests.Utilities;

public class PlayerAdminEquipmentRollerTests
{
    private static readonly Guid AccountId = Guid.Parse("00000000-0000-0000-0000-000000000001");
    private static readonly Guid Actor = Guid.Parse("00000000-0000-0000-0000-000000000002");
    private static readonly DateTime Now = new(2026, 10, 2, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void CreateResolvesFixedValuesAndInitialStateLikePlugin()
    {
        var item = MakeItem(new ItemEquipmentResponse
        {
            Slot = "WEAPON",
            Rune = new ItemEquipmentRuneResponse { MaxSlots = " 2 " },
            Durability = new ItemEquipmentDurabilityResponse { Max = 83 },
            Stats =
            [
                new() { Status = "  ATTACK  ", Value = new() { Min = "0010.5000", Max = "12.000" } },
                new() { Status = "  ", Value = new() { Min = "999", Max = "999" } },
                new() { Status = "CRITICAL", Value = new() { Min = "-0.2500", Max = "0.125000" } },
            ],
        });

        var (instance, rolls) = PlayerAdminEquipmentRoller.Create(item, AccountId, Actor, Now, new Random(1));

        Assert.NotEqual(Guid.Empty, instance.EquipmentInstanceId);
        Assert.Equal(AccountId, instance.AccountId);
        Assert.Equal(item.Id, instance.ItemId);
        Assert.Equal(0, instance.EnhanceLevel);
        Assert.Equal(2, instance.RuneMaxSlots);
        Assert.Equal(0, instance.TranscendenceRank);
        Assert.Equal(83, instance.DurabilityMax);
        Assert.Equal(83, instance.DurabilityValue);
        Assert.Equal(Now, instance.CreatedAt);
        Assert.Equal(Now, instance.UpdatedAt);
        Assert.Equal(Actor, instance.CreatedBy);
        Assert.Equal(Actor, instance.UpdatedBy);
        Assert.False(instance.IsDeleted);
        Assert.Collection(rolls,
            first => AssertRoll(first, instance.EquipmentInstanceId, "ATTACK", "10.5", "12", 0),
            second => AssertRoll(second, instance.EquipmentInstanceId, "CRITICAL", "-0.25", "0.125", 1));
        Assert.NotEqual(rolls[0].StatRollId, rolls[1].StatRollId);
    }

    [Fact]
    public void CreateDefaultsMissingAndNegativeValuesToZero()
    {
        var item = MakeItem(new ItemEquipmentResponse
        {
            Slot = "ARMOR",
            Rune = new ItemEquipmentRuneResponse { MaxSlots = "-4" },
            Durability = new ItemEquipmentDurabilityResponse { Max = -10 },
            Stats = [new() { Status = "HP" }],
        });

        var (instance, rolls) = PlayerAdminEquipmentRoller.Create(item, AccountId, Actor, Now);

        Assert.Equal(0, instance.RuneMaxSlots);
        Assert.Equal(0, instance.DurabilityMax);
        Assert.Equal(0, instance.DurabilityValue);
        Assert.Equal("0", Assert.Single(rolls).RandomMin);
        Assert.Equal("0", rolls[0].RandomMax);
    }

    [Theory]
    [InlineData(" 7 ～ 3 ", 3, 7)]
    [InlineData("-3~2", -3, 2)]
    [InlineData("2147483646~2147483647", 2147483646, 2147483647)]
    [InlineData("-2147483648~2147483647", int.MinValue, int.MaxValue)]
    public void CreateSamplesIntegerRangesWithinInclusiveBounds(string raw, int min, int max)
    {
        var item = MakeItem(new ItemEquipmentResponse
        {
            Slot = "WEAPON",
            Rune = new ItemEquipmentRuneResponse { MaxSlots = raw },
            Stats = [new() { Status = "ATTACK", Value = new() { Min = raw, Max = raw } }],
        });

        for (var seed = 0; seed < 64; seed++)
        {
            var (instance, rolls) = PlayerAdminEquipmentRoller.Create(item, AccountId, Actor, Now, new Random(seed));
            Assert.InRange(instance.RuneMaxSlots, Math.Max(0, min), Math.Max(0, max));
            var roll = Assert.Single(rolls);
            Assert.InRange(int.Parse(roll.RandomMin, CultureInfo.InvariantCulture), min, max);
            Assert.InRange(int.Parse(roll.RandomMax, CultureInfo.InvariantCulture), min, max);
        }
    }

    [Fact]
    public void CreateSamplesDecimalRangesIndependentlyAndRoundsToFourPlaces()
    {
        var item = MakeItem(new ItemEquipmentResponse
        {
            Slot = "WEAPON",
            Stats = [new() { Status = "RATE", Value = new() { Min = "1.0～2.0", Max = "-2.0~-1.0" } }],
        });

        for (var seed = 0; seed < 64; seed++)
        {
            var (_, rolls) = PlayerAdminEquipmentRoller.Create(item, AccountId, Actor, Now, new Random(seed));
            var roll = Assert.Single(rolls);
            var min = decimal.Parse(roll.RandomMin, CultureInfo.InvariantCulture);
            var max = decimal.Parse(roll.RandomMax, CultureInfo.InvariantCulture);
            Assert.InRange(min, 1m, 2m);
            Assert.InRange(max, -2m, -1m);
            Assert.Equal(decimal.Round(min, 4), min);
            Assert.Equal(decimal.Round(max, 4), max);
            Assert.DoesNotContain('~', roll.RandomMin);
            Assert.DoesNotContain('~', roll.RandomMax);
        }
    }

    [Fact]
    public void CreateRejectsUnrepresentableMasterNumericValue()
    {
        var item = MakeItem(new ItemEquipmentResponse
        {
            Slot = "WEAPON",
            Stats = [new() { Status = "ATTACK", Value = new() { Min = "1~2~3", Max = "0" } }],
        });

        Assert.Throws<FormatException>(() => PlayerAdminEquipmentRoller.Create(item, AccountId, Actor, Now));
    }

    private static ItemResponse MakeItem(ItemEquipmentResponse equipment) => new()
    {
        SchemaVersion = 1,
        Id = "test_sword",
        Category = "WEAPON",
        Name = "Test Sword",
        Icon = "IRON_SWORD",
        Rarity = "COMMON",
        Equipment = equipment,
    };

    private static void AssertRoll(
        AstralRecordApi.Data.Entities.EquipmentInstanceStatRollEntity roll,
        Guid instanceId,
        string status,
        string min,
        string max,
        int sortOrder)
    {
        Assert.NotEqual(Guid.Empty, roll.StatRollId);
        Assert.Equal(instanceId, roll.EquipmentInstanceId);
        Assert.Equal(status, roll.Status);
        Assert.Equal(min, roll.RandomMin);
        Assert.Equal(max, roll.RandomMax);
        Assert.Equal(sortOrder, roll.SortOrder);
        Assert.Equal(Now, roll.CreatedAt);
        Assert.Equal(Now, roll.UpdatedAt);
        Assert.Equal(Actor, roll.CreatedBy);
        Assert.Equal(Actor, roll.UpdatedBy);
    }
}
