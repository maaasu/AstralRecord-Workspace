using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed partial class BalanceRuntimeIntegrationTests
{
    private const string AllyId = "00000000-0000-0000-0000-000000000101";

    [Fact]
    public void DynamicSwordNormalAttackLandsAtTickZero()
    {
        var catalog = Catalog();
        var result = Run(catalog, Request(.05, 2), Build(normal: true));
        var hit = Assert.Single(Timeline(result, "normal_hit"));
        Assert.Equal(0, hit["tick"]!.GetValue<int>());
        Assert.True(hit["damage"]!.GetValue<double>() > 0);
    }

    [Fact]
    public void NormalAttackInputsRespectTheSharedCooldown()
    {
        var request = Request(.6, 2);
        request["inputs"] = new JsonArray(Enumerable.Range(0, 13)
            .Select(tick => (JsonNode?)new JsonObject { ["tick"] = tick, ["kind"] = "normal_attack" }).ToArray());
        var result = Run(Catalog(), request, Build(normal: false));
        Assert.Equal(new[] { 0, 10 }, Timeline(result, "normal_hit").Select(value => value["tick"]!.GetValue<int>()));
        Assert.Equal(2, result["contributions"]!.AsArray().Single(value => value!["id"]!.ToString() == "normal_attack")!["uses"]!.GetValue<int>());
    }

    [Fact]
    public void GuardCostIsNotReducedByEnergyCostReduction()
    {
        var catalog = Catalog(enemyAttack: 20, enemyInterval: 1);
        AddSkill(catalog, Skill("paladin_guard_convert", "{}", passive: true));
        var impact = Skill("paladin_shield_impact", """{"radius":6,"damageRatio":0.55,"defenseReductionRatio":30,"defenseDebuffDurationTicks":100}""", resource: "GUARD", cost: 10);
        AddSkill(catalog, impact);
        var build = Build("paladin_shield_impact");
        build["passiveSkillIds"] = new JsonArray("paladin_guard_convert");
        build["baseStats"]!["DEFENSE"] = 1_000_000;
        build["baseStats"]!["ENERGY_COST_REDUCTION"] = 100;
        var request = Request(.1, 2);
        request["inputs"] = new JsonArray(new JsonObject { ["tick"] = 2, ["enemyPosition"] = new JsonArray(0, 0, 50) });
        var result = Run(catalog, request, build);
        Assert.Equal(1, result["contributions"]!.AsArray().Single(value => value!["id"]!.ToString() == "paladin_shield_impact")!["uses"]!.GetValue<int>());
        Assert.Equal(0, LastResource(result)["custom"]!["GUARD"]!.GetValue<double>(), 10);
        Assert.Equal(100, LastResource(result)["eng"]!.GetValue<double>(), 10);
    }

    [Fact]
    public void TurningAfterLaunchDoesNotRotateOrCancelTheExistingProjectile()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("adventurer_quick_shot", "{}", resource: "ENERGY", cost: 10));
        var baselineRequest = Request(.4, 8);
        var turningRequest = baselineRequest.DeepClone().AsObject();
        turningRequest["inputs"] = new JsonArray(new JsonObject { ["tick"] = 1, ["facing"] = new JsonArray(1, 0, 0) });
        var baseline = Run(catalog, baselineRequest, Build("adventurer_quick_shot"));
        var turning = Run(catalog, turningRequest, Build("adventurer_quick_shot"));
        var expected = Assert.Single(Timeline(baseline, "damage"));
        var actual = Assert.Single(Timeline(turning, "damage"));
        Assert.True(expected["damage"]!.GetValue<double>() > 0);
        Assert.Equal(expected["tick"]!.GetValue<int>(), actual["tick"]!.GetValue<int>());
        Assert.Equal(expected["damage"]!.GetValue<double>(), actual["damage"]!.GetValue<double>(), 10);
    }

    [Fact]
    public void RuntimeBurningUsesRangedCastSnapshotAndPluginGoldenDotPower()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("sharpshooter_fire_arrow", """{"range":16,"damageRatio":1.15,"projectileSpeed":1.6,"projectileHitRadius":0.6,"conditionChance":100,"inheritedConditionChance":25,"conditionDurationTicks":100}"""));
        AddSkill(catalog, Skill("hunter_build_up", """{"buffId":"buff:hunter_build_up","buffDurationTicks":200,"energyRecoveryRatio":0.01,"rangedAttackIncreaseRatio":0.11}"""));
        catalog["masters"]!.AsArray().Add(new JsonObject
        {
            ["kind"] = "buff", ["path"] = "test/build-up.yml",
            ["content"] = JsonNode.Parse("""{"schemaVersion":1,"id":"hunter_build_up","type":"BUFF","durationTicks":200,"isDebuff":false,"resetOnChallenge":true,"stackGroup":"hunter_build_up","modifiers":[{"status":"RANGED_ATTACK","type":"SCALAR","value":0.11}]}""")
        });
        var build = Build("sharpshooter_fire_arrow", "hunter_build_up");
        build["baseStats"]!["MAGIC_ATTACK"] = 400;
        var result = Run(catalog, Request(1.2, 4.4), build);
        var dot = Assert.Single(Timeline(result, "condition_tick"));
        var oracle = BalanceSkillExecutorGoldenTests.Fixture("skill-mechanics-golden.json", "burning_ticks")["expected"]!;
        Assert.Equal(oracle["damagePerTick"]!.GetValue<double>(), dot["damage"]!.GetValue<double>(), 10);
        Assert.True(Timeline(result, "stat").Any(value => value["skillId"]!.ToString() == "hunter_build_up"), result.ToJsonString());
    }

    [Fact]
    public void EnemyTargetsTheSpecifiedAllyAndProtectRedirectsNinetyPercent()
    {
        var catalog = Catalog(enemyAttack: 20, enemyInterval: 1);
        AddSkill(catalog, Skill("paladin_guardian_protect", """{"targetRange":30,"aimRadius":2,"durationTicks":200,"visualIntervalTicks":10,"damageRedirectRatio":0.9}""", cost: 25));
        var request = Request(.05, 3);
        request["allies"] = new JsonArray(Ally());
        request["enemy"]!["targetAllyId"] = AllyId;
        var baseline = Run(catalog, request, Build());
        var protectedResult = Run(catalog, request, Build("paladin_guardian_protect"));
        Assert.Equal(100, LastResource(baseline)["hp"]!.GetValue<double>(), 10);
        Assert.Equal(80, LastResource(baseline)["allies"]![0]!["hp"]!.GetValue<double>(), 10);
        Assert.Equal(82, LastResource(protectedResult)["hp"]!.GetValue<double>(), 10);
        Assert.Equal(98, LastResource(protectedResult)["allies"]![0]!["hp"]!.GetValue<double>(), 10);
        Assert.Equal(18, protectedResult["damageTaken"]!.GetValue<double>(), 10);
    }

    [Fact]
    public void SeededResourcesAndCustomStateRepeatWithoutMutatingInputsOrMasters()
    {
        var catalog = Catalog(enemyAttack: 20, enemyInterval: 1);
        AddSkill(catalog, Skill("paladin_guard_convert", "{}", passive: true));
        AddSkill(catalog, Skill("adventurer_mana_burst", """{"range":7,"angle":60,"damageRatio":1.32,"maxTargets":6}""", cost: 7));
        var quick = Skill("adventurer_quick_shot", "{}", resource: "ENERGY", cost: 10);
        quick["manaCost"] = 3;
        AddSkill(catalog, quick);
        var build = Build("adventurer_mana_burst", "adventurer_quick_shot");
        build["passiveSkillIds"] = new JsonArray("paladin_guard_convert");
        build["baseStats"]!["DEFENSE"] = 1_000_000;
        var request = Request(.2, 2);
        request["inputs"] = new JsonArray(new JsonObject { ["tick"] = 2, ["enemyPosition"] = new JsonArray(0, 0, 50) });
        var before = (Catalog: catalog.ToJsonString(), Request: request.ToJsonString(), Build: build.ToJsonString());
        var first = Run(catalog, request, build);
        var second = Run(catalog, request, build);
        Assert.Equal(first["resources"]!.ToJsonString(), second["resources"]!.ToJsonString());
        Assert.Equal(first["skillTimeline"]!.ToJsonString(), second["skillTimeline"]!.ToJsonString());
        Assert.Equal(90, LastResource(first)["mp"]!.GetValue<double>(), 10);
        Assert.Equal(90, LastResource(first)["eng"]!.GetValue<double>(), 10);
        Assert.Equal(10, LastResource(first)["custom"]!["GUARD"]!.GetValue<double>(), 10);
        Assert.Equal(before.Catalog, catalog.ToJsonString());
        Assert.Equal(before.Request, request.ToJsonString());
        Assert.Equal(before.Build, build.ToJsonString());
    }

    [Fact]
    public void VelocityReplayStopsAtTheFirstMissingFrame()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("hunter_fade_shot", """{"range":9,"damageRatio":0.384,"pelletCount":5,"spreadAngle":30,"projectileSpeed":1.8,"projectileHitRadius":0.3,"backstepVelocity":0.35}"""));
        var request = Request(.15, 5);
        request["world"]!["velocityMode"] = "replay";
        request["inputs"] = new JsonArray(new JsonObject { ["tick"] = 1, ["playerPosition"] = new JsonArray(0, 0, -.35) });
        var result = BalanceCombat.Simulate(catalog, request, Build("hunter_fade_shot"), 17, CancellationToken.None);
        Assert.Equal("unsupported", result["outcome"]!.ToString());
        Assert.Equal(2, LastResource(result)["tick"]!.GetValue<int>());
        Assert.Contains(result["diagnostics"]!.AsArray(), value => value?["message"]?.ToString().Contains("tick 2") == true);
    }

    private static JsonObject Run(JsonObject catalog, JsonObject request, JsonObject build)
    {
        var result = BalanceCombat.Simulate(catalog, request, build, 17, CancellationToken.None);
        Assert.Equal("timeout", result["outcome"]?.ToString());
        // Synthetic masters intentionally omit source fingerprints. An unrelated unsupported
        // effect must still fail these tests instead of silently disappearing in approximate mode.
        Assert.DoesNotContain(result["diagnostics"]!.AsArray(), value => value?["code"]?.ToString() == "unsupported_mechanic"
            && value["message"]?.ToString().Contains("Pluginソース", StringComparison.Ordinal) != true);
        return result;
    }

    private static JsonObject LastResource(JsonObject result) => result["resources"]!.AsArray()[^1]!.AsObject();
    private static JsonObject[] Timeline(JsonObject result, string kind) => result["skillTimeline"]!.AsArray().OfType<JsonObject>()
        .Where(value => value["kind"]!.ToString() == kind).ToArray();

    private static JsonObject Catalog(double enemyAttack = 0, int enemyInterval = 1000)
    {
        var catalog = JsonNode.Parse("""
            {"masters":[
              {"kind":"class","path":"test/class.yml","content":{"id":"adventurer","maxLevel":30,"baseStats":[]}},
              {"kind":"item","path":"test/weapon.yml","content":{"id":"weapon","name":"剣","equipment":{"slot":"WEAPON","tag":"SWORD","stats":[]}}},
              {"kind":"mob","path":"test/mob.yml","content":{"id":"enemy","level":1,"baseStats":[{"status":"MAX_HEALTH","value":10000},{"status":"ATTACK","value":0},{"status":"STRENGTH","value":0},{"status":"DEFENSE","value":0},{"status":"ACCURACY","value":100},{"status":"ATTACK_SPEED","value":100}],"ai":{"combat":{"style":"MELEE","normalAttack":{"intervalTicks":1000,"range":3}}}}}
            ],"sources":[],"modelVersion":"test","masterHash":"test"}
            """)!.AsObject();
        catalog["masters"]![2]!["content"]!["baseStats"]![1]!["value"] = enemyAttack;
        catalog["masters"]![2]!["content"]!["ai"]!["combat"]!["normalAttack"]!["intervalTicks"] = enemyInterval;
        return catalog;
    }

    private static JsonObject Request(double seconds, double distance) => new()
    {
        ["mode"] = "approximate", ["maxSeconds"] = seconds,
        ["world"] = new JsonObject { ["floorY"] = 0 },
        ["enemy"] = new JsonObject { ["mobId"] = "enemy", ["level"] = 1, ["distanceBlocks"] = distance, ["targetHalfDepth"] = .3 }
    };

    private static JsonObject Build(params string[] skills) => Build(skills, false);
    private static JsonObject Build(bool normal) => Build([], normal);
    private static JsonObject Build(string[] skills, bool normal) => new()
    {
        ["classId"] = "adventurer", ["playerLevel"] = 1, ["classLevel"] = 1,
        ["equipment"] = new JsonArray(new JsonObject { ["itemId"] = "weapon" }),
        ["skillIds"] = new JsonArray(skills.Select(id => (JsonNode?)JsonValue.Create(id)).ToArray()), ["normalAttacks"] = normal,
        ["baseStats"] = JsonNode.Parse("""{"MAX_HEALTH":100,"MAX_MANA":100,"MAX_ENERGY":100,"ATTACK":0,"MELEE_ATTACK":40,"RANGED_ATTACK":40,"MAGIC_ATTACK":0,"STRENGTH":0,"DEXTERITY":0,"INTELLIGENCE":0,"VITALITY":0,"AGILITY":0,"LUCK":0,"ACCURACY":100,"EVASION":0,"DEFENSE":0,"CRITICAL_RATE":0,"SUPER_CRITICAL_RATE":0,"ATTACK_SPEED":100,"MP_REGEN":0,"ENERGY_REGEN":0,"HP_REGEN":0}""")
    };

    private static JsonObject Skill(string id, string parameters, string resource = "MANA", double cost = 0, bool passive = false) => new()
    {
        ["id"] = id, ["implementationId"] = id, ["name"] = id, ["type"] = "SKILL",
        ["resourceType"] = resource, ["resourceCost"] = cost, ["castTimeTicks"] = 0, ["cooldownTicks"] = 200,
        ["params"] = JsonNode.Parse(parameters), ["maxLevel"] = 1,
        ["passive"] = passive ? new JsonObject { ["bindRequired"] = true } : null
    };

    private static void AddSkill(JsonObject catalog, JsonObject skill)
    {
        if (skill["passive"] is null) skill.Remove("passive");
        catalog["masters"]!.AsArray().Add(new JsonObject { ["kind"] = "skill", ["path"] = "test/" + skill["id"] + ".yml", ["content"] = skill });
    }

    private static JsonObject Ally() => new()
    {
        ["id"] = AllyId, ["position"] = new JsonArray(0, 0, 2), ["hp"] = 100,
        ["stats"] = new JsonObject { ["MAX_HEALTH"] = 100, ["DEFENSE"] = 0, ["EVASION"] = 0 }
    };
}
