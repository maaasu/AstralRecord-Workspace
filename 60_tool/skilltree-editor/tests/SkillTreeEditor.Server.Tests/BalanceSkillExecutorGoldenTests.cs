using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceSkillExecutorGoldenTests
{
    [Fact]
    public void BaseAdventurerSkillsRejectValuesTheirPluginValidatorRejects()
    {
        var burst = JsonNode.Parse("""{"id":"burst","implementationId":"adventurer_mana_burst","resourceType":"MANA","params":{"range":7,"angle":60,"damageRatio":1.32,"maxTargets":6}}""")!.AsObject();
        Assert.True(BalanceSkills.Resolve(burst).Supported);
        burst["params"]!["angle"] = 181;
        Assert.False(BalanceSkills.Resolve(burst).Supported);
        burst["params"]!["angle"] = 60; burst["params"]!["damageRatio"] = -1;
        Assert.False(BalanceSkills.Resolve(burst).Supported);
        burst["params"]!.AsObject().Remove("range");
        Assert.False(BalanceSkills.Resolve(burst).Supported);
    }

    [Theory]
    [InlineData("swordsman_flame_rush", """{"range":6,"targetAngle":60,"maxTargets":5,"damageRatios":[0.78,0.9],"secondHitDelayTicks":4,"burningChance":0,"burningDurationTicks":100}""")]
    [InlineData("swordmaster_blade_tempest", """{"radius":3,"maxTargets":5,"damageRatio":1.5,"hitCount":4,"intervalTicks":4}""")]
    [InlineData("swordmaster_crescent_duet", """{"range":4.5,"angle":65,"maxTargets":5,"damageRatio":2,"criticalMultiplier":1.6,"intervalTicks":4}""")]
    public void AdapterHitPlanMatchesActualExecutorTrace(string id, string parameters)
    {
        // Inputs match the Java harness. Expected hit order/timing/ratios come from real executor bytecode.
        var profile = BalanceSkills.Resolve(new JsonObject { ["id"] = id, ["implementationId"] = id,
            ["resourceType"] = "ENERGY", ["params"] = JsonNode.Parse(parameters) });
        Assert.True(profile.Supported, string.Join(" / ", profile.Reasons));
        var actual = profile.Effects.Where(effect => effect.Kind == "damage").ToArray();
        var expected = Fixture("skill-executor-golden.json", id)["expected"]!["hits"]!.AsArray();
        Assert.Equal(expected.Count, actual.Length);
        var view = new BalanceSkillView(new Dictionary<string, double>(), new Dictionary<string, double>(),
            100, 100, 50, 50, 0, 0, true, true, false, new HashSet<string>(), 0);
        for (var index = 0; index < actual.Length; index++)
        {
            Assert.Equal(expected[index]!["tick"]!.GetValue<int>(), actual[index].Tick);
            Assert.Equal(expected[index]!["ratio"]!.GetValue<double>(), actual[index].Evaluate?.Invoke(view) ?? actual[index].Value, 10);
            Assert.Equal(expected[index]!["element"]!.ToString(), actual[index].Element);
        }
    }

    internal static JsonObject Fixture(string file, string id)
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            var path = Path.Combine(directory.FullName, "60_tool", "skilltree-editor", "tests", "plugin-parity", file);
            if (File.Exists(path)) return JsonNode.Parse(File.ReadAllText(path))!["cases"]!.AsArray().OfType<JsonObject>().Single(value => value["id"]!.ToString() == id);
        }
        throw new FileNotFoundException("Pluginの固定期待値がありません。", file);
    }
}
