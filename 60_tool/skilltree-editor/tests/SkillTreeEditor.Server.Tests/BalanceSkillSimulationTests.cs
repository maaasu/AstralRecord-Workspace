using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceSkillSimulationTests
{
    [Fact]
    public void BatchKeepsDetailedEffectsOnlyForFirstTrialAndDoesNotMutateMaster()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("shot", "adventurer_quick_shot", "ENERGY", 1, 100, new JsonObject()));
        var before = catalog.ToJsonString();
        var build = Build("shot"); build["id"] = "a";
        var request = Request(1); request["schemaVersion"] = 1; request["seed"] = 123;
        request["trials"] = 3; request["builds"] = new JsonArray(build); request["overrides"] = new JsonArray();
        var result = BalanceLabRunner.Run(catalog, request, (_, _) => { }, CancellationToken.None);
        var trials = result["results"]![0]!["trials"]!.AsArray();
        Assert.NotEmpty(trials[0]!["skillTimeline"]!.AsArray());
        Assert.All(trials.Skip(1), trial => Assert.Empty(trial!["skillTimeline"]!.AsArray()));
        Assert.All(trials, trial => Assert.NotEmpty(trial!["contributions"]!.AsArray()));
        Assert.Equal(before, catalog.ToJsonString());
    }

    // Expected damage and collision ticks come from SkillMechanicsGoldenHarness.java,
    // which invokes the actual Plugin DamageCalculator and SkillTargetingService.
    [Fact]
    public void MultiElementDamageAndProjectileTickMatchPluginGoldenCases()
    {
        var attacker = new Dictionary<string, double> { ["RANGED_ATTACK"] = 40, ["ACCURACY"] = 100,
            ["FINAL_DAMAGE_MULTIPLIER"] = 100 };
        var victim = new Dictionary<string, double> { ["DEFENSE"] = 8 };
        var hit = BalanceSkillDamage.Calculate(attacker, victim, "RANGED",
            [new("FIRE", .6), new("ICE", .6)], "SKILL", 1, true, new Random(7));
        Assert.True(hit.Landed);
        Assert.False(hit.Critical);
        Assert.Equal(BalanceSkillExecutorGoldenTests.Fixture("skill-mechanics-golden.json", "thermal_components")["expected"]!["compositeDamage"]!.GetValue<double>(), hit.Damage, 10);

        var standard = new BalanceSkillEffect("damage", FlightSpeed: 1.6, HitRadius: .6, Range: 16);
        var collision = BalanceSkillExecutorGoldenTests.Fixture("skill-mechanics-golden.json", "projectile_collision")["expected"]!;
        Assert.Equal(collision["standardTick"]!.GetValue<int>(), BalanceSkillDamage.FlightTicks(4.4, .3, standard));
        Assert.Equal(collision["offsetTick"]!.GetValue<int>(), BalanceSkillDamage.FlightTicks(4.4, .3, standard with { FlightOriginOffset = .8 }));
        Assert.False(BalanceSkillDamage.OutOfRange(3.1, .3, standard with { Range = 3, HitRadius = .1 }));
        Assert.True(BalanceSkillDamage.OutOfRange(3.1, .3,
            new BalanceSkillEffect("damage", Range: 3, HitRadius: 0, FlightSpeed: 0)));
    }

    [Fact]
    public void SuperCriticalCountsAsCriticalButZeroDamageCannotLand()
    {
        var attacker = new Dictionary<string, double> { ["RANGED_ATTACK"] = 40,
            ["ACCURACY"] = 100, ["SUPER_CRITICAL_RATE"] = 100,
            ["SUPER_CRITICAL_DAMAGE"] = 30, ["FINAL_DAMAGE_MULTIPLIER"] = 100 };
        var victim = new Dictionary<string, double>();
        var super = BalanceSkillDamage.Calculate(attacker, victim, "RANGED", [new("NONE", 1)],
            "SKILL", 1, true, new Random(4));
        Assert.True(super.Landed);
        Assert.True(super.Critical);
        Assert.Equal(52, super.Damage, 10);
        attacker["RANGED_ATTACK"] = 0;
        var zero = BalanceSkillDamage.Calculate(attacker, victim, "RANGED", [new("NONE", 1)],
            "SKILL", 1, true, new Random(4));
        Assert.True(zero.Critical); // raw roll; the simulation gates critical on effective damage.
        Assert.Equal(0, zero.Damage);
    }

    [Fact]
    public void SkillCatalogDiscoversAliasAndClassLinkWithoutMasterFileReads()
    {
        var catalog = Catalog();
        var skill = Skill("renamed_fire_arrow", "sharpshooter_fire_arrow", "MANA", 30, 100,
            new JsonObject { ["damageRatio"] = 1.15, ["range"] = 16, ["projectileSpeed"] = 1.6,
                ["projectileHitRadius"] = .6, ["conditionChance"] = 24.5, ["inheritedConditionChance"] = 25,
                ["conditionDurationTicks"] = 100 });
        AddSkill(catalog, skill);
        catalog["masters"]!.AsArray()[0]!["content"]!["usableSkills"] = new JsonArray("renamed_fire_arrow");
        var entry = Assert.Single(BalanceSkills.Catalog(catalog));
        Assert.Equal("renamed_fire_arrow", entry!["id"]?.ToString());
        Assert.Equal("sharpshooter_fire_arrow", entry["implementationId"]?.ToString());
        Assert.True(entry["supported"]!.GetValue<bool>());
        Assert.Equal("adventurer", Assert.Single(entry["classLinks"]!.AsArray())!["classId"]?.ToString());
    }

    [Fact]
    public void UnknownParameterAndMalformedNumbersStopAdapter()
    {
        var baseSkill = Skill("fire", "sharpshooter_fire_arrow", "MANA", 30, 100,
            new JsonObject { ["damageRatio"] = 1.15, ["range"] = 16,
                ["projectileSpeed"] = 1.6, ["projectileHitRadius"] = .6,
                ["conditionChance"] = 24.5, ["inheritedConditionChance"] = 25,
                ["conditionDurationTicks"] = 100 });
        Assert.True(BalanceSkills.Resolve(baseSkill).Supported);

        var unknown = (JsonObject)baseSkill.DeepClone();
        unknown["params"]!["extraBurningPower"] = 4;
        Assert.Contains(BalanceSkills.Resolve(unknown).Reasons, reason => reason.Contains("params.extraBurningPower"));

        var malformed = (JsonObject)baseSkill.DeepClone();
        malformed["params"]!["damageRatio"] = "fast";
        Assert.False(BalanceSkills.Resolve(malformed).Supported);

        var invalid = (JsonObject)baseSkill.DeepClone();
        invalid["params"]!["conditionChance"] = 120;
        Assert.False(BalanceSkills.Resolve(invalid).Supported);
    }

    [Fact]
    public void SeededPhantomShotIsThreeHitsForOneEnergyAndManaCost()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("phantom", "sharpshooter_phantom_shot", "ENERGY", 20, 60,
            new JsonObject { ["range"] = 16, ["damageRatio"] = 1.7, ["projectileSpeed"] = 1.8,
                ["projectileHitRadius"] = 1.1, ["shotIntervalTicks"] = 5,
                ["successiveHitWindowTicks"] = 40, ["resourceRecoveryRatio"] = 0 }, manaCost: 16));
        var build = Build("phantom");
        var first = BalanceCombat.Simulate(catalog, Request(.9), build, 7, CancellationToken.None);
        var second = BalanceCombat.Simulate(catalog, Request(.9), build, 7, CancellationToken.None);
        Assert.Equal(first["skillTimeline"]!.ToJsonString(), second["skillTimeline"]!.ToJsonString());
        Assert.Equal(first["resources"]!.ToJsonString(), second["resources"]!.ToJsonString());
        Assert.Equal("timeout", first["outcome"]?.ToString());
        var hits = first["skillTimeline"]!.AsArray().Where(item => item?["skillId"]?.ToString() == "phantom"
            && item?["kind"]?.ToString() == "damage").ToArray();
        Assert.Equal(new[] { 1, 6, 11 }, hits.Select(item => item!["tick"]!.GetValue<int>()));
        Assert.Equal(1, Assert.Single(first["contributions"]!.AsArray())!["uses"]!.GetValue<int>());
        // Base MP 100 receives +5 from INT before the single 16-MP cast.
        Assert.Equal(89, first["resources"]!.AsArray()[^1]!["mp"]!.GetValue<double>());
        Assert.Equal(80, first["resources"]!.AsArray()[^1]!["eng"]!.GetValue<double>());
    }

    [Fact]
    public void BuildUpBuffsFollowingShotAndRecoversEnergyAtTwentyTicks()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("build_up", "hunter_build_up", "MANA", 6, 300,
            new JsonObject { ["buffId"] = "buff:hunter_build_up", ["buffDurationTicks"] = 200,
                ["energyRecoveryRatio"] = .01, ["rangedAttackIncreaseRatio"] = .11 }));
        AddSkill(catalog, Skill("phantom", "sharpshooter_phantom_shot", "ENERGY", 20, 60,
            new JsonObject { ["range"] = 16, ["damageRatio"] = 1.7, ["projectileSpeed"] = 1.8,
                ["projectileHitRadius"] = 1.1, ["shotIntervalTicks"] = 5,
                ["successiveHitWindowTicks"] = 40, ["resourceRecoveryRatio"] = 0 }, manaCost: 16));
        var withoutBuff = BalanceCombat.Simulate(catalog, Request(1.1), Build("phantom"), 3, CancellationToken.None);
        var withBuff = BalanceCombat.Simulate(catalog, Request(1.1), Build("build_up", "phantom"), 3, CancellationToken.None);
        Assert.Equal("timeout", withBuff["outcome"]?.ToString());
        Assert.True(withBuff["damageDealt"]!.GetValue<double>() > withoutBuff["damageDealt"]!.GetValue<double>());
        var recovery = withBuff["skillTimeline"]!.AsArray().Where(item => item?["skillId"]?.ToString() == "build_up"
            && item?["kind"]?.ToString() == "resource").ToArray();
        Assert.Single(recovery);
        Assert.Equal(20, recovery[0]!["tick"]!.GetValue<int>());
        Assert.Equal(81, withBuff["resources"]!.AsArray()[^1]!["eng"]!.GetValue<double>());
        Assert.Equal(6, withoutBuff["resources"]!.AsArray()[^1]!["mp"]!.GetValue<double>()
            - withBuff["resources"]!.AsArray()[^1]!["mp"]!.GetValue<double>(), 10);
    }

    [Fact]
    public void ExistingCircleAddsOneOrbitalProjectileAtCast()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("circle", "archmage_meteor_circle", "MANA", 0, 200,
            new JsonObject { ["range"] = 16, ["radius"] = 3, ["power"] = .1,
                ["durationTicks"] = 40, ["pulseTicks"] = 20 }));
        AddSkill(catalog, Skill("orbital", "archmage_orbital_volley", "MANA", 0, 200,
            new JsonObject { ["range"] = 18, ["radius"] = 3, ["power"] = .75 }));
        var alone = BalanceCombat.Simulate(catalog, Request(.9), Build("orbital"), 5, CancellationToken.None);
        var together = BalanceCombat.Simulate(catalog, Request(.9), Build("circle", "orbital"), 5, CancellationToken.None);
        static int Bolts(JsonObject result) => result["skillTimeline"]!.AsArray().Count(item =>
            item?["skillId"]?.ToString() == "orbital" && item?["kind"]?.ToString() == "damage");
        Assert.Equal(2, Bolts(alone));
        Assert.Equal(3, Bolts(together));
        Assert.Equal(1, together["resources"]!.AsArray()[^1]!["circleCount"]!.GetValue<int>());
    }

    [Fact]
    public void ZeroDamageHitDoesNotFreezeButEffectiveHitStopsEnemyAttack()
    {
        // SkillCombatService applies a condition only after positive HP or shield damage.
        var catalog = Catalog();
        catalog["masters"]!.AsArray()[2]!["content"]!["baseStats"]![1]!["value"] = 10;
        catalog["masters"]!.AsArray()[2]!["content"]!["ai"]!["combat"]!["normalAttack"]!["intervalTicks"] = 30;
        catalog["masters"]!.AsArray()[2]!["content"]!["ai"]!["combat"]!["normalAttack"]!["range"] = 3;
        AddSkill(catalog, Skill("frost", "mage_frost_ball", "MANA", 0, 100,
            new JsonObject { ["range"] = 16, ["radius"] = 2, ["damageRatio"] = .45,
                ["maxTargets"] = 1, ["projectileSpeed"] = 1.45, ["projectileHitRadius"] = .45,
                ["freezeChance"] = 100, ["freezeDurationTicks"] = 40 }));
        var weak = Build("frost");
        weak["baseStats"]!["ATTACK"] = 0;
        weak["baseStats"]!["MAGIC_ATTACK"] = 0;
        weak["baseStats"]!["INTELLIGENCE"] = 0;
        weak["baseStats"]!["AGILITY"] = 0;
        weak["baseStats"]!["EVASION"] = 0;
        var strong = (JsonObject)weak.DeepClone();
        strong["baseStats"]!["MAGIC_ATTACK"] = 40;
        var request = Request(2);
        request["enemy"]!["distanceBlocks"] = 3;
        var noFreeze = BalanceCombat.Simulate(catalog, request, weak, 17, CancellationToken.None);
        var frozen = BalanceCombat.Simulate(catalog, request, strong, 17, CancellationToken.None);
        Assert.Equal("timeout", noFreeze["outcome"]?.ToString());
        Assert.True(noFreeze["damageTaken"]!.GetValue<double>() > 0);
        Assert.Equal(0, frozen["damageTaken"]!.GetValue<double>());
    }

    [Fact]
    public void EnemyNormalAttackStopsJustOutsideRangeSquaredPlusFour()
    {
        var catalog = Catalog();
        catalog["masters"]!.AsArray()[2]!["content"]!["baseStats"]![1]!["value"] = 10;
        catalog["masters"]!.AsArray()[2]!["content"]!["ai"]!["combat"]!["normalAttack"]!["range"] = 2;
        catalog["masters"]!.AsArray()[2]!["content"]!["ai"]!["combat"]!["normalAttack"]!["intervalTicks"] = 30;
        var inside = Request(2);
        inside["enemy"]!["distanceBlocks"] = 2.82; // 2.82² < 2² + 4
        var outside = Request(2);
        outside["enemy"]!["distanceBlocks"] = 2.83; // 2.83² > 2² + 4
        var build = Build();

        var inRange = BalanceCombat.Simulate(catalog, inside, build, 17, CancellationToken.None);
        var outOfRange = BalanceCombat.Simulate(catalog, outside, build, 17, CancellationToken.None);

        Assert.True(inRange["damageTaken"]!.GetValue<double>() > 0);
        Assert.Equal(0, outOfRange["damageTaken"]!.GetValue<double>());
        Assert.Contains(outOfRange["assumptions"]!.AsArray(), item =>
            item?.ToString().Contains("射程外で攻撃しない") == true);
    }

    [Fact]
    public void BurningUsesRangedAttackPowerAndTicksTwentyTicksAfterImpact()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("fire", "sharpshooter_fire_arrow", "MANA", 0, 100,
            new JsonObject { ["range"] = 16, ["damageRatio"] = 1.15, ["projectileSpeed"] = 1.6,
                ["projectileHitRadius"] = .6, ["conditionChance"] = 100,
                ["inheritedConditionChance"] = 25, ["conditionDurationTicks"] = 100 }));
        var build = Build("fire");
        build["baseStats"]!["ATTACK"] = 0;
        build["baseStats"]!["DEXTERITY"] = 0;
        build["baseStats"]!["RANGED_ATTACK"] = 40;
        var result = BalanceCombat.Simulate(catalog, Request(1.2), build, 9, CancellationToken.None);
        var dot = Assert.Single(result["skillTimeline"]!.AsArray(), item =>
            item?["kind"]?.ToString() == "condition_tick");
        Assert.Equal(22, dot!["tick"]!.GetValue<int>());
        Assert.Equal(8, dot["damage"]!.GetValue<double>(), 10);
    }

    [Fact]
    public void InvalidGeometryAndLevelTwoSigilInputsAreDiagnosed()
    {
        var catalog = Catalog();
        AddSkill(catalog, Skill("fire", "sharpshooter_fire_arrow", "MANA", 0, 100,
            new JsonObject { ["range"] = 16, ["damageRatio"] = 1.15, ["projectileSpeed"] = 1.6,
                ["projectileHitRadius"] = .6, ["conditionChance"] = 100,
                ["inheritedConditionChance"] = 25, ["conditionDurationTicks"] = 100 }));
        var range = Request(1);
        range["enemy"]!["distanceBlocks"] = 100;
        var outOfRange = BalanceCombat.Simulate(catalog, range, Build("fire"), 1, CancellationToken.None);
        // A synthetic catalog has no Plugin fingerprints; approximate mode still emits the range diagnostic.
        Assert.True(outOfRange["diagnostics"]!.AsArray().Any(item =>
            item?["message"]?.ToString().Contains("射程外") == true), outOfRange.ToJsonString());

        var leveled = Build("fire");
        leveled["skillLevels"] = new JsonObject { ["fire"] = 2 };
        leveled["skillSigils"] = new JsonObject { ["fire"] = new JsonArray("70a00001") };
        var levelResult = BalanceCombat.Simulate(catalog, Request(1), leveled, 1, CancellationToken.None);
        Assert.Contains(levelResult["diagnostics"]!.AsArray(), item =>
            item?["message"]?.ToString().Contains("build.skillLevels") == true);
        Assert.Contains(levelResult["diagnostics"]!.AsArray(), item =>
            item?["message"]?.ToString().Contains("build.skillSigils") == true);

        var badGeometry = Request(1);
        badGeometry["enemy"]!["distanceBlocks"] = -1;
        var invalid = BalanceCombat.Simulate(catalog, badGeometry, Build("fire"), 1, CancellationToken.None);
        Assert.Equal("invalid", invalid["outcome"]?.ToString());
    }

    private static JsonObject Catalog() => JsonNode.Parse("""
        {"masters":[
          {"kind":"class","path":"test/class.yml","content":{"id":"adventurer","maxLevel":30,"baseStats":[]}},
          {"kind":"item","path":"test/weapon.yml","content":{"id":"sword","name":"剣","equipment":{"slot":"WEAPON","tag":"SWORD","stats":[]}}},
          {"kind":"mob","path":"test/mob.yml","content":{"id":"boar","level":1,"baseStats":[{"status":"MAX_HEALTH","value":1000},{"status":"ATTACK","value":0},{"status":"DEFENSE","value":8},{"status":"ACCURACY","value":100}],"ai":{"combat":{"style":"MELEE","normalAttack":{"intervalTicks":1000}}}}}
          ,{"kind":"buff","path":"70.shared.buff/v1.hunter_build_up.yml","content":{"schemaVersion":1,"id":"hunter_build_up","type":"BUFF","durationTicks":200,"isDebuff":false,"resetOnChallenge":true,"stackGroup":"hunter_build_up","modifiers":[{"status":"RANGED_ATTACK","type":"SCALAR","value":0.11}]}}
        ],"sources":[],"modelVersion":"test","masterHash":"test"}
        """)!.AsObject();

    private static void AddSkill(JsonObject catalog, JsonObject skill)
        => catalog["masters"]!.AsArray().Add(new JsonObject
        {
            ["kind"] = "skill", ["path"] = "test/" + skill["id"] + ".yml", ["content"] = skill
        });

    private static JsonObject Skill(string id, string implementation, string resource, double cost,
        int cooldown, JsonObject parameters, double manaCost = 0) => new()
    {
        ["id"] = id, ["implementationId"] = implementation, ["name"] = id, ["type"] = "SKILL",
        ["resourceType"] = resource, ["resourceCost"] = cost, ["manaCost"] = manaCost,
        ["cooldownTicks"] = cooldown, ["castTimeTicks"] = 0, ["params"] = parameters
    };

    private static JsonObject Request(double seconds) => new()
    {
        ["mode"] = "approximate", ["maxSeconds"] = seconds,
        ["enemy"] = new JsonObject { ["mobId"] = "boar", ["level"] = 1,
            ["distanceBlocks"] = 4.4, ["targetHalfDepth"] = .3 }
    };

    private static JsonObject Build(params string[] skills) => new()
    {
        ["classId"] = "adventurer", ["playerLevel"] = 1, ["classLevel"] = 1,
        ["equipment"] = new JsonArray(new JsonObject { ["itemId"] = "sword" }),
        ["skillIds"] = new JsonArray(skills.Select(id => (JsonNode?)JsonValue.Create(id)).ToArray()),
        ["normalAttacks"] = false,
        ["baseStats"] = new JsonObject { ["MAX_HEALTH"] = 100, ["MAX_MANA"] = 100,
            ["MAX_ENERGY"] = 100, ["RANGED_ATTACK"] = 40, ["ACCURACY"] = 100,
            ["MP_REGEN"] = 0, ["ENERGY_REGEN"] = 0, ["HP_REGEN"] = 0 }
    };
}
