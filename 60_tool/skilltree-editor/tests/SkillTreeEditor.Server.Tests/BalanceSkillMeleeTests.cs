using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceSkillMeleeTests
{
    private static readonly string[] SupportedMasterIds =
    [
        "adventurer_astral_edge", "swordsman_flame_rush", "swordsman_shield_drain",
        "swordmaster_blade_tempest", "swordmaster_crescent_duet", "swordmaster_heartseeker",
        "swordmaster_star_cleave", "swordmaster_overdrive", "swordmaster_trinity_edge",
        "swordmaster_flame_lotus", "swordmaster_frost_bloom", "swordmaster_thunder_line",
        "paladin_blessed_lance", "paladin_holy_reprieve", "paladin_holy_smash", "paladin_holy_smite"
    ];

    [Fact]
    public void AllSixteenRealLevelOneMastersMeetTheirExecutorValidation()
    {
        foreach (var id in SupportedMasterIds)
        {
            var profile = BalanceSkillMeleeProfiles.Resolve(LoadMaster(id));
            Assert.NotNull(profile);
            Assert.True(profile.Supported, $"{id}: {string.Join(" / ", profile.Reasons)}");
        }
    }

    [Theory]
    [InlineData("adventurer_astral_edge", "energyRecoveryRatio", 1.1)]
    [InlineData("swordsman_flame_rush", "burningChance", 120)]
    [InlineData("swordsman_shield_drain", "shieldAbsorbRatio", 1.1)]
    [InlineData("swordmaster_blade_tempest", "damageRatio", -1)]
    [InlineData("swordmaster_crescent_duet", "intervalTicks", 0)]
    [InlineData("swordmaster_heartseeker", "executeThreshold", 1.1)]
    [InlineData("swordmaster_star_cleave", "burstRadius", 7)]
    [InlineData("swordmaster_overdrive", "durationTicks", 0)]
    [InlineData("swordmaster_trinity_edge", "conditionBonusRatio", -1)]
    [InlineData("swordmaster_flame_lotus", "conditionChance", 120)]
    [InlineData("swordmaster_frost_bloom", "conditionTicks", 0)]
    [InlineData("swordmaster_thunder_line", "maxTargets", 1.5)]
    [InlineData("paladin_blessed_lance", "fieldMultiplier", 6)]
    [InlineData("paladin_holy_reprieve", "healAmount", 201)]
    [InlineData("paladin_holy_smash", "defenseReductionRatio", 101)]
    [InlineData("paladin_holy_smite", "weaknessChance", 101)]
    public void ExecutorInvalidParameterCannotRemainSupported(string id, string key, double value)
    {
        var master = LoadMaster(id);
        master["params"]![key] = value;
        var profile = BalanceSkillMeleeProfiles.Resolve(master)!;
        Assert.False(profile.Supported, $"{id}.{key}={value} should be rejected");
        Assert.Contains(profile.Reasons, reason => reason.Contains(key, StringComparison.Ordinal));
    }

    [Fact]
    public void InclusiveChanceAndDelayBoundariesFollowExecutors()
    {
        var flame = LoadMaster("swordsman_flame_rush");
        flame["params"]!["burningChance"] = 0;
        flame["params"]!["secondHitDelayTicks"] = 0;
        Assert.True(BalanceSkillMeleeProfiles.Resolve(flame)!.Supported);
        flame["params"]!["burningChance"] = 100;
        Assert.True(BalanceSkillMeleeProfiles.Resolve(flame)!.Supported);

        var smite = LoadMaster("paladin_holy_smite");
        smite["params"]!["weaknessChance"] = 0;
        Assert.True(BalanceSkillMeleeProfiles.Resolve(smite)!.Supported);
        smite["params"]!["weaknessChance"] = 100;
        Assert.True(BalanceSkillMeleeProfiles.Resolve(smite)!.Supported);

        var smash = LoadMaster("paladin_holy_smash");
        smash["params"]!["defenseReductionRatio"] = 0;
        Assert.True(BalanceSkillMeleeProfiles.Resolve(smash)!.Supported);
    }

    [Fact]
    public void ScalarAndArrayParameterShapesAreNotInterchangeable()
    {
        var flame = LoadMaster("swordsman_flame_rush");
        flame["params"]!["damageRatios"] = JsonNode.Parse("""[0.78,-0.9]""");
        Assert.False(BalanceSkillMeleeProfiles.Resolve(flame)!.Supported);
        flame["params"]!["damageRatios"] = 0.78;
        Assert.Throws<ArgumentException>(() => BalanceSkillMeleeProfiles.Resolve(flame));
        flame["params"]!["damageRatios"] = JsonNode.Parse("""[0.78,0.9]""");
        flame["params"]!["range"] = JsonNode.Parse("""[6]""");
        Assert.Throws<ArgumentException>(() => BalanceSkillMeleeProfiles.Resolve(flame));
    }

    [Fact]
    public void FlameRushKeepsTheDelayedSecondHitAndConditionalBurning()
    {
        var skill = JsonNode.Parse("""
            {"id":"renamed_flame_rush","implementationId":"swordsman_flame_rush","params":{
              "range":6,"targetAngle":60,"maxTargets":5,"damageRatios":[0.78,0.90],
              "secondHitDelayTicks":4,"burningChance":25,"burningDurationTicks":100}}
            """)!.AsObject();
        var profile = BalanceSkillMeleeProfiles.Resolve(skill)!;
        Assert.True(profile.Supported);
        Assert.Equal("swordsman_flame_rush", profile.ImplementationId);
        Assert.Collection(profile.Effects,
            first => { Assert.Equal("damage", first.Kind); Assert.Equal(0, first.Tick); Assert.Equal(.78, first.Value); },
            second => { Assert.Equal("damage", second.Kind); Assert.Equal(4, second.Tick); Assert.Equal(.90, second.Value); },
            condition => { Assert.Equal("condition", condition.Kind); Assert.Equal(4, condition.Tick);
                Assert.Equal("BURNING", condition.Status); Assert.Equal("landed", condition.Trigger);
                Assert.Equal("MELEE", condition.AttackType); Assert.Equal(6, condition.Range); });
    }

    [Fact]
    public void UnsupportedKnownExecutorHasReasonAndUnknownExecutorIsNotClaimed()
    {
        var unsupported = BalanceSkillMeleeProfiles.Resolve(new JsonObject { ["implementationId"] = "paladin_guardian_bulwark" });
        Assert.NotNull(unsupported);
        Assert.False(unsupported.Supported);
        Assert.Contains("GUARD", unsupported.Reasons[0]);
        Assert.Null(BalanceSkillMeleeProfiles.Resolve(new JsonObject { ["implementationId"] = "future_skill" }));
    }

    [Fact]
    public void BladeTempestUsesFourScheduledHitsAndRejectsInvalidCount()
    {
        var skill = JsonNode.Parse("""
            {"implementationId":"swordmaster_blade_tempest","params":{
              "radius":3,"maxTargets":5,"damageRatio":1.5,"hitCount":4,"intervalTicks":4}}
            """)!.AsObject();
        var profile = BalanceSkillMeleeProfiles.Resolve(skill)!;
        Assert.True(profile.Supported);
        Assert.Equal(new[] { 1, 5, 9, 13 }, profile.Effects.Select(effect => effect.Tick));
        skill["params"]!["hitCount"] = 7;
        Assert.False(BalanceSkillMeleeProfiles.Resolve(skill)!.Supported);
    }

    [Fact]
    public void CrescentDuetCriticalBranchMatchesRealExecutorTrace()
    {
        // SkillExecutorGoldenHarness invokes the Plugin executor and emits 2.0@tick1, 3.2@tick5.
        var skill = JsonNode.Parse("""
            {"implementationId":"swordmaster_crescent_duet","params":{
              "range":4.5,"angle":65,"maxTargets":5,"damageRatio":2,
              "criticalMultiplier":1.6,"intervalTicks":4}}
            """)!.AsObject();
        var profile = BalanceSkillMeleeProfiles.Resolve(skill)!;
        Assert.True(profile.Supported);
        Assert.Equal(1, profile.Effects[0].Tick);
        Assert.Equal(2, profile.Effects[0].Value);
        Assert.Equal(5, profile.Effects[1].Tick);
        var view = new BalanceSkillView(new Dictionary<string, double>(), new Dictionary<string, double>(),
            100, 100, 100, 100, 0, 0, true, true, false, new HashSet<string>(), 0);
        Assert.Equal(3.2, profile.Effects[1].Evaluate!(view), 10);
    }

    private static JsonObject LoadMaster(string id)
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (directory is not null && !Directory.Exists(Path.Combine(directory.FullName, "40_filebase")))
            directory = directory.Parent;
        Assert.NotNull(directory);
        var path = Path.Combine(directory.FullName, "40_filebase", "30.features.skill", $"v1.{id}.yml");
        var parsed = new MasterDataCodec().Parse(path, File.ReadAllText(path));
        Assert.Empty(parsed.Issues);
        return Assert.IsType<JsonObject>(parsed.Content);
    }
}
