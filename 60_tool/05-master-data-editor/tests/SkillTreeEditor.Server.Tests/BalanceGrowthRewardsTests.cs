using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceGrowthRewardsTests
{
    private static readonly Guid Account = Guid.Parse("00000000-0000-0000-0000-000000000001");

    [Fact]
    public void ExperienceAppliesAbsoluteLevelPenaltyThenRoundsBeforeStatusRate()
    {
        Assert.Equal(3, BalanceGrowthRewards.ExperiencePerVictory(2, 1, 5, 125));
        Assert.Equal(3, BalanceGrowthRewards.ExperiencePerVictory(2, 5, 1, 125));
        Assert.Equal(10, BalanceGrowthRewards.ExperiencePerVictory(100, 1, 100, 100));
        Assert.Equal(1, BalanceGrowthRewards.ExperiencePerVictory(1, 1, 100, 100));
        Assert.Equal(0, BalanceGrowthRewards.ExperiencePerVictory(1, 1, 2, 25));
        Assert.Equal(0, BalanceGrowthRewards.ExperiencePerVictory(100, 1, 1, 0));
        Assert.Equal(0, BalanceGrowthRewards.ExperiencePerVictory(0, 1, 1, 100));
        Assert.Equal(int.MaxValue, BalanceGrowthRewards.ExperiencePerVictory(int.MaxValue, 1, 1, 200));
        Assert.Equal(100, BalanceGrowthRewards.ExperiencePerVictory(100, 1, 1, double.NaN));
    }

    [Fact]
    public void PlayerLevelJourneyKeepsExcessExpAndRecomputesVictoryGainAtNewLevel()
    {
        var firstThreshold = MasterAnalyticsService.PlayerExperienceForNextLevel(Account, 1);
        var secondThreshold = MasterAnalyticsService.PlayerExperienceForNextLevel(Account, 2);
        var baseExp = checked((int)firstThreshold + 1);
        var secondLevelGain = BalanceGrowthRewards.ExperiencePerVictory(baseExp, 2, 1, 100);
        var secondLevelWins = 1 + (secondThreshold - 2) / secondLevelGain;

        Assert.Equal(1, BalanceGrowthRewards.WinningKillsToPlayerLevel(Account, 1, 2, baseExp, 1, 100));
        Assert.Equal(1 + secondLevelWins,
            BalanceGrowthRewards.WinningKillsToPlayerLevel(Account, 1, 3, baseExp, 1, 100));
        Assert.Equal(2, BalanceGrowthRewards.WinningKillsForExperience(Account, 1,
            firstThreshold + 1 + secondLevelGain, baseExp, 1, 100));
        Assert.Equal(3, BalanceGrowthRewards.WinningKillsForExperience(Account, 1,
            firstThreshold + 2 + secondLevelGain, baseExp, 1, 100));
    }

    [Fact]
    public void ASingleLargeVictoryCanCrossSeveralLevelsWithoutExtraKills()
    {
        Assert.Equal(1, BalanceGrowthRewards.WinningKillsToPlayerLevel(Account, 1, 200,
            int.MaxValue, 1, 100));
        Assert.Equal(0, BalanceGrowthRewards.WinningKillsToPlayerLevel(Account, 20, 20, 0, 1, 0));
        Assert.Equal(0, BalanceGrowthRewards.WinningKillsForExperience(Account, 1, 0, 0, 1, 0));
    }

    [Fact]
    public void ZeroAwardIsUnreachableAndInvalidJourneyInputsAreRejected()
    {
        Assert.Equal(double.PositiveInfinity,
            BalanceGrowthRewards.WinningKillsToPlayerLevel(Account, 1, 2, 100, 1, 0));
        Assert.Equal(double.PositiveInfinity,
            BalanceGrowthRewards.WinningKillsForExperience(Account, 1, 1, 0, 1, 100));
        Assert.Throws<ArgumentException>(() => BalanceGrowthRewards.WinningKillsToPlayerLevel(
            Guid.Empty, 1, 2, 100, 1, 100));
        Assert.Throws<ArgumentOutOfRangeException>(() => BalanceGrowthRewards.WinningKillsToPlayerLevel(
            Account, 1, 201, 100, 1, 100));
        Assert.Throws<ArgumentOutOfRangeException>(() => BalanceGrowthRewards.WinningKillsForExperience(
            Account, 1, -1, 100, 1, 100));
    }
}
