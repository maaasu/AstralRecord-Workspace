namespace SkillTreeEditor.Server.Services;

/// <summary>Plugin MobDropService's level-difference and EXP-rate order, then normal player growth.</summary>
public static class BalanceGrowthRewards
{
    /// <summary>EXP for one victory. Non-finite status rates use the Plugin's neutral 100% fallback.</summary>
    public static int ExperiencePerVictory(int baseExp, int playerLevel, int mobLevel, double experienceRate)
    {
        if (baseExp <= 0) return 0;
        var gap = Math.Abs((long)Math.Max(1, playerLevel) - Math.Max(1, mobLevel));
        var multiplier = Math.Max(0.10, 1.0 - gap * 0.05);
        // Java Math.round is floor(x + 0.5) for these nonnegative amounts.
        var levelAdjusted = Math.Max(1, (int)Math.Floor(baseExp * multiplier + 0.5));
        var rate = double.IsFinite(experienceRate) ? Math.Max(0, experienceRate) : 100.0;
        var adjusted = levelAdjusted * rate / 100.0;
        if (!double.IsFinite(adjusted) || adjusted >= int.MaxValue) return int.MaxValue;
        return (int)Math.Floor(adjusted + 0.5);
    }

    /// <summary>Winning kills from the instant the initial level was reached, retaining EXP carry at level-ups.</summary>
    public static double WinningKillsToPlayerLevel(Guid uuid, int playerLevel, int targetLevel,
        int baseExp, int mobLevel, double rate)
    {
        Validate(uuid, playerLevel);
        if (targetLevel is < 1 or > 200) throw new ArgumentOutOfRangeException(nameof(targetLevel));
        if (targetLevel <= playerLevel) return 0;
        long kills = 0, carry = 0;
        for (var level = playerLevel; level < targetLevel;)
        {
            var threshold = MasterAnalyticsService.PlayerExperienceForNextLevel(uuid, level);
            if (carry >= threshold) { carry -= threshold; level++; continue; }
            var gain = ExperiencePerVictory(baseExp, level, mobLevel, rate);
            if (gain == 0) return double.PositiveInfinity;
            var chunk = CeilingDivide(threshold - carry, gain);
            kills += chunk;
            carry += chunk * gain;
        }
        return kills;
    }

    /// <summary>Winning kills needed to earn at least requiredExp total EXP, including changes of player level.</summary>
    public static double WinningKillsForExperience(Guid uuid, int playerLevel, long requiredExp,
        int baseExp, int mobLevel, double rate)
    {
        Validate(uuid, playerLevel);
        if (requiredExp < 0) throw new ArgumentOutOfRangeException(nameof(requiredExp));
        if (requiredExp == 0) return 0;
        long kills = 0, earned = 0, carry = 0;
        var level = playerLevel;
        while (true)
        {
            if (level < 200)
            {
                var threshold = MasterAnalyticsService.PlayerExperienceForNextLevel(uuid, level);
                if (carry >= threshold) { carry -= threshold; level++; continue; }
            }
            var gain = ExperiencePerVictory(baseExp, level, mobLevel, rate);
            if (gain == 0) return double.PositiveInfinity;
            var toGoal = CeilingDivide(requiredExp - earned, gain);
            if (level == 200) return kills + (double)toGoal;
            var toLevel = CeilingDivide(MasterAnalyticsService.PlayerExperienceForNextLevel(uuid, level) - carry, gain);
            if (toGoal <= toLevel) return kills + (double)toGoal;
            kills += toLevel;
            var gained = toLevel * gain;
            earned += gained;
            carry += gained;
        }
    }

    private static long CeilingDivide(long positiveNumerator, int positiveDenominator)
        => 1 + (positiveNumerator - 1) / positiveDenominator;

    private static void Validate(Guid uuid, int playerLevel)
    {
        if (uuid == Guid.Empty) throw new ArgumentException("アカウントUUIDを指定してください。", nameof(uuid));
        if (playerLevel is < 1 or > 200) throw new ArgumentOutOfRangeException(nameof(playerLevel));
    }
}
