using System.Reflection;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalancePluginGoldenTests
{
    [Theory]
    [InlineData("melee_neutral")]
    [InlineData("melee_critical")]
    [InlineData("melee_miss")]
    public void ActualSimulatorStrikeMatchesJavaRuntimeWithFixedRandomInputs(string id)
    {
        var golden = Case(id); var input = golden["input"]!;
        var attacker = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase)
        {
            ["ATTACK"] = Number(input["attack"]), ["ACCURACY"] = Number(input["accuracy"]),
            ["CRITICAL_RATE"] = Number(input["critRate"]), ["CRITICAL_DAMAGE"] = Number(input["critDamage"], 150)
        };
        var victim = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase) { ["DEFENSE"] = Number(input["mobDefense"]) };
        var strike = typeof(BalanceCombat).GetMethod("Strike", BindingFlags.NonPublic | BindingFlags.Static)!;
        var actual = (double)strike.Invoke(null, [attacker, victim, "MELEE", "NONE", "NORMAL_ATTACK", 1d, 1d, true,
            new FixedRandom(Number(input["hitRoll"]) / 100, Number(input["critRoll"]) / 100)])!;
        Assert.Equal(Number(golden["expected"]!["finalDamage"]), actual, 10);
    }

    [Fact]
    public void CastAndExperienceRoundingUseJavaGeneratedExpectations()
    {
        var cast = Case("cast_reduction_stacked"); var input = cast["input"]!;
        Assert.Equal((long)Number(cast["expected"]!["castTicks"]), BalanceCombatMath.CastTimeTicks(
            (long)Number(input["baseCastTicks"]), Number(input["primaryReduction"]), Number(input["additionalReduction"])));
        var experience = Case("exp_level_gap_rate"); input = experience["input"]!;
        Assert.Equal((int)Number(experience["expected"]!["afterRateExp"]), BalanceGrowthRewards.ExperiencePerVictory(
            (int)Number(input["baseExp"]), (int)Number(input["playerLevel"]), (int)Number(input["mobLevel"]), Number(input["experienceGainRate"])));
        var half = Case("exp_half_round");
        Assert.Equal((int)Number(half["expected"]!["afterRateExp"]), BalanceGrowthRewards.ExperiencePerVictory(7, 1, 1, 50));
    }

    [Fact]
    public void HitAndCriticalDistributionIsCheckedSeparatelyFromFixedRollParity()
    {
        var strike = typeof(BalanceCombat).GetMethod("Strike", BindingFlags.NonPublic | BindingFlags.Static)!;
        var attacker = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase)
        { ["ATTACK"] = 40, ["ACCURACY"] = 55, ["CRITICAL_RATE"] = 25, ["CRITICAL_DAMAGE"] = 150 };
        var victim = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase) { ["DEFENSE"] = 8, ["EVASION"] = 15 };
        var random = new Random(1234);
        var criticalDamage = Number(Case("melee_critical")["expected"]!["finalDamage"]);
        var hits = 0; var criticals = 0;
        for (var index = 0; index < 5000; index++)
        {
            var damage = (double)strike.Invoke(null, [attacker, victim, "MELEE", "NONE", "NORMAL_ATTACK", 1d, 1d, true, random])!;
            if (damage > 0) hits++;
            if (Math.Abs(damage - criticalDamage) < 1e-8) criticals++;
        }
        Assert.InRange(hits, 1850, 2150); // 40% hit probability, all misses remain in the population.
        Assert.InRange(criticals, 400, 600); // 25% conditional critical probability.
    }

    private static JsonObject Case(string id)
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            var path = Path.Combine(directory.FullName, "60_tool", "skilltree-editor", "tests", "plugin-parity", "combat-golden.json");
            if (File.Exists(path)) return JsonNode.Parse(File.ReadAllText(path))!["cases"]!.AsArray().OfType<JsonObject>().Single(value => value["id"]!.ToString() == id);
        }
        throw new FileNotFoundException("Plugin実処理の固定期待値がありません。");
    }
    private static double Number(JsonNode? value, double fallback = 0) => value is null ? fallback : double.Parse(value.ToString(), System.Globalization.CultureInfo.InvariantCulture);
    private sealed class FixedRandom(params double[] values) : Random
    {
        private int _index;
        public override double NextDouble() => values[Math.Min(_index++, values.Length - 1)];
    }
}
