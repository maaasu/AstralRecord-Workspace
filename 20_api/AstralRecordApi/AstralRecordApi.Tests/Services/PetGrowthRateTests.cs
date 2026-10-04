using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public class PetGrowthRateTests
{
    [Theory]
    [InlineData(1, 1)]
    [InlineData(17, .625)]
    [InlineData(33, .25)]
    [InlineData(-100, 1)]
    [InlineData(100, .25)]
    public void GrowthRate_UsesSpeciesRangeAndClampsBaseValues(double value, double expected)
    {
        var (master, species, details) = Fixture(value);
        Assert.Equal(expected, PetGenetics.GrowthRate(master, species, details), 10);
    }

    [Fact]
    public void GrowthRate_DecreasesWhenAnyBaseStatIncreases()
    {
        var (master, species, details) = Fixture(1);
        var previous = PetGenetics.GrowthRate(master, species, details);
        foreach (var id in species.Stats.Keys)
        {
            details.Stats[id].BaseValue = 33;
            var rate = PetGenetics.GrowthRate(master, species, details);
            Assert.True(rate < previous);
            previous = rate;
        }
        Assert.Equal(.25, previous, 10);
    }

    [Fact]
    public void GrowthRate_NormalizesEachStatBeforeAveragingDifferentScales()
    {
        var (master, species, details) = Fixture(17);
        foreach (var tier in species.Stats["POWER"].Tiers)
        {
            tier.Min *= 100;
            tier.Max *= 100;
        }
        details.Stats["POWER"].BaseValue *= 100;
        Assert.Equal(.625, PetGenetics.GrowthRate(master, species, details), 10);
    }

    [Fact]
    public void GrowthRate_IgnoresCurrentValuesPotentialAndLevel()
    {
        var (master, species, details) = Fixture(17);
        details.Level = 80;
        foreach (var stat in details.Stats.Values)
        {
            stat.CurrentValue = 100000;
            stat.Potential = true;
            stat.Tier = 10;
        }
        Assert.Equal(.625, PetGenetics.GrowthRate(master, species, details), 10);
    }

    [Theory]
    [InlineData(0, 0)]
    [InlineData(.5, 32)]
    [InlineData(1, 64)]
    public void Grow_MultipliesBothRatesBeforeSingleFloor(double activityRate, long expected)
    {
        var (master, species, details) = Fixture(17);
        master.Experience.ActivityRate = activityRate;
        PetGenetics.Grow(master, species, details, 103, new Random(1));
        Assert.Equal(expected, details.Experience);
        Assert.Equal(1, details.Level);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(-.1)]
    [InlineData(double.NaN)]
    [InlineData(double.PositiveInfinity)]
    public void Validator_RejectsInvalidMinimumRate(double value)
    {
        var (master, _, _) = Fixture(1);
        master.Experience.GrowthRateMin = value;
        Assert.Contains("experience growth rate", Assert.Throws<InvalidOperationException>(
            () => PetMasterValidator.Validate(master)).Message);
    }

    [Theory]
    [InlineData(0, 0)]
    [InlineData(-1, 1)]
    [InlineData(1, -1)]
    [InlineData(double.NaN, 1)]
    [InlineData(1, double.PositiveInfinity)]
    [InlineData(1E19, 0)]
    [InlineData(0, 1E10)]
    public void Validator_RejectsInvalidActivityGain(double baseValue, double perLevel)
    {
        foreach (var combat in new[] { true, false })
        {
            var (master, _, _) = Fixture(1);
            var gain = combat ? master.Experience.CombatGain : master.Experience.GatheringGain;
            gain.Base = baseValue; gain.PerLevel = perLevel;
            Assert.Contains("activity experience gain", Assert.Throws<InvalidOperationException>(
                () => PetMasterValidator.Validate(master)).Message);
        }
    }

    [Theory]
    [InlineData(0, 1)]
    [InlineData(1, 0)]
    public void Validator_AcceptsOnePositiveGainCoefficient(double baseValue, double perLevel)
    {
        var (master, _, _) = Fixture(1);
        master.Experience.CombatGain = new() { Base = baseValue, PerLevel = perLevel };
        master.Experience.GatheringGain = new() { Base = baseValue, PerLevel = perLevel };
        PetMasterValidator.Validate(master);
    }

    private static (PetMasterResponse Master, PetSpeciesMaster Species, PetDetailsResponse Details) Fixture(double value)
    {
        var species = new PetSpeciesMaster
        {
            Id = "wolf", EggItemId = "egg_wolf", PetItemId = "pet_wolf", Names = ["コハク"],
            BasicAttack = new() { DamageRatio = .1, CooldownSeconds = 5, Range = 3 },
            HatchMaterials = [new() { ItemId = "flower", Quantity = 1 }],
            Stats = new[] { "VITALITY", "POWER", "DEFENSE", "EVASION", "SUPPORT" }.ToDictionary(id => id, _ => new PetStatMaster
            {
                GrowthPerLevel = 1, Scale = 40, InheritanceMax = .5,
                Tiers = Enumerable.Range(0, 11).Select(t => new PetTierMaster
                    { Tier = t, Min = t * 3 + 1, Max = t * 3 + 3, Weight = 1 }).ToList(),
            }),
            Skills = Enumerable.Range(0, 8).Select(s => new PetSkillMaster
                { Id = "s" + s, Tier = s / 2, CooldownSeconds = 10 }).ToList(),
        };
        var master = new PetMasterResponse
        {
            Species = [species],
            Rules = new() { BreedMaterials = [new() { ItemId = "flower", Quantity = 1 }],
                ReviveMaterials = [new() { ItemId = "flower", Quantity = 1 }] },
        };
        var details = new PetDetailsResponse
        {
            Stats = species.Stats.Keys.ToDictionary(id => id, _ => new PetStatResponse
                { BaseValue = value, CurrentValue = value }),
            Skills = [new() { Id = "s0", Tier = 0, SlotIndex = 0 }],
        };
        return (master, species, details);
    }
}
