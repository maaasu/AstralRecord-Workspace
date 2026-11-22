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
        // The runtime searches actual projectile collisions and may spend a cast on a miss.
        Assert.Equal("timeout", outOfRange["outcome"]?.ToString());
        Assert.Equal(0, outOfRange["damageDealt"]!.GetValue<double>());

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

    [Theory]
    [InlineData(5, 100, 0, 0, 10, 10)]
    [InlineData(5, 50, 50, 0, 10, 7.5)]
    [InlineData(5, 50, 0, 50, 7.5, 10)]
    [InlineData(0, 100, 100, 100, 0, 0)]
    [InlineData(5, -200, 0, 0, 0, 0)]
    public void CriticalRateCombinesOnlyApplicablePercentages(double basis, double common, double normal, double skill,
        double expectedNormal, double expectedSkill)
    {
        var stats = new Dictionary<string, double>
        {
            ["CRITICAL_RATE"] = basis, ["CRITICAL_RATE_RELATIVE_INCREASE"] = common,
            ["NORMAL_ATTACK_CRITICAL_RATE_RELATIVE_INCREASE"] = normal, ["SKILL_CRITICAL_RATE_RELATIVE_INCREASE"] = skill
        };
        Assert.Equal(expectedNormal, BalanceCombatData.Stat(stats, "CRITICAL_RATE", source: "NORMAL_ATTACK"));
        Assert.Equal(expectedSkill, BalanceCombatData.Stat(stats, "CRITICAL_RATE", source: "SKILL"));
        Assert.Equal(basis * Math.Max(0, 1 + common / 100), BalanceCombatData.Stat(stats, "CRITICAL_RATE", source: "OTHER"));
    }

    [Theory]
    [InlineData("NORMAL_ATTACK", true, false)]
    [InlineData("SKILL", false, true)]
    public void DamageRollUsesTheAttackSource(string source, bool normal, bool skill)
    {
        var stats = new Dictionary<string, double> { ["ACCURACY"] = 100, ["ATTACK"] = 10, ["CRITICAL_RATE"] = 50,
            ["NORMAL_ATTACK_CRITICAL_RATE_RELATIVE_INCREASE"] = normal ? 100 : -100,
            ["SKILL_CRITICAL_RATE_RELATIVE_INCREASE"] = skill ? 100 : -100 };
        bool Critical(string attackSource) => BalanceSkillDamage.Calculate(stats, new Dictionary<string, double>(), "MELEE",
            [new("NONE", 1)], attackSource, 1, true, new Random(17)).Critical;
        Assert.True(Critical(source));
        Assert.False(Critical(normal ? "SKILL" : "NORMAL_ATTACK"));
    }

    [Fact]
    public void EffectiveRateIncludesClassGearTreeAndDerivedValues()
    {
        var catalog = Catalog();
        var masters = catalog["masters"]!.AsArray();
        masters[0]!["content"]!["baseStats"] = JsonNode.Parse("""[{"status":"CRITICAL_RATE","value":2}]""");
        masters[1]!["content"]!["equipment"]!["stats"] = JsonNode.Parse("""
            [{"status":"CRITICAL_RATE","type":"FLAT","value":3},{"status":"CRITICAL_RATE_RELATIVE_INCREASE","type":"FLAT","value":50}]
            """);
        masters.Add(JsonNode.Parse("""
            {"kind":"node","content":{"nodeId":"12","effects":[{"type":"status","status":"CRITICAL_RATE","modifierType":"SCALAR","value":0.5},
              {"type":"status","status":"CRITICAL_RATE_RELATIVE_INCREASE","modifierType":"FLAT","value":50}]}}
            """));
        var build = Build();
        build["nodes"] = new JsonArray(12);
        var result = BalanceCombat.Simulate(catalog, Request(1), build, 1, CancellationToken.None);
        Assert.Equal("timeout", result["outcome"]!.ToString());
        // (5 default + 2 class + 3 gear) × 1.5 tree + 0.5 from luck, then +100%.
        Assert.Equal(31, result["playerStats"]!["CRITICAL_RATE"]!.GetValue<double>());
        Assert.DoesNotContain(result["diagnostics"]!.AsArray(), row => row?["code"]?.ToString() == "unsupported_mechanic");
    }

    [Fact]
    public void SigilAddsRawAndRelativeModifiersOnlyToItsOwnSkill()
    {
        var catalog = Catalog();
        var first = Skill("shot", "adventurer_quick_shot", "ENERGY", 4, 100, new JsonObject());
        first["allowedSigilIds"] = new JsonArray("critical");
        first["sigilSlotsByLevel"] = JsonNode.Parse("""[{"level":1,"slots":1}]""");
        AddSkill(catalog, first);
        AddSkill(catalog, Skill("burst", "adventurer_mana_burst", "MANA", 0, 100,
            new JsonObject { ["angle"] = 45, ["range"] = 8, ["damageRatio"] = 1, ["maxTargets"] = 1 }));
        catalog["masters"]!.AsArray().Add(JsonNode.Parse("""
            {"kind":"item","content":{"id":"critical","category":"sigil","sigil":{"equipGroupId":"critical",
             "modifiers":[{"status":"CRITICAL_RATE","value":50},{"status":"SKILL_CRITICAL_RATE_RELATIVE_INCREASE","value":100}]}}}
            """));
        var build = Build("shot", "burst");
        build["baseStats"]!["CRITICAL_RATE"] = 0;
        build["baseStats"]!["LUCK"] = 0;
        build["baseStats"]!["INTELLIGENCE"] = 0;
        var plain = BalanceCombat.Simulate(catalog, Request(1), build, 3, CancellationToken.None);
        build["skillSigils"] = new JsonObject { ["shot"] = new JsonArray("critical") };
        var sigil = BalanceCombat.Simulate(catalog, Request(1), build, 3, CancellationToken.None);
        static double Damage(JsonObject result, string id) => result["contributions"]!.AsArray()
            .Single(row => row?["id"]?.ToString() == id)!["damage"]!.GetValue<double>();
        Assert.True(Damage(plain, "shot") > 0);
        Assert.Equal(Damage(plain, "shot") * 1.5, Damage(sigil, "shot"), 8);
        Assert.Equal(Damage(plain, "burst"), Damage(sigil, "burst"));
        Assert.Equal(0, sigil["playerStats"]!["CRITICAL_RATE"]!.GetValue<double>());
        Assert.Equal(92, sigil["resources"]!.AsArray().Last()!["eng"]!.GetValue<double>());
    }

    [Fact]
    public void SigilRatesUseTheFinishedCostAndCooldownTotals()
    {
        var catalog = Catalog();
        var shot = Skill("shot", "adventurer_quick_shot", "ENERGY", 10, 20, new JsonObject());
        shot["allowedSigilIds"] = new JsonArray("tempo");
        shot["sigilSlotsByLevel"] = JsonNode.Parse("""[{"level":1,"slots":1}]""");
        AddSkill(catalog, shot);
        catalog["masters"]!.AsArray().Add(JsonNode.Parse("""
            {"kind":"item","content":{"id":"tempo","category":"sigil","sigil":{"equipGroupId":"tempo","modifiers":[
              {"status":"COOLDOWN_REDUCTION","value":10},{"status":"COOLDOWN_REDUCTION_RELATIVE_INCREASE","value":100},
              {"status":"ENERGY_COST_REDUCTION_RELATIVE_INCREASE","value":100}]}}}
            """));
        var build = Build("shot");
        build["baseStats"]!["COOLDOWN_REDUCTION"] = 15;
        build["baseStats"]!["ENERGY_COST_REDUCTION"] = 25;
        build["skillSigils"] = new JsonObject { ["shot"] = new JsonArray("tempo") };
        var result = BalanceCombat.Simulate(catalog, Request(.8), build, 1, CancellationToken.None);
        Assert.Equal(2, Assert.Single(result["contributions"]!.AsArray())!["uses"]!.GetValue<int>());
        // Two casts, each costing 10 × (1 + 1 sigil) × (1 - 50/100).
        Assert.Equal(80, result["resources"]!.AsArray().Last()!["eng"]!.GetValue<double>());
    }

    [Fact]
    public void UnsupportedAndUngeneratedRelativeIdsStillFailClosed()
    {
        Assert.True(BalanceCombat.SupportsStatus("FIRE_RESISTANCE_RELATIVE_INCREASE"));
        Assert.False(BalanceCombat.SupportsStatus("SKILL_DAMAGE_INCREASE_RELATIVE_INCREASE"));
        Assert.False(BalanceCombat.SupportsStatus("SKILL_ACCURACY_RELATIVE_INCREASE"));
        Assert.False(BalanceCombat.SupportsStatus("LIFE_STEAL_RELATIVE_INCREASE"));
        var catalog = Catalog();
        catalog["sources"] = new JsonArray(BalanceCombatParity.SourceHashes.Select(pair => (JsonNode?)new JsonObject
            { ["path"] = pair.Key, ["hash"] = pair.Value }).ToArray());
        var request = Request(1);
        request["mode"] = "strict";
        var build = Build();
        build["baseStats"]!["SKILL_ACCURACY_RELATIVE_INCREASE"] = 100;
        var result = BalanceCombat.Simulate(catalog, request, build, 2, CancellationToken.None);
        Assert.Equal("unsupported", result["outcome"]!.ToString());
        Assert.Contains(result["diagnostics"]!.AsArray(), row => row?["message"]?.ToString().Contains("SKILL_ACCURACY_RELATIVE_INCREASE") == true);
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
