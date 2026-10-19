using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public sealed class DefenseConfigurationValidatorTests
{
    [Fact]
    public void Missing_UndecidedValuesRemainRequired()
    {
        var missing = DefenseConfigurationValidator.Missing(new DefenseConfiguration());

        Assert.Contains("configuration.coreMaxHealth", missing);
        Assert.Contains("configuration.playerSpawnCoefficient", missing);
        Assert.Contains("configuration.maxSpawnsPerTick", missing);
        Assert.Contains("configuration.reviveShieldMode", missing);
        Assert.Contains("configuration.rewardTiers", missing);
    }

    [Fact]
    public void Missing_CompleteConfigurationAllowsStart()
    {
        var configuration = Complete();
        Assert.Empty(DefenseConfigurationValidator.Missing(configuration));
    }

    [Fact]
    public void Missing_RejectsIncompleteRewardRow()
    {
        var configuration = new DefenseConfiguration { RewardTiers = [new(null, null, null)] };
        Assert.Contains("configuration.rewardTiers", DefenseConfigurationValidator.Missing(configuration));
    }

    private static DefenseConfiguration Complete() => new()
    {
        WorldName = "defense_a", CoreX = 0, CoreY = 70, CoreZ = 0,
        EntryX = 10, EntryY = 70, EntryZ = 10, SpawnPoints = [new(30, 70, 30)],
        CoreMaxHealth = 1000, CoreDefense = 0,
        BaseSpawnCount = 2, SpawnIntervalSeconds = 10, MaxSpawnsPerWave = 10,
        MaxSpawnsPerTick = 2, MaxAliveEnemies = 30,
        RetaliationSeconds = 3, RetaliationRange = 12,
        PlayerSpawnCoefficient = 0.01, PlayerHealthCoefficient = 0.01,
        PlayerDamageCoefficient = 0.01, PostMaxLevelHealthCoefficient = 0.01,
        PostMaxLevelDamageCoefficient = 0.01, RegistrationLeadSeconds = 60,
        ReconnectGraceSeconds = 60, ReviveProtectionSeconds = 0,
        ReviveShieldMode = "EMPTY", RewardEveryLevels = 5, MinimumActiveSeconds = 20,
        RewardTiers = [new(1, "test-orb", 1)],
    };
}
