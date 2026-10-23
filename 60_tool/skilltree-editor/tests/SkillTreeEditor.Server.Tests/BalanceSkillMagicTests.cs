using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceSkillMagicTests
{
    [Fact]
    public void ProjectileFollowupKeepsTheSameFlightAsItsDamage()
    {
        var profile = Resolve("wizard_mana_siphon", """{"range":14,"hitRadius":0.35,"projectileSpeed":1.2,"damageRatio":1.2,"manaRecovery":20}""");
        Assert.True(profile.Supported);
        var damage = Assert.Single(profile.Effects, effect => effect.Kind == "damage");
        var recovery = Assert.Single(profile.Effects, effect => effect.Kind == "resource");
        Assert.Equal("landed", recovery.Trigger);
        Assert.Equal((damage.FlightSpeed, damage.HitRadius, damage.Range),
            (recovery.FlightSpeed, recovery.HitRadius, recovery.Range));
    }

    [Fact]
    public void TriadUsesThreeComponentsInOneHitAndReadsCurrentConditions()
    {
        var profile = Resolve("wizard_triad_convergence", """{"range":16,"radius":3,"maxTargets":5,"damageRatio":1.2,"conditionBonusRatio":0.25}""");
        Assert.True(profile.Supported);
        var effect = Assert.Single(profile.Effects);
        Assert.Equal(new[] { "FIRE", "ICE", "LIGHTNING" }, effect.Components!.Select(component => component.Element));
        Assert.NotNull(effect.Evaluate);
        var empty = View();
        var conditioned = View("BURNING", "CHILLED", "SHOCKED");
        Assert.True(effect.Evaluate!(conditioned) > effect.Evaluate(empty));
    }

    [Fact]
    public void FieldPulseStopsBeforeExpiryAndKeepsItsReplacementScope()
    {
        var profile = Resolve("archmage_meteor_circle", """{"range":16,"radius":3,"durationTicks":120,"pulseTicks":20,"power":0.55}""");
        Assert.True(profile.Supported);
        var circle = Assert.Single(profile.Effects, effect => effect.Kind == "circle");
        Assert.Equal("archmage_meteor_circle", circle.Status);
        var pulses = profile.Effects.Where(effect => effect.Kind == "damage").ToArray();
        Assert.Equal(new[] { 1, 21, 41, 61, 81, 101 }, pulses.Select(effect => effect.Tick));
        Assert.All(pulses, effect => Assert.Equal(circle.Status, effect.Scope));
    }

    [Fact]
    public void UnsupportedRuntimeEffectIsExplainedButUnknownImplementationIsUnclaimed()
    {
        var prism = Resolve("wizard_elemental_prism", "{}");
        Assert.False(prism.Supported);
        Assert.NotEmpty(prism.Reasons);
        Assert.Contains("WizardElementalPrismExecutor.java", Assert.Single(prism.Sources));
        Assert.Null(BalanceSkillMagicProfiles.Resolve(new JsonObject { ["implementationId"] = "unrelated_skill" }));
    }

    [Fact]
    public void InstantBeamUsesOverriddenRangeAndRejectsNonPositiveGeometry()
    {
        var changed = Resolve("wizard_arcane_lance", """{"range":2,"hitRadius":0.3,"maxTargets":3,"damageRatio":3.2}""");
        Assert.Equal(2, Assert.Single(changed.Effects).Range);
        Assert.Throws<ArgumentException>(() => Resolve("wizard_arcane_lance",
            """{"range":0,"hitRadius":0.3,"maxTargets":3,"damageRatio":3.2}"""));
        Assert.Throws<ArgumentException>(() => Resolve("wizard_arcane_lance",
            """{"range":20,"hitRadius":0.3,"maxTargets":0,"damageRatio":3.2}"""));
    }

    [Fact]
    public void ExcessiveCircleDurationIsRejectedBeforePulseAllocation()
    {
        Assert.Throws<ArgumentException>(() => Resolve("archmage_meteor_circle",
            """{"range":16,"radius":3,"durationTicks":2000000000,"pulseTicks":10,"power":0.55}"""));
    }

    [Theory]
    [InlineData("mage_frost_ball", "freezeChance", "120")]
    [InlineData("mage_frost_ball", "freezeDurationTicks", "0")]
    [InlineData("mage_frost_ball", "damageRatio", "-1")]
    [InlineData("mage_heal_aura", "healAmount", "0")]
    [InlineData("wizard_arcane_overload", "manaThreshold", "1.1")]
    [InlineData("wizard_mana_veil", "outgoingMultiplier", "1.1")]
    [InlineData("wizard_mana_veil", "manaRegenBonus", "101")]
    [InlineData("wizard_cinder_lance", "conditionChance", "0")]
    [InlineData("wizard_storm_chain", "conditionTicks", "401")]
    [InlineData("wizard_triad_convergence", "maxTargets", "1.5")]
    [InlineData("archmage_astral_spear", "power", "0")]
    [InlineData("archmage_meteor_circle", "power", "-0.1")]
    public void PluginRejectedParameterValuesAreNotMarkedSupported(string id, string key, string invalidValue)
    {
        var parameters = ValidParameters(id);
        parameters[key] = JsonNode.Parse(invalidValue);
        Assert.Throws<ArgumentException>(() => BalanceSkillMagicProfiles.Resolve(new JsonObject
        {
            ["implementationId"] = id, ["params"] = parameters
        }));
    }

    [Theory]
    [InlineData("mage_fireball", "damageRatio")]
    [InlineData("mage_frost_ball", "freezeChance")]
    [InlineData("wizard_arcane_lance", "hitRadius")]
    [InlineData("wizard_mana_veil", "manaRegenBonus")]
    [InlineData("archmage_orbital_volley", "power")]
    [InlineData("archmage_meteor_circle", "pulseTicks")]
    public void PluginRequiredParametersCannotSilentlyUseFallbacks(string id, string key)
    {
        var parameters = ValidParameters(id);
        parameters.Remove(key);
        Assert.Throws<ArgumentException>(() => BalanceSkillMagicProfiles.Resolve(new JsonObject
        {
            ["implementationId"] = id, ["params"] = parameters
        }));
    }

    [Theory]
    [InlineData("mage_fireball", "damageRatio")]
    [InlineData("mage_frost_ball", "damageRatio")]
    [InlineData("mage_heal_aura", "healAmount")]
    [InlineData("wizard_arcane_lance", "damageRatio")]
    [InlineData("wizard_prismatic_dart", "damageRatio")]
    [InlineData("wizard_elemental_ball", "damageRatio")]
    [InlineData("wizard_mana_siphon", "damageRatio")]
    [InlineData("wizard_arcane_overload", "damageRatio")]
    [InlineData("wizard_self_heal", "healPercent")]
    [InlineData("wizard_mana_veil", "incomingMultiplier")]
    [InlineData("wizard_cinder_lance", "damageRatio")]
    [InlineData("wizard_storm_chain", "damageRatio")]
    [InlineData("wizard_triad_convergence", "damageRatio")]
    [InlineData("archmage_astral_spear", "power")]
    [InlineData("archmage_starfall", "power")]
    [InlineData("archmage_orbital_volley", "power")]
    [InlineData("archmage_aegis_circle", "power")]
    [InlineData("archmage_meteor_circle", "power")]
    [InlineData("archmage_renewal_circle", "power")]
    [InlineData("archmage_vigor_circle", "power")]
    public void EverySupportedMagicProfileAcceptsMasterValuesAndRejectsInvalidPositiveParameter(string id, string key)
    {
        var valid = ValidParameters(id);
        Assert.True(BalanceSkillMagicProfiles.Resolve(new JsonObject
        {
            ["implementationId"] = id, ["params"] = valid.DeepClone()
        })!.Supported);
        valid[key] = -1;
        Assert.Throws<ArgumentException>(() => BalanceSkillMagicProfiles.Resolve(new JsonObject
        {
            ["implementationId"] = id, ["params"] = valid
        }));
    }

    private static JsonObject ValidParameters(string id) => JsonNode.Parse(id switch
    {
        "mage_fireball" => """{"range":16,"radius":2.25,"damageRatio":1.32,"maxTargets":4,"projectileSpeed":1.45,"projectileHitRadius":0.45}""",
        "mage_frost_ball" => """{"range":16,"radius":2.25,"damageRatio":0.45,"maxTargets":4,"projectileSpeed":1.45,"projectileHitRadius":0.45,"freezeChance":75,"freezeDurationTicks":40}""",
        "mage_heal_aura" => """{"radius":4,"height":3,"healAmount":5,"healPercent":1}""",
        "wizard_arcane_lance" => """{"range":20,"hitRadius":0.3,"maxTargets":3,"damageRatio":3.2}""",
        "wizard_prismatic_dart" => """{"range":16,"hitRadius":0.3,"projectileSpeed":1.6,"damageRatio":1.4}""",
        "wizard_elemental_ball" => """{"range":16,"radius":2.25,"damageRatio":2.45,"maxTargets":4,"projectileSpeed":1.45,"projectileHitRadius":0.45}""",
        "wizard_mana_siphon" => """{"range":14,"hitRadius":0.35,"projectileSpeed":1.2,"damageRatio":1.2,"manaRecovery":20}""",
        "wizard_arcane_overload" => """{"range":16,"radius":3.5,"maxTargets":5,"damageRatio":3.8,"manaThreshold":0.6,"empoweredMultiplier":1.4}""",
        "wizard_self_heal" => """{"healPercent":25}""",
        "wizard_mana_veil" => """{"durationTicks":100,"incomingMultiplier":0.8,"outgoingMultiplier":0.8,"manaRegenBonus":40}""",
        "wizard_cinder_lance" => """{"range":16,"hitRadius":0.6,"maxTargets":3,"damageRatio":2.2,"conditionChance":70,"conditionTicks":140}""",
        "wizard_storm_chain" => """{"range":16,"hitRadius":0.5,"maxTargets":4,"chainRadius":4,"damageRatio":2,"conditionChance":70,"conditionTicks":120}""",
        "wizard_triad_convergence" => """{"range":16,"radius":3,"maxTargets":5,"damageRatio":1.2,"conditionBonusRatio":0.25}""",
        "archmage_astral_spear" => """{"range":18,"radius":2,"power":2}""",
        "archmage_starfall" => """{"range":16,"radius":3,"power":1.4}""",
        "archmage_orbital_volley" => """{"range":18,"radius":4,"power":0.75}""",
        "archmage_aegis_circle" => """{"range":16,"radius":3,"power":0.08,"durationTicks":160,"pulseTicks":20}""",
        "archmage_meteor_circle" => """{"range":16,"radius":3,"power":0.55,"durationTicks":120,"pulseTicks":20}""",
        "archmage_renewal_circle" => """{"range":16,"radius":3,"power":0.03,"durationTicks":120,"pulseTicks":20}""",
        "archmage_vigor_circle" => """{"range":16,"radius":3,"power":0.05,"durationTicks":140,"pulseTicks":20}""",
        _ => throw new ArgumentOutOfRangeException(nameof(id))
    })!.AsObject();

    private static BalanceSkillProfile Resolve(string id, string parameters)
    {
        var definition = new JsonObject
        {
            ["implementationId"] = id,
            ["params"] = JsonNode.Parse(parameters)
        };
        return Assert.IsType<BalanceSkillProfile>(BalanceSkillMagicProfiles.Resolve(definition));
    }

    private static BalanceSkillView View(params string[] conditions)
        => new(new Dictionary<string, double>(), new Dictionary<string, double>(),
            100, 100, 50, 50, 0, 0, false, false, false,
            new HashSet<string>(conditions), 0);
}
