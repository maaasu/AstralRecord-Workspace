using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceCombatTests
{
    [Fact]
    public void DamageAndTimingMatchPluginGoldenHarness()
    {
        // These expectations are emitted by CombatGoldenHarness.java through real Plugin classes.
        var attack = BalanceCombatMath.AttackPower(40, 0, 0);
        var defense = BalanceCombatMath.EffectiveDefense(8, 0, 0, 0);
        var finalDamage = attack * BalanceCombatMath.DefenseMultiplier(attack, defense);
        Assert.Equal(35.72096105187394, finalDamage, 10);
        Assert.Equal(53.581441577810914, finalDamage * 1.5, 10);
        Assert.Equal(95, BalanceCombatMath.HitChance(95, 0));
        Assert.Equal(9, BalanceCombatMath.CooldownTicks(17, 50));
        Assert.Equal(6, BalanceCombatMath.AttackIntervalTicks(9, 175));
        Assert.Equal(5, BalanceCombatMath.CastTimeTicks(9, 20, 40));
        Assert.Equal(9, BalanceCombatMath.CastTimeTicks(17, 80, -10));
    }

    [Fact]
    public void ChangedPluginSourceStopsStrictMode()
    {
        var result = BalanceCombat.Simulate(Catalog(), Request("strict"), Build(), 7, CancellationToken.None);
        Assert.Equal("unsupported", result["outcome"]?.ToString());
        Assert.Contains(result["diagnostics"]!.AsArray(), item => item?["code"]?.ToString() == "plugin_source_changed");
    }

    [Fact]
    public void ApproximateModeRunsSeededOneOnOneFightAndRecordsResources()
    {
        var build = Build();
        build["baseStats"] = new JsonObject { ["MOVEMENT_SPEED_CAP"] = 150 };
        var first = BalanceCombat.Simulate(Catalog(), Request("approximate"), build, 7, CancellationToken.None);
        var second = BalanceCombat.Simulate(Catalog(), Request("approximate"), build, 7, CancellationToken.None);
        Assert.DoesNotContain(first["diagnostics"]!.AsArray(), item => item?["message"]?.ToString().Contains("MOVEMENT_SPEED_CAP") == true);
        Assert.Equal(first.ToJsonString(), second.ToJsonString());
        Assert.True(new[] { "victory", "death", "timeout" }.Contains(first["outcome"]?.ToString()), first.ToJsonString());
        Assert.NotEmpty(first["resources"]!.AsArray());
        Assert.NotEmpty(first["contributions"]!.AsArray());
        Assert.True(double.Parse(first["damageDealt"]!.ToString()) > 0);
    }

    [Fact]
    public void DuplicateSelectedMasterIsInvalidInBothModes()
    {
        var catalog = Catalog();
        catalog["masters"]!.AsArray().Add(catalog["masters"]![0]!.DeepClone());
        foreach (var mode in new[] { "strict", "approximate" })
        {
            var result = BalanceCombat.Simulate(catalog, Request(mode), Build(), 7, CancellationToken.None);
            Assert.Equal("invalid", result["outcome"]?.ToString());
            Assert.Contains(result["diagnostics"]!.AsArray(), item => item?["code"]?.ToString() == "duplicate_master");
        }
    }

    [Fact]
    public void ManaBurstConsumesManaAndRecordsSkillDamage()
    {
        var catalog = Catalog();
        catalog["masters"]!.AsArray().Add(JsonNode.Parse("""
            {"kind":"skill","path":"test/mana_burst.yml","content":{"id":"adventurer_mana_burst","implementationId":"adventurer_mana_burst","name":"マナバースト","cooldownTicks":60,"resourceType":"MANA","resourceCost":13,"castTimeTicks":2,"params":{"damageRatio":1.32}}}
            """));
        var build = Build();
        build["skillIds"] = new JsonArray("adventurer_mana_burst");
        var result = BalanceCombat.Simulate(catalog, Request("approximate"), build, 7, CancellationToken.None);
        Assert.Contains(result["contributions"]!.AsArray(), item => item?["id"]?.ToString() == "adventurer_mana_burst"
            && double.Parse(item["damage"]!.ToString()) > 0);
        Assert.Contains(result["resources"]!.AsArray(), item => double.Parse(item!["mp"]!.ToString()) < 15);
    }

    [Fact]
    public void SmashAppliesDamageOnlyAfterCastAndImpactDelay()
    {
        var catalog = Catalog();
        catalog["masters"]!.AsArray().Add(JsonNode.Parse("""
            {"kind":"skill","path":"test/smash.yml","content":{"id":"adventurer_smash","implementationId":"adventurer_smash","name":"スマッシュ","cooldownTicks":150,"resourceType":"ENERGY","resourceCost":30,"castTimeTicks":12,"params":{"damageRatio":3.6,"impactDelayTicks":8}}}
            """));
        var build = Build();
        build["skillIds"] = new JsonArray("adventurer_smash");
        var result = BalanceCombat.Simulate(catalog, Request("approximate"), build, 7, CancellationToken.None);
        Assert.Equal("victory", result["outcome"]?.ToString());
        Assert.True(int.Parse(result["elapsedTicks"]!.ToString()) >= 20);
        Assert.Contains(result["contributions"]!.AsArray(), item => item?["id"]?.ToString() == "adventurer_smash"
            && int.Parse(item["uses"]!.ToString()) == 1 && double.Parse(item["damage"]!.ToString()) > 0);
    }

    [Fact]
    public void ShieldEnabledAndLevelProfileChangeActualEncounter()
    {
        var catalog = Catalog();
        var request = Request("approximate");
        var original = BalanceCombat.Simulate(catalog, request, Build(), 9, CancellationToken.None);
        catalog["masters"]![2]!["content"]!["shield"] = JsonNode.Parse("""{"enabled":true,"max":18}""");
        var shielded = BalanceCombat.Simulate(catalog, request, Build(), 9, CancellationToken.None);
        Assert.True(shielded["elapsedTicks"]!.GetValue<int>() > original["elapsedTicks"]!.GetValue<int>());
        Assert.InRange(shielded["resources"]![0]!["enemyShield"]!.GetValue<double>(), .001, 17.999);
        catalog["masters"]![2]!["content"]!["levels"] = JsonNode.Parse("""[{"level":2,"shield":{"enabled":true,"max":20,"rechargeTimeSeconds":15},"baseStats":[{"status":"MAX_HEALTH","value":200}],"ai":{"combat":{"normalAttack":{"intervalTicks":100}}}}]""");
        request["enemy"]!["level"] = 2;
        var profile = BalanceCombat.Simulate(catalog, request, Build(), 9, CancellationToken.None);
        Assert.Equal(200, profile["enemyStats"]!["MAX_HEALTH"]!.GetValue<double>());
        Assert.Contains(profile["diagnostics"]!.AsArray(), value => value?["message"]?.ToString().Contains("再充填") == true);
    }

    [Fact]
    public void UnimplementedEffectsAndImpossibleSlotsAreDiagnosed()
    {
        var build = Build();
        build["baseStats"] = JsonNode.Parse("""{"LIFE_STEAL":10,"FIXED_HEALTH_DAMAGE":10}""");
        var result = BalanceCombat.Simulate(Catalog(), Request("approximate"), build, 7, CancellationToken.None);
        Assert.Contains(result["diagnostics"]!.AsArray(), value => value?["message"]?.ToString().Contains("LIFE_STEAL") == true);
        var catalog = Catalog();
        catalog["masters"]!.AsArray().Add(JsonNode.Parse("""{"kind":"item","content":{"id":"hat","equipment":{"slot":"HEAD","stats":[]}}}"""));
        build = Build();
        build["equipment"]!.AsArray().Add(new JsonObject { ["itemId"] = "hat" });
        build["equipment"]!.AsArray().Add(new JsonObject { ["itemId"] = "hat" });
        var duplicate = BalanceCombat.Simulate(catalog, Request("approximate"), build, 7, CancellationToken.None);
        Assert.Equal("invalid", duplicate["outcome"]!.ToString());
        build = Build(); build["skillIds"] = new JsonArray("x", "x");
        var skills = BalanceCombat.Simulate(catalog, Request("approximate"), build, 7, CancellationToken.None);
        Assert.Equal("invalid", skills["outcome"]!.ToString());
    }

    [Fact]
    public void RecordedHealthDamageCannotExceedAvailableHealthAtDeath()
    {
        var catalog = Catalog();
        catalog["masters"]![2]!["content"]!["baseStats"] = JsonNode.Parse("""[{"status":"MAX_HEALTH","value":100000},{"status":"ATTACK","value":100000},{"status":"ACCURACY","value":100}]""");
        var result = BalanceCombat.Simulate(catalog, Request("approximate"), Build(), 1, CancellationToken.None);
        Assert.Equal("death", result["outcome"]!.ToString());
        Assert.True(result["damageTaken"]!.GetValue<double>() < 100);
        Assert.Equal(0, result["resources"]!.AsArray()[^1]!["hp"]!.GetValue<double>());
    }

    private static JsonObject Catalog() => JsonNode.Parse("""
        {"masters":[
          {"kind":"class","path":"test/class.yml","content":{"id":"adventurer","maxLevel":30,"baseStats":[{"status":"ATTACK","value":20}]}},
          {"kind":"item","path":"test/weapon.yml","content":{"id":"sword","name":"剣","equipment":{"slot":"WEAPON","tag":"SWORD","stats":[{"status":"ATTACK","type":"FLAT","value":10}]}}},
          {"kind":"mob","path":"test/mob.yml","content":{"id":"boar","level":1,"baseStats":[{"status":"MAX_HEALTH","value":100},{"status":"ATTACK","value":5},{"status":"DEFENSE","value":8},{"status":"ACCURACY","value":95}],"ai":{"combat":{"style":"MELEE","normalAttack":{"intervalTicks":30}}}}}
        ],"sources":[],"modelVersion":"test","masterHash":"test"}
        """)!.AsObject();

    private static JsonObject Request(string mode) => new()
    {
        ["mode"] = mode, ["maxSeconds"] = 30,
        ["enemy"] = new JsonObject { ["mobId"] = "boar", ["level"] = 1 }
    };

    private static JsonObject Build() => JsonNode.Parse("""
        {"classId":"adventurer","playerLevel":1,"classLevel":1,"equipment":[{"itemId":"sword"}],"skillIds":[]}
        """)!.AsObject();
}
