using AstralRecordApi.Models;
using System.Text.RegularExpressions;

namespace AstralRecordApi.Services;

/// <summary>未決値を推測で埋めず、開催に必要な設定を列挙します。</summary>
public static class DefenseConfigurationValidator
{
    public static IReadOnlyList<string> Missing(DefenseConfiguration value)
    {
        var missing = new List<string>();
        if (value.WorldName is null || !Regex.IsMatch(value.WorldName, "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$"))
            missing.Add("configuration.worldName");
        Position(value.CoreX, "coreX"); Position(value.CoreY, "coreY"); Position(value.CoreZ, "coreZ");
        Position(value.EntryX, "entryX"); Position(value.EntryY, "entryY"); Position(value.EntryZ, "entryZ");
        if (value.SpawnPoints is null || value.SpawnPoints.Count == 0 || value.SpawnPoints.Any(p =>
            p.X is null || p.Y is null || p.Z is null
            || !double.IsFinite(p.X.Value) || !double.IsFinite(p.Y.Value) || !double.IsFinite(p.Z.Value)))
            missing.Add("configuration.spawnPoints");
        PositiveDouble(value.CoreMaxHealth, "coreMaxHealth"); NonNegativeDouble(value.CoreDefense, "coreDefense");
        Positive(value.BaseSpawnCount, "baseSpawnCount"); Positive(value.SpawnIntervalSeconds, "spawnIntervalSeconds");
        Positive(value.MaxSpawnsPerWave, "maxSpawnsPerWave"); Positive(value.MaxSpawnsPerTick, "maxSpawnsPerTick");
        Positive(value.MaxAliveEnemies, "maxAliveEnemies"); Positive(value.RetaliationSeconds, "retaliationSeconds");
        PositiveDouble(value.RetaliationRange, "retaliationRange");
        PositiveDouble(value.PlayerSpawnCoefficient, "playerSpawnCoefficient");
        PositiveDouble(value.PlayerHealthCoefficient, "playerHealthCoefficient");
        PositiveDouble(value.PlayerDamageCoefficient, "playerDamageCoefficient");
        PositiveDouble(value.PostMaxLevelHealthCoefficient, "postMaxLevelHealthCoefficient");
        PositiveDouble(value.PostMaxLevelDamageCoefficient, "postMaxLevelDamageCoefficient");
        Positive(value.RegistrationLeadSeconds, "registrationLeadSeconds");
        Positive(value.ReconnectGraceSeconds, "reconnectGraceSeconds");
        NonNegative(value.ReviveProtectionSeconds, "reviveProtectionSeconds");
        if (value.ReviveShieldMode is not ("EMPTY" or "FULL")) missing.Add("configuration.reviveShieldMode");
        Positive(value.RewardEveryLevels, "rewardEveryLevels");
        NonNegative(value.MinimumActiveSeconds, "minimumActiveSeconds");
        if (value.RewardTiers is null || value.RewardTiers.Count == 0 ||
            value.RewardTiers.Any(t => t.RequiredDefenseLevel is null or < 1 || t.OrbQuantity is null or < 1
                || string.IsNullOrWhiteSpace(t.OrbItemId)) ||
            value.RewardTiers.Select(t => t.RequiredDefenseLevel).Distinct().Count() != value.RewardTiers.Count ||
            value.RewardTiers.Min(t => t.RequiredDefenseLevel) > 1)
            missing.Add("configuration.rewardTiers");
        if (value.MaxSpawnsPerTick > value.MaxSpawnsPerWave || value.MaxSpawnsPerWave > value.MaxAliveEnemies)
            missing.Add("configuration.spawnLimits");
        return missing;

        void Position(double? number, string name)
        {
            if (number is null || !double.IsFinite(number.Value)) missing.Add($"configuration.{name}");
        }
        void Positive(int? number, string name)
        {
            if (number is null or <= 0) missing.Add($"configuration.{name}");
        }
        void PositiveDouble(double? number, string name)
        {
            if (number is null or <= 0 || !double.IsFinite(number.Value)) missing.Add($"configuration.{name}");
        }
        void NonNegative(int? number, string name)
        {
            if (number is null or < 0) missing.Add($"configuration.{name}");
        }
        void NonNegativeDouble(double? number, string name)
        {
            if (number is null or < 0 || !double.IsFinite(number.Value)) missing.Add($"configuration.{name}");
        }
    }
}
