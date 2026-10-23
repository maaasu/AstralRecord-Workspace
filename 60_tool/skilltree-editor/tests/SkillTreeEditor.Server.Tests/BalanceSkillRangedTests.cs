using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceSkillRangedTests
{
    [Theory]
    [InlineData("sharpshooter_fire_arrow", "FIRE", "BURNING", 24.5, 100)]
    [InlineData("sharpshooter_ice_arrow", "ICE", "FROZEN", 50, 40)]
    public void ElementalArrowsKeepDamageAndConditionOnSameProjectile(
        string id, string element, string condition, double chance, int duration)
    {
        var profile = BalanceSkillRangedProfiles.Resolve(Definition(id, new JsonObject
        {
            ["damageRatio"] = 1.45, ["projectileSpeed"] = 1.6,
            ["projectileHitRadius"] = .6, ["range"] = 16
        }));
        Assert.NotNull(profile);
        Assert.True(profile.Supported);
        Assert.Equal(2, profile.Effects.Count);
        Assert.Equal("RANGED", profile.Effects[0].AttackType);
        Assert.Equal(element, profile.Effects[0].Element);
        Assert.Equal(1.45, profile.Effects[0].Value);
        Assert.Equal(1.6, profile.Effects[0].FlightSpeed);
        Assert.Equal(16, profile.Effects[0].Range);
        Assert.Equal("condition", profile.Effects[1].Kind);
        Assert.Equal("landed", profile.Effects[1].Trigger);
        Assert.Equal(condition, profile.Effects[1].Status);
        Assert.Equal(chance, profile.Effects[1].Chance);
        Assert.Equal(duration, profile.Effects[1].DurationTicks);
        Assert.Equal(profile.Effects[0].FlightSpeed, profile.Effects[1].FlightSpeed);
    }

    [Fact]
    public void MasterAliasUsesImplementationIdAndAllKnownKeysAreListed()
    {
        var definition = Definition("sharpshooter_piercing_line", new JsonObject
        {
            ["inheritanceDurationTicks"] = 2980, ["inheritanceConsumptionTicks"] = 600,
            ["damageRatio"] = 1.7, ["maxHits"] = 3, ["pierceDecay"] = .8,
            ["range"] = 23, ["projectileSpeed"] = 2, ["projectileHitRadius"] = .55
        });
        definition["id"] = "renamed_piercer";
        var profile = BalanceSkillRangedProfiles.Resolve(definition);
        Assert.NotNull(profile);
        Assert.True(profile.Supported);
        Assert.Equal("sharpshooter_piercing_line", profile.ImplementationId);
        Assert.Equal(1.7, Assert.Single(profile.Effects).Value);
        Assert.All(((JsonObject)definition["params"]!).Select(pair => pair.Key),
            key => Assert.Contains(key, profile.KnownParameters));
    }

    [Fact]
    public void RicochetHasOnlyFirstHitInOneEnemyScenario()
    {
        var profile = BalanceSkillRangedProfiles.Resolve(Definition("sharpshooter_ricochet_arrow", new JsonObject
        {
            ["damageRatio"] = 1.2, ["bounceRadius"] = 5, ["bounceCount"] = 2,
            ["bounceDecay"] = .7, ["range"] = 19, ["projectileSpeed"] = 1.8,
            ["projectileHitRadius"] = .6
        }));
        Assert.NotNull(profile);
        Assert.True(profile.Supported);
        Assert.Single(profile.Effects);
        Assert.Equal(1.2, profile.Effects[0].Value);
    }

    [Fact]
    public void ThermalBreakUsesTwoComponentsAndCurrentConditions()
    {
        var profile = BalanceSkillRangedProfiles.Resolve(Definition("sharpshooter_thermal_break", new JsonObject
        {
            ["damageRatio"] = 1.2, ["statusBonusRatio"] = .9
        }));
        Assert.NotNull(profile);
        Assert.True(profile.Supported);
        var effect = Assert.Single(profile.Effects);
        Assert.Equal(new[] { "FIRE", "ICE" }, effect.Components!.Select(component => component.Element));
        Assert.All(effect.Components!, component => Assert.Equal(.5, component.Ratio));
        Assert.Equal(1.2, effect.Evaluate!(View(new HashSet<string>())));
        Assert.Equal(2.1, effect.Evaluate(View(new HashSet<string> { "FROZEN" })), 10);
        Assert.Equal(2.1, effect.Evaluate(View(new HashSet<string> { "BURNING" })), 10);
    }

    [Fact]
    public void PhantomShotSchedulesThreeSeparateProjectilesAndStopsRecoveryVariant()
    {
        var p = new JsonObject { ["damageRatio"] = 1.7, ["shotIntervalTicks"] = 5,
            ["resourceRecoveryRatio"] = 0 };
        var lv1 = BalanceSkillRangedProfiles.Resolve(Definition("sharpshooter_phantom_shot", p));
        Assert.NotNull(lv1);
        Assert.True(lv1.Supported);
        Assert.Equal(new[] { 0, 5, 10 }, lv1.Effects.Select(effect => effect.Tick));
        p["resourceRecoveryRatio"] = .1;
        var recovery = BalanceSkillRangedProfiles.Resolve(Definition("sharpshooter_phantom_shot", p));
        Assert.NotNull(recovery);
        Assert.False(recovery.Supported);
        Assert.Contains("連続", Assert.Single(recovery.Reasons));
    }

    [Fact]
    public void SpreadingAmbitionSupportsOnePelletWithOriginOffsetAndConditionBonus()
    {
        var p = new JsonObject { ["damageRatio"] = 2, ["pelletCount"] = 1,
            ["projectileSpeed"] = 1.6, ["projectileHitRadius"] = .6, ["range"] = 24 };
        var single = BalanceSkillRangedProfiles.Resolve(Definition("sharpshooter_spreading_ambition", p));
        Assert.NotNull(single);
        Assert.True(single.Supported);
        var hit = Assert.Single(single.Effects);
        Assert.Equal(.8, hit.FlightOriginOffset);
        Assert.Equal(2, hit.Evaluate!(View(new HashSet<string>())));
        Assert.Equal(4, hit.Evaluate(View(new HashSet<string> { "BURNING" })));
        p["pelletCount"] = 3;
        var spread = BalanceSkillRangedProfiles.Resolve(Definition("sharpshooter_spreading_ambition", p));
        Assert.NotNull(spread);
        Assert.False(spread.Supported);
        Assert.Contains("散弾", Assert.Single(spread.Reasons));
    }

    [Fact]
    public void BuildUpHasScalarBuffAndTenRecoveryTicks()
    {
        var profile = BalanceSkillRangedProfiles.Resolve(Definition("hunter_build_up", new JsonObject
        {
            ["buffId"] = "buff:hunter_build_up", ["buffDurationTicks"] = 200,
            ["energyRecoveryRatio"] = .01, ["rangedAttackIncreaseRatio"] = .11
        }));
        Assert.NotNull(profile);
        Assert.True(profile.Supported);
        Assert.Equal(11, profile.Effects.Count);
        Assert.Equal("SCALAR", profile.Effects[0].Mode);
        Assert.Equal("RANGED_ATTACK", profile.Effects[0].Status);
        Assert.Equal(.11, profile.Effects[0].Value);
        Assert.Equal(200, profile.Effects[0].DurationTicks);
        Assert.Equal(Enumerable.Range(1, 10).Select(index => index * 20),
            profile.Effects.Skip(1).Select(effect => effect.Tick));
        Assert.All(profile.Effects.Skip(1), effect =>
        {
            Assert.Equal("ENG", effect.Status);
            Assert.Equal("maxEnergy", effect.Basis);
            Assert.Equal(.01, effect.Value);
            Assert.Equal("hunter_build_up", effect.Scope);
        });
    }

    [Fact]
    public void EveryKnownRangedSkillHasDescriptorAndUnknownImplementationDoesNot()
    {
        string[] known = ["hunter_fade_shot", "hunter_arrow_rain", "hunter_crash_arrow", "hunter_heal_arrow",
            "hunter_build_up", "hunter_spell_step", "archer_air_shift", "archer_heal_arrow_alpha",
            "archer_starweave_followup", "sharpshooter_fire_arrow", "sharpshooter_ice_arrow",
            "sharpshooter_spreading_ambition", "sharpshooter_phantom_shot", "sharpshooter_piercing_line",
            "sharpshooter_ricochet_arrow", "sharpshooter_thermal_break", "sharpshooter_glacial_trap",
            "sharpshooter_inheritance_mastery", "phantom_archer_shadow_stitch", "phantom_archer_soul_pierce",
            "phantom_archer_dread_bloom", "phantom_archer_spectral_sentry", "phantom_archer_echo_volley",
            "phantom_archer_mist_retreat", "phantom_archer_eclipse_shot", "phantom_archer_curse_weaving",
            "phantom_archer_spectral_attunement", "phantom_archer_wraith_step"];
        Assert.All(known, id =>
        {
            var profile = BalanceSkillRangedProfiles.Resolve(Definition(id, new()));
            Assert.NotNull(profile);
            Assert.Equal(id, profile.ImplementationId);
            Assert.NotEmpty(profile.Sources);
            if (!profile.Supported) Assert.NotEmpty(profile.Reasons);
        });
        Assert.Null(BalanceSkillRangedProfiles.Resolve(Definition("unrecognized", new())));
    }

    private static JsonObject Definition(string id, JsonObject parameters) => new()
    {
        ["id"] = id, ["implementationId"] = id, ["params"] = parameters.DeepClone()
    };

    private static BalanceSkillView View(IReadOnlySet<string> conditions)
        => new(new Dictionary<string, double>(), new Dictionary<string, double>(),
            100, 100, 100, 100, 0, 0, false, false, false, conditions, 0);
}
