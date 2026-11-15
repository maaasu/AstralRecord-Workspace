using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceMagicRuntimeTests
{
    [Theory]
    [MemberData(nameof(Definitions))]
    public void VerifiedDefinitionsResolveToRuntimeWithoutMutatingInput(JsonObject definition)
    {
        var before = definition.ToJsonString();
        var profile = Assert.IsType<BalanceSkillProfile>(BalanceMagicRuntime.Resolve(definition));
        Assert.True(profile.Supported, string.Join(";", profile.Reasons));
        Assert.True(profile.Runtime);
        Assert.Equal(definition["passive"] is not null, profile.Passive);
        Assert.Equal(before, definition.ToJsonString());
        Assert.NotEmpty(profile.Sources);
    }

    [Fact]
    public void UnrelatedImplementationIsNotClaimed()
        => Assert.Null(BalanceMagicRuntime.Resolve(new JsonObject { ["implementationId"] = "unknown" }));

    [Theory]
    [InlineData("mage_blink", "maxDistance", "11")]
    [InlineData("mage_frost_blizzard", "damageIntervalTicks", "0")]
    [InlineData("mage_sparking", "projectileCount", "1.5")]
    [InlineData("wizard_meteor", "impactDelayTicks", "3")]
    [InlineData("wizard_emulate_spark", "maxHitsPerTarget", "10")]
    [InlineData("wizard_emulate_spark", "bounceHeight", "0.04")]
    [InlineData("wizard_rime_nova", "radius", "11")]
    [InlineData("wizard_spectral_orrery", "chainWindowTicks", "401")]
    [InlineData("archmage_bind_circle", "pullTicks", "19")]
    [InlineData("archmage_celestial_circle", "radius", "2")]
    [InlineData("archmage_stasis_circle", "pulseTicks", "11")]
    [InlineData("adventurer_meditation", "chargeTicks", "61")]
    public void PluginRejectedParametersCannotBecomeSupported(string id, string key, string value)
    {
        var definition = Definition(id); definition["params"]![key] = JsonNode.Parse(value);
        Assert.Throws<ArgumentException>(() => BalanceMagicRuntime.Resolve(definition));
    }

    [Fact]
    public void OrreryCollectsCompositeMagicAfterEffectiveHitAndReleasesAfterPrimaryConsumption()
    {
        var world = new World("wizard_spectral_orrery");
        var attack = Skill("attack", "{}");
        world.C.Hit(world.C.Snapshot(attack), [new("FIRE", 1), new("ICE", 1), new("LIGHTNING", 1)]);
        Assert.Single(world.Hits);
        world.C.SetResource("MP", 10); world.Advance(1);
        Assert.Single(world.Hits); // 本体消費後の残量不足は三属性を失う。
        world.C.SetResource("MP", 100);
        world.C.Hit(world.C.Snapshot(attack), [new("FIRE", 1), new("ICE", 1), new("LIGHTNING", 1)]);
        world.Advance(2);
        var proc = Assert.Single(world.Hits, hit => hit.Id == "wizard_spectral_orrery");
        Assert.Equal(2, proc.Tick);
        Assert.True(proc.Secondary);
        Assert.Equal(new[] { "FIRE", "ICE", "LIGHTNING" }, proc.Components.Select(component => component.Element));
        Assert.Equal(88, world.C.Mp);
        Assert.Equal(3, world.Hits.Count); // 追撃自身で再収集しない。
    }

    [Fact]
    public void SameOrreryElementDoesNotExtendTheInitialWindow()
    {
        var world = new World("wizard_spectral_orrery"); var attack = world.C.Snapshot(Skill("attack", "{}"));
        world.C.Hit(attack, 1, element: "FIRE"); world.Advance(199);
        world.C.Hit(attack, 1, element: "FIRE"); world.Advance(201);
        world.C.Hit(attack, 1, element: "ICE"); world.C.Hit(attack, 1, element: "LIGHTNING"); world.Advance(202);
        Assert.DoesNotContain(world.Hits, hit => hit.Id == "wizard_spectral_orrery");
    }

    [Fact]
    public void ConditionPassivesSpendOnlyOnSuccessfulSkillConditionAndBurnWaitsTwentyTicks()
    {
        var world = new World("wizard_burn_meteor_strike", "wizard_lightning_strike");
        var source = world.C.Snapshot(Skill("attack", "{}"));
        world.Event(new("condition_applied", source, Condition: "BURNING", Reason: "NORMAL_ATTACK"));
        Assert.Equal(100, world.C.Mp);
        world.C.ApplyCondition(source, "BURNING", 100, 100);
        Assert.Equal(72, world.C.Mp);
        Assert.Equal(1, world.C.CircleCount);
        world.Advance(19); Assert.Empty(world.Hits);
        world.Advance(20); Assert.Equal("wizard_burn_meteor_strike", Assert.Single(world.Hits).Id);
        Assert.Equal(0, world.C.CircleCount);
        world.C.ApplyCondition(source, "SHOCKED", 100, 100);
        Assert.Equal(16, world.C.Mp);
        Assert.Equal("wizard_lightning_strike", world.Hits[^1].Id);
        world.C.ApplyCondition(source, "SHOCKED", 100, 100); Assert.Equal(2, world.Hits.Count);
    }

    [Fact]
    public void PickupRemainsWhenFullThenConsumesOnlyAfterRealFixedManaRecovery()
    {
        var world = new World("wizard_prism_condition"); world.C.EnemyPosition = new(0, 0, .5);
        world.C.ApplyCondition(world.C.Snapshot(Skill("attack", "{}")), "FROZEN", 100, 40);
        world.Advance(1); Assert.Equal(1, world.C.CircleCount);
        world.C.SetResource("MP", 90); world.Advance(2);
        Assert.Equal(93, world.C.Mp); Assert.Equal(0, world.C.CircleCount);
    }

    [Fact]
    public void RimeWaveFindsTheBoundingBoxOnceAtItsActualExpansionTick()
    {
        var world = new World(); var skill = Definition("wizard_rime_nova");
        world.C.EnemyPosition = new(0, 0, 3);
        world.Cast(skill); world.Advance(2); Assert.Empty(world.Hits);
        world.Advance(9);
        var hit = Assert.Single(world.Hits); Assert.Equal(3, hit.Tick);
        Assert.Contains("FROZEN", world.C.EnemyConditions); Assert.Contains("CHILLED", world.C.EnemyConditions);
    }

    [Fact]
    public void MeteorUsesSightEndAndHitsOnlyAtImpactOrNewWaveBand()
    {
        var world = new World(); var meteor = Definition("wizard_meteor");
        world.C.EnemyPosition = new(0, 0, 20); world.Cast(meteor);
        world.Advance(63); Assert.Empty(world.Hits); // center z16、bounds最近点3.7、62tick半径3.5。
        world.Advance(64); Assert.Equal(64, Assert.Single(world.Hits).Tick);
        world.Advance(80); Assert.Single(world.Hits);
        Assert.Contains("BURNING", world.C.EnemyConditions);
    }

    [Fact]
    public void PrismAbsorbsTheEligibleProjectileBeforeEnemyAndUsesPlacementSnapshot()
    {
        var world = new World(); world.C.EnemyPosition = new(0, 0, 6);
        var prism = Definition("wizard_elemental_prism"); world.Cast(prism);
        var bolt = Definition("adventurer_lightning_bolt"); bolt["tags"] = new JsonArray("prism", "magic");
        var projectile = new BalanceRuntimeProjectile(14, 2.8, .45, world.C.Eye, new(0, 0, 1), "MAGIC", [new("LIGHTNING", 1.74)]);
        Assert.True(world.C.Intercept!(world.C.Snapshot(bolt), projectile, world.C.Eye, world.C.Eye + new BalanceVector(0, 0, 4)));
        Assert.Equal(0, world.C.CircleCount); // プリズム本体は陣ではない。
        world.Stats["MAGIC_ATTACK"] = 900;
        world.Advance(5);
        Assert.Equal(5, world.Hits.Count);
        Assert.All(world.Hits, hit => { Assert.Equal("wizard_elemental_prism", hit.Id); Assert.Equal(100, hit.AttackStat); });
        Assert.Equal(new[] { 1, 2, 3, 4, 5 }, world.Hits.Select(hit => hit.Tick));
    }

    [Fact]
    public void AstralRaySnapshotsUncappedCircleCountAndRegistersItsOwnFiniteCircles()
    {
        var world = new World(); var ray = Definition("archmage_astral_ray");
        for (var index = 0; index < 4; index++) world.C.Circles[index.ToString()] = new(index.ToString(), "other", new(), 1, 0, 100);
        world.Cast(ray); world.Advance(60);
        Assert.Equal(9, world.Hits.Count);
        Assert.Equal(Enumerable.Range(0, 9).Select(index => 10 + index * 5), world.Hits.Select(hit => hit.Tick));
        Assert.Equal(4, world.C.CircleCount);
    }

    [Fact]
    public void MeditationHasChargeGrowingRegenCompletionAndExplicitInterruption()
    {
        var world = new World("adventurer_meditation"); world.C.Sneaking = true;
        world.Advance(59); Assert.Equal(1, world.C.NaturalRegenMultiplier);
        world.Advance(60); Assert.Equal(2, world.C.NaturalRegenMultiplier);
        Assert.Contains("adventurer_meditation", world.Buffs);
        world.Advance(80); Assert.Equal(2.5, world.C.NaturalRegenMultiplier);
        world.C.SetResource("MP", 1); world.C.SetResource("ENG", 1); world.Advance(200);
        Assert.Equal(100, world.C.Mp); Assert.Equal(100, world.C.Eng); Assert.DoesNotContain("adventurer_meditation", world.Buffs);
        world.C.SetResource("MP", 1); world.Advance(300); Assert.Equal(1, world.C.Mp);
        world.Event(new("normal_attempt")); world.Advance(361); Assert.Equal(2, world.C.NaturalRegenMultiplier);
    }

    [Fact]
    public void ArcaneFlowTracksPreviousSuccessfulSkillRegardlessOfItsTag()
    {
        var world = new World("mage_arcane_flow"); var first = Skill("physical", "{}"); var next = Skill("magic", "{}");
        next["tags"] = new JsonArray("magic");
        world.Event(new("cast_completed", world.C.Snapshot(first)));
        world.Event(new("before_cast", world.C.Snapshot(next)));
        Assert.Equal(5, world.C.AdditionalCastReductionPercent);
        world.C.AdditionalCastReductionPercent = 0;
        world.Event(new("cast_completed", world.C.Snapshot(next)));
        world.Event(new("before_cast", world.C.Snapshot(next))); Assert.Equal(0, world.C.AdditionalCastReductionPercent);
    }

    [Fact]
    public void PhoenixNeedsNormalOrSupportCircleTargetAndUsesFixedManaAndPhoenixResources()
    {
        var world = new World("archmage_phoenix_resonance"); var attack = world.C.Snapshot(Skill("attack", "{}"));
        world.C.Hit(attack, 1); world.Advance(200);
        Assert.Single(world.Hits); Assert.Equal(500, world.C.Resource("PHOENIX"));
        world.C.Hit(attack, 1, source: "NORMAL_ATTACK"); world.Advance(220);
        Assert.Equal(80, world.C.Mp); world.Advance(250);
        var phoenix = Assert.Single(world.Hits, hit => hit.Id == "archmage_phoenix_resonance");
        Assert.Equal(250, phoenix.Tick); Assert.Equal(480, world.C.Resource("PHOENIX"));
    }

    [Fact]
    public void HealingContactConsumesOneCircleAndClearsItsCooldownOnlyOnActualRecovery()
    {
        var world = new World(); world.C.Facing = new(0, -1, 0); var heal = Definition("archmage_heal_circle");
        world.Cast(heal); world.Advance(1); Assert.Equal(1, world.C.CircleCount);
        world.C.SetResource("HP", 50); world.Advance(2);
        Assert.Equal(64, world.C.Hp); Assert.Equal(0, world.C.CircleCount); Assert.Equal(0, world.Cooldowns["archmage_heal_circle"]);
    }

    [Fact]
    public void SoleCasterDeathCannotUseOwnRebornCircle()
    {
        var world = new World(); world.C.Facing = new(0, -1, 0); world.Cast(Definition("archmage_reborn_protect_circle"));
        Assert.Equal(1, world.C.CircleCount); world.C.SetResource("HP", 0); world.Event(new("death"));
        Assert.Equal(0, world.C.Hp); Assert.Equal(0, world.C.CircleCount);
    }

    [Fact]
    public void RebornSelectsNearestDeadAllyRestoresResourcesAndConsumesExactlyOneCircle()
    {
        var world = new World(); world.C.Facing = new(0, -1, 0);
        var farther = world.AddAlly("farther", new(0, 0, 2), 0); var closer = world.AddAlly("closer", new(0, 0, 1), 0);
        closer.ShieldEnabled = true; closer.Stats["MAX_SHIELD"] = 40;
        world.Cast(Definition("archmage_reborn_protect_circle")); world.Advance(1);
        Assert.True(farther.Dead); Assert.False(closer.Dead);
        Assert.Equal((100d, 100d, 100d, 40d), (closer.Hp, closer.Mp, closer.Eng, closer.Shield));
        Assert.Equal(61, closer.InvulnerableUntil); Assert.Contains("closer:archmage_battle_confusion", world.Buffs);
        Assert.Equal(0, world.C.CircleCount);
    }

    [Fact]
    public void HealAndClearContactUsePlayerIdOrderAndPreserveUnaffectedRecipients()
    {
        var world = new World(); world.C.Facing = new(0, -1, 0);
        var z = world.AddAlly("z", new(0, 0, .3), 20); var a = world.AddAlly("a", new(0, 0, 1), 20);
        world.Cast(Definition("archmage_heal_circle")); world.Advance(1);
        Assert.Equal(34, a.Hp); Assert.Equal(20, z.Hp);
        a.Conditions.Add("FROZEN"); z.Conditions.Add("FROZEN");
        world.Cast(Definition("archmage_clear_circle")); world.Advance(2);
        Assert.Empty(a.Conditions); Assert.Contains("FROZEN", z.Conditions);
    }

    [Fact]
    public void CelestialBuffStartsAfterConstructionAndLeavesAnAllyAtNextSynchronization()
    {
        var world = new World(); world.C.Facing = new(0, -1, 0); var ally = world.AddAlly("ally", new(0, 0, .5), 100);
        world.Cast(Definition("archmage_celestial_circle")); world.Advance(38);
        Assert.DoesNotContain("ally:archmage_celestial_circle", world.Buffs);
        world.Advance(39); Assert.Contains("ally:archmage_celestial_circle", world.Buffs);
        ally.Position = new(0, 0, 10); world.Advance(43); Assert.DoesNotContain("ally:archmage_celestial_circle", world.Buffs);
    }

    [Fact]
    public void ExistingSupportFieldsApplyOnlyWhilePlayerAndAllyRemainInsideThePlacedCircle()
    {
        var world = new World(); world.C.Facing = new(0, -1, 0); world.C.SetResource("HP", 50);
        var ally = world.AddAlly("ally", new(0, 0, .5), 50);
        world.Cast(Definition("archmage_renewal_circle")); world.Advance(1);
        Assert.Equal(53, world.C.Hp); Assert.Equal(53, ally.Hp);
        ally.Position = new(0, 0, 10); world.Advance(21);
        Assert.Equal(56, world.C.Hp); Assert.Equal(53, ally.Hp);
    }

    [Fact]
    public void GeometryAndPassiveTimingMatchActualJavaRuntimeMethodOutput()
    {
        var prism = Golden("magic_prism_intersection");
        Assert.Equal(prism["distance"]!.GetValue<double>(), BalanceMagicGeometry.FirstSphereHit(new(0, 1.62, 0), new(0, 0, 1), new(0, 1.5, 3), 1.27, 4), 12);
        Assert.Equal(prism["behindMiss"]!.GetValue<bool>(), double.IsNaN(BalanceMagicGeometry.FirstSphereHit(new(0, 1.62, 0), new(0, 0, -1), new(0, 1.5, 3), 1.27, 4)));
        var vortex = Golden("magic_vortex_velocity");
        EqualVector(vortex["ordinary"]!, BalanceMagicGeometry.VortexVelocity(new(), new(3, 2, 4), new(0, 0, 1), .16, .08, .04));
        EqualVector(vortex["fallback"]!, BalanceMagicGeometry.VortexVelocity(new(), new(0, 2, 0), new(0, 1, 0), .16, .08, .04));
        var spiral = Golden("magic_spark_spiral")["steps"]!.AsArray(); var radius = 0d; var angle = 0d;
        foreach (var step in spiral) EqualVector(step!, BalanceMagicGeometry.AdvanceSpiral(ref radius, ref angle, Math.PI / 6, .14, Math.PI / 60));
        Assert.Equal(Golden("magic_emulate_bounce")["verticalSpeed"]!.GetValue<double>(), BalanceMagicGeometry.BounceSpeed(1.3), 12);
        var world = new World("adventurer_meditation"); world.C.Sneaking = true;
        var ticks = new[] { 59, 60, 79, 80, 199, 200 }; var values = Golden("magic_meditation_multiplier")["values"]!.AsArray();
        for (var index = 0; index < ticks.Length; index++) { world.Advance(ticks[index]); Assert.Equal(values[index]!.GetValue<double>(), world.C.NaturalRegenMultiplier); }
    }

    private static JsonObject Golden(string id) => BalanceSkillExecutorGoldenTests.Fixture("magic-runtime-golden.json", id)["expected"]!.AsObject();
    private static void EqualVector(JsonNode expected, BalanceVector actual)
    {
        Assert.Equal(expected[0]!.GetValue<double>(), actual.X, 12); Assert.Equal(expected[1]!.GetValue<double>(), actual.Y, 12); Assert.Equal(expected[2]!.GetValue<double>(), actual.Z, 12);
    }

    public static IEnumerable<object[]> Definitions() => Parameters.Select(pair => new object[] { Definition(pair.Key) });
    private static JsonObject Definition(string id)
    {
        var skill = Skill(id, Parameters[id]);
        if (PassiveIds.Contains(id)) skill["passive"] = new JsonObject { ["bindRequired"] = true };
        if (id == "wizard_spectral_orrery") { skill["resourceType"] = "MANA"; skill["resourceCost"] = 12; skill["cooldownTicks"] = 160; }
        if (id == "wizard_burn_meteor_strike") skill["resourceCost"] = 28;
        if (id == "wizard_lightning_strike") skill["resourceCost"] = 56;
        return skill;
    }
    private static JsonObject Skill(string id, string parameters) => new()
    {
        ["id"] = id, ["implementationId"] = id, ["params"] = JsonNode.Parse(parameters), ["resourceType"] = "MANA", ["resourceCost"] = 0,
        ["cooldownTicks"] = 0, ["tags"] = new JsonArray("magic")
    };
    private static readonly HashSet<string> PassiveIds = ["adventurer_meditation", "mage_arcane_flow", "mage_blink", "wizard_burn_meteor_strike", "wizard_lightning_strike", "wizard_prism_condition", "wizard_spectral_orrery", "archmage_phoenix_resonance"];
    private static readonly Dictionary<string, string> Parameters = new()
    {
        ["adventurer_lightning_bolt"] = """{"range":14,"damageRatio":1.74,"chainRadius":5,"chainDamageRatio":0.48,"maxChainTargets":2,"projectileSpeed":2.8,"projectileHitRadius":0.45}""",
        ["adventurer_meditation"] = """{"chargeTicks":60,"initialRegenMultiplier":2,"regenMultiplierIncrement":0.5,"activeDurationTicks":140,"buffId":"buff:adventurer_meditation","chargeParticleIntervalTicks":10,"activeParticleIntervalTicks":5,"activeSoundIntervalTicks":40}""",
        ["mage_arcane_flow"] = """{"castTimeReductionPercent":5}""", ["mage_blink"] = """{"maxDistance":10}""",
        ["mage_heal_aura"] = """{"radius":4,"height":3,"healAmount":5,"healPercent":1}""",
        ["mage_frost_blizzard"] = """{"damageRatio":0.24,"radius":2.75,"height":2.5,"movementSpeed":0.18,"durationTicks":200,"damageIntervalTicks":10,"maxTargets":8,"orbitVelocity":0.16,"inwardVelocity":0.08,"verticalVelocity":0.04}""",
        ["mage_sparking"] = """{"damageRatio":1.2,"projectileCount":5,"range":16,"projectileSpeed":1.45,"spiralRadiusGrowth":0.14,"spiralDegreesPerTick":3,"projectileHitRadius":0.6,"durationTicks":50,"shockChance":25,"shockDurationTicks":100}""",
        ["wizard_burn_meteor_strike"] = "{}", ["wizard_elemental_prism"] = """{"placementRange":3,"radius":6,"damageRatio":0.4,"maxTargets":5,"projectileCount":5,"durationTicks":7200,"projectileSpeed":1.5}""",
        ["wizard_emulate_spark"] = """{"damageRatio":0.75,"projectileCount":1,"maxHitsPerTarget":4,"projectileSpeedPerSecond":15,"projectileHitRadius":0.35,"bounceHeight":1.3,"spreadAngle":30,"durationTicks":30,"shockChance":12,"shockDurationTicks":100}""",
        ["wizard_lightning_strike"] = """{"range":30,"damageRatio":1.5}""", ["wizard_meteor"] = """{"range":16,"radius":8,"damageRatio":6.3,"burningChance":25,"burningDurationTicks":100,"impactDelayTicks":60}""",
        ["wizard_prism_condition"] = """{"radius":1,"durationTicks":60,"manaRecoveryRatio":0.03}""", ["wizard_rime_nova"] = """{"radius":8,"maxTargets":5,"damageRatio":2,"burningMultiplier":1.5,"conditionChance":70,"conditionTicks":100,"frozenTicks":40}""",
        ["wizard_spectral_orrery"] = """{"damageRatio":0.4,"radius":2.5,"range":24,"maxTargets":3,"chainWindowTicks":200}""",
        ["archmage_astral_ray"] = """{"range":18,"damageRatio":1.2,"circleCount":1,"circleIntervalTicks":5}""", ["archmage_bind_circle"] = """{"range":16,"radius":4,"pullTicks":20,"bindTicks":600,"circleLifetimeTicks":400}""",
        ["archmage_celestial_circle"] = """{"placementRange":24,"durationTicks":600,"effectLevel":1,"radius":1,"finalDamageBonus":0.5,"otherBonus":1}""", ["archmage_clear_circle"] = """{"range":12,"radius":3,"durationTicks":120}""",
        ["archmage_gravity_nova"] = """{"range":16,"radius":3,"power":2}""", ["archmage_heal_circle"] = """{"range":16,"radius":3,"healPercent":14,"durationTicks":20}""",
        ["archmage_phoenix_resonance"] = """{"phoenixMax":500,"targetRange":50,"despawnDelayTicks":600,"burningChance":15}""", ["archmage_reborn_protect_circle"] = """{"range":16,"radius":5,"durationTicks":400}""",
        ["archmage_repulsion_circle"] = """{"range":16,"radius":3,"power":0.3,"durationTicks":160,"pulseTicks":20}""", ["archmage_stasis_circle"] = """{"range":16,"radius":3,"power":0.3,"durationTicks":160,"pulseTicks":20}""",
        ["archmage_aegis_circle"] = """{"range":16,"radius":3,"power":0.08,"durationTicks":160,"pulseTicks":20}""",
        ["archmage_renewal_circle"] = """{"range":16,"radius":3,"power":0.03,"durationTicks":120,"pulseTicks":20}""",
        ["archmage_vigor_circle"] = """{"range":16,"radius":3,"power":0.05,"durationTicks":140,"pulseTicks":20}"""
    };

    private sealed record Hit(int Tick, string Id, IReadOnlyList<BalanceSkillComponent> Components, bool Secondary, double AttackStat);
    private sealed class World
    {
        internal readonly Dictionary<string, double> Stats = new() { ["MAX_HEALTH"] = 100, ["MAX_MANA"] = 100, ["MAX_ENERGY"] = 100, ["MAGIC_ATTACK"] = 100 };
        internal readonly HashSet<string> Buffs = [];
        internal readonly Dictionary<string, int> Cooldowns = [];
        internal readonly List<Hit> Hits = [];
        internal readonly List<BalanceRuntimeProjectile> Projectiles = [];
        private readonly Dictionary<string, double> resources = new() { ["HP"] = 100, ["MP"] = 100, ["ENG"] = 100, ["ENEMY_HP"] = 1_000_000 };
        private readonly SortedDictionary<int, List<(Action Action, string Scope, int Generation)>> queue = [];
        private readonly Dictionary<string, int> generations = [];
        private readonly BalanceMagicRuntime runtime = new();
        private int nextTick;
        internal BalanceRuntimeContext C { get; }
        internal World(params string[] passiveIds)
        {
            C = new BalanceRuntimeContext
            {
                Catalog = new(), Build = new(), Request = new(), Random = new(7),
                Definitions = Parameters.Keys.ToDictionary(id => id, Definition), Passives = passiveIds.ToDictionary(id => id, Definition),
                ReadResource = key => resources.GetValueOrDefault(key),
                WriteResource = (key, value) => resources[key] = Math.Clamp(value, 0, key is "HP" or "MP" or "ENG" ? 100 : 1_000_000),
                ReadStats = enemy => enemy ? new Dictionary<string, double> { ["MOVEMENT_SPEED"] = 100 } : Stats,
                Queue = (cast, delay, action, scope) =>
                {
                    var tick = C!.Tick + delay; if (!queue.TryGetValue(tick, out var list)) queue[tick] = list = [];
                    list.Add((action, scope, generations.GetValueOrDefault(scope)));
                },
                Damage = (cast, type, components, source, shield, secondary, victim) =>
                {
                    Hits.Add(new(C!.Tick, cast.ImplementationId, components, secondary, cast.Stat("MAGIC_ATTACK")));
                    var hit = new BalanceRuntimeHit(cast, type, source, components, 1, 1, 0, false, false, false, secondary);
                    Event(new("damage_applied", cast, hit)); return hit;
                },
                Healing = (cast, amount) => { var before = C!.Hp; C.AddResource("HP", amount); return C.Hp - before; },
                Condition = (cast, type, chance, ticks, strength) =>
                { if (chance <= 0) return false; C!.EnemyConditions.Add(type); Event(new("condition_applied", cast, Condition: type)); return true; },
                StatEffect = (key, status, amount, mode, ticks, target) => { }, MultiplierEffect = (key, type, amount, ticks) => { },
                Buff = (cast, id) => { Buffs.Add(id); return true; }, RemoveBuff = id => Buffs.Remove(id),
                ApplyAllyBuff = (cast, ally, id) => { Buffs.Add(ally.Id + ":" + id); return true; }, RemoveAllyBuff = (ally, id) => Buffs.Remove(ally.Id + ":" + id),
                ClearAllyNegativeEffects = ally => { var count = ally.Conditions.Count; ally.Conditions.Clear(); return count; },
                AllyHealing = (cast, ally, amount) => { var before = ally.Hp; ally.Hp = Math.Min(100, ally.Hp + amount); return ally.Hp - before; },
                AllyRecovery = (ally, resource, amount) => { if (resource is "MP" or "MP_FIXED") ally.Mp = Math.Min(100, ally.Mp + amount); else if (resource is "ENG" or "ENG_SELF") ally.Eng = Math.Min(100, ally.Eng + amount); else if (resource == "SHIELD" && ally.ShieldEnabled) ally.Shield = Math.Min(ally.Stats.GetValueOrDefault("MAX_SHIELD"), ally.Shield + amount); },
                Projectile = (cast, projectile) => Projectiles.Add(projectile),
                SetSkillCooldown = (id, tick) => Cooldowns[id] = tick, Trace = (cast, kind, amount, detail) => { },
                Dispatch = value => Event(value), MovePlayer = (start, direction, distance) => start + direction * distance,
                Velocity = (target, velocity) => true, CancelScope = scope => generations[scope] = generations.GetValueOrDefault(scope) + 1,
                TryConsumeCastResources = cast => C!.Spend("MP", BalanceCombatData.Number(cast.Definition["resourceCost"])),
                EnemyPosition = new(0, 0, 3)
            };
            Event(new("bind"));
        }
        internal void Cast(JsonObject definition) => Assert.True(runtime.Cast(C, C.Snapshot(definition)).Success);
        internal BalanceRuntimeAlly AddAlly(string id, BalanceVector position, double hp)
        {
            var ally = new BalanceRuntimeAlly { Id = id, Position = position, Hp = hp };
            ally.Stats["MAX_HEALTH"] = 100; ally.Stats["MAX_MANA"] = 100; ally.Stats["MAX_ENERGY"] = 100; C.Allies.Add(ally); return ally;
        }
        internal void Event(BalanceRuntimeEvent value) => runtime.OnEvent(C, value);
        internal void Advance(int until)
        {
            for (; nextTick <= until; nextTick++)
            {
                C.Tick = nextTick; C.NaturalRegenMultiplier = 1;
                foreach (var key in C.Circles.Where(pair => pair.Value.UntilTick <= C.Tick).Select(pair => pair.Key).ToArray()) C.Circles.Remove(key);
                Event(new("tick"));
                while (queue.Remove(C.Tick, out var actions))
                    foreach (var item in actions) if (item.Generation == generations.GetValueOrDefault(item.Scope)) item.Action();
            }
        }
    }
}
