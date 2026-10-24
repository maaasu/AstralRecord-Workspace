using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceRangedRuntimeTests
{
    [Fact]
    public void MarksMatchActualPluginRefreshCapExpiryAndConsumeTrace()
    {
        var runtime = new BalanceRangedRuntime();
        var trace = new List<int>();
        foreach (int tick in new[] { 0, 10, 20, 30 })
        {
            runtime.AddMark("enemy", tick);
            trace.Add(runtime.Marks("enemy", tick));
        }
        trace.Add(runtime.Marks("enemy", 149));
        trace.Add(runtime.Marks("enemy", 150));
        runtime.AddMark("enemy", 150);
        trace.Add(runtime.Marks("enemy", 150));
        runtime.ConsumeMarks("enemy");
        trace.Add(runtime.Marks("enemy", 150));
        Assert.Equal(Fixture("phantom_marks")["marks"]!.AsArray().Select(value => value!.GetValue<int>()), trace);
    }

    [Fact]
    public void SoulAndEclipseRatiosMatchActualExecutorAndKeepTargetMarksSeparate()
    {
        var runtime = new BalanceRangedRuntime();
        runtime.AddMark("enemy", 150);
        runtime.AddMark("enemy", 150);
        var fixture = Fixture("phantom_ratios");
        Assert.Equal(fixture["soul"]!.GetValue<double>(),
            runtime.MarkDamageRatio("enemy", 150, 2.8, .65, true, .25, true, .4), 10);
        Assert.Equal(fixture["eclipse"]!.GetValue<double>(),
            runtime.MarkDamageRatio("enemy", 150, 2.8, .65, false, .25, true, .4), 10);
        Assert.Equal(0, runtime.Marks("other", 150));
        Assert.Equal(2, runtime.Marks("enemy", 150));
    }

    [Fact]
    public void PreparedArrowIsConsumedEvenIfWeaponOrPassiveIsNoLongerValid()
    {
        var runtime = new BalanceRangedRuntime();
        runtime.ReadyArrow(10, 20);
        Assert.False(runtime.TakeArrow(15, false, true));
        Assert.False(runtime.TakeArrow(16, true, true));
        runtime.ReadyArrow(20, 20);
        Assert.False(runtime.TakeArrow(40, true, true));
        runtime.ReadyArrow(40, 20);
        Assert.True(runtime.TakeArrow(59, true, true));
        Assert.False(runtime.TakeArrow(59, true, true));
    }

    [Fact]
    public void StarweaveCapturesComboAtTriggerAndPreservesCooldownAcrossCleanup()
    {
        var runtime = new BalanceRangedRuntime();
        runtime.AddMark("enemy", 0);
        Assert.Equal(1.2, runtime.PrepareStarweave("enemy", 0, 120, .8, .4, false)!.Value, 10);
        runtime.ClearTransientState();
        Assert.Null(runtime.PrepareStarweave("enemy", 119, 120, .8, .4, true));
        Assert.Equal(.8, runtime.PrepareStarweave("enemy", 120, 120, .8, .4, false));
    }

    [Fact]
    public void PelletAndBallisticGeometryMatchActualPluginHelpers()
    {
        var directions = BalanceRangedRuntime.PelletDirections(new(0, 0, 1), 5, 30);
        var expected = Fixture("pellet_directions")["directions"]!.AsArray();
        for (int index = 0; index < directions.Count; index++) AssertVector(expected[index]!.AsArray(), directions[index]);
        var path = Fixture("rain_ballistic");
        var velocity = BalanceRangedRuntime.BallisticVelocity(new(0, 6.62, 0), new(3, .05, 8), 12, .14);
        AssertVector(path["velocity"]!.AsArray(), velocity);
        Assert.Equal(path["length"]!.GetValue<double>(), BalanceRangedRuntime.BallisticPathLength(velocity, 12, .14), 10);
    }

    [Fact]
    public void SavedRangedMastersResolveWithKnownParametersAndExistingSourcePaths()
    {
        string root = RepositoryRoot();
        var codec = new MasterDataCodec();
        int count = 0;
        foreach (string path in Directory.EnumerateFiles(Path.Combine(root, "40_filebase", "30.features.skill"), "*.yml"))
        {
            var definition = codec.Parse(path, File.ReadAllText(path)).Content!.AsObject();
            var profile = BalanceSkillRangedProfiles.Resolve(definition);
            if (profile is null) continue;
            count++;
            Assert.True(profile.Supported, profile.ImplementationId + ": " + string.Join(" / ", profile.Reasons));
            Assert.All(definition["params"]!.AsObject(), pair => Assert.Contains(pair.Key, profile.KnownParameters));
            Assert.All(profile.Sources, source => Assert.True(File.Exists(Path.Combine(root, source)), source));
        }
        Assert.Equal(29, count);
    }

    [Fact]
    public void ShadowAndSoulOnlyChangeMarksAfterLandedHitAndEvaluateMarksAtImpact()
    {
        var environment = new Environment();
        var runtime = environment.Runtime;
        var shadow = environment.Cast("phantom_archer_shadow_stitch", """{"range":20,"damageRatio":2.6,"projectileSpeed":2.2,"projectileHitRadius":0.4,"conditionChance":70,"conditionTicks":100}""");
        runtime.Cast(environment.Context, shadow);
        environment.Landed = false;
        environment.Collide(0);
        Assert.Equal(0, runtime.Marks("enemy", 0));
        runtime.Cast(environment.Context, shadow);
        environment.Landed = true;
        environment.Collide(1);
        Assert.Equal(1, runtime.Marks("enemy", 0));
        Assert.Single(environment.Conditions);
        var soul = environment.Cast("phantom_archer_soul_pierce", """{"range":24,"damageRatio":2.8,"markDamageRatio":0.65,"conditionBonusRatio":0.4,"projectileSpeed":2.6,"projectileHitRadius":0.45,"maxHits":4}""");
        runtime.Cast(environment.Context, soul);
        runtime.AddMark("enemy", 1);
        environment.Context.Tick = 2;
        environment.Landed = false;
        environment.Collide(2);
        Assert.Equal(2, runtime.Marks("enemy", 2));
        Assert.Equal(4.1, environment.Hits[^1].Components.Single().Ratio, 10);
        runtime.Cast(environment.Context, soul);
        environment.Landed = true;
        environment.Collide(3);
        Assert.Equal(0, runtime.Marks("enemy", 2));
    }

    [Fact]
    public void WraithIsIndependentSecondaryProjectileAndDoesNotAddMarks()
    {
        var environment = new Environment(new Dictionary<string, JsonObject>
        {
            ["phantom_archer_wraith_step"] = Definition("phantom_archer_wraith_step", """{"phantomDamageRatio":0.4,"readyDurationTicks":120}""")
        });
        environment.Runtime.OnEvent(environment.Context, new("dodge"));
        Assert.False(environment.Runtime.TakeArrow(0, true, true));
        environment.Runtime.Cast(environment.Context, environment.Cast("phantom_archer_mist_retreat", """{"backstepVelocity":1.4,"durationTicks":60,"evasionMultiplier":1.4}"""));
        var cast = environment.Cast("phantom_archer_shadow_stitch", """{"damageRatio":2.6}""");
        environment.Runtime.Cast(environment.Context, cast);
        Assert.Equal(2, environment.Projectiles.Count);
        Assert.Equal(.35, Math.Abs(environment.Projectiles[1].Projectile.Origin.X), 10);
        environment.Collide(1);
        Assert.True(Assert.Single(environment.Hits).Secondary);
        Assert.Equal(0, environment.Runtime.Marks("enemy", 0));
        environment.Collide(0);
        Assert.Equal(1, environment.Runtime.Marks("enemy", 0));
        environment.Runtime.Cast(environment.Context, cast);
        Assert.Equal(3, environment.Projectiles.Count);
    }

    [Fact]
    public void EchoVolleyRechecksOriginAndDirectionAtEachScheduledShot()
    {
        var environment = new Environment();
        var cast = environment.Cast("phantom_archer_echo_volley", """{"shotCount":3,"shotIntervalTicks":6}""");
        environment.Runtime.Cast(environment.Context, cast);
        Assert.Empty(environment.Projectiles);
        environment.RunUntil(1);
        environment.Context.PlayerPosition = new(3, 0, 0);
        environment.Context.Facing = new(1, 0, 0);
        environment.RunUntil(7);
        Assert.Equal(2, environment.Projectiles.Count);
        Assert.Equal(3, environment.Projectiles[1].Projectile.Origin.X);
        Assert.Equal(new BalanceVector(1, 0, 0), environment.Projectiles[1].Projectile.Direction);
    }

    [Fact]
    public void HealingAreaAllowsEntryDuringLifetimeButHealsEachPlayerOnce()
    {
        var environment = new Environment();
        var cast = environment.Cast("hunter_heal_arrow", """{"radius":2,"healAmount":12,"areaDurationTicks":3,"damageRatio":0.36}""");
        environment.Runtime.Cast(environment.Context, cast);
        environment.Projectiles[0].Projectile.OnCollision!(new(10, 0, 0));
        environment.RunUntil(0);
        Assert.Empty(environment.Heals);
        environment.Context.PlayerPosition = new(10, 0, 0);
        environment.RunUntil(1);
        Assert.Equal(12, Assert.Single(environment.Heals));
        environment.RunUntil(2);
        Assert.Single(environment.Heals);
    }

    [Fact]
    public void AlphaHealingUsesEachAllysMaximumHpAndTracksEntryPerArea()
    {
        var environment = new Environment();
        var ally = new BalanceRuntimeAlly { Id = "ally", Position = new(20, 0, 0), Hp = 50 };
        ally.Stats["MAX_HEALTH"] = 200;
        environment.Context.Allies.Add(ally);
        var cast = environment.Cast("archer_heal_arrow_alpha", """{"radius":2,"healPercent":3.5,"areaDurationTicks":3,"damageRatio":0.36}""");
        environment.Runtime.Cast(environment.Context, cast);
        environment.Projectiles[0].Projectile.OnCollision!(new(10, 0, 0));
        environment.RunUntil(0);
        Assert.Empty(environment.AllyHeals);
        ally.Position = new(10, 0, 0);
        environment.RunUntil(2);
        Assert.Equal(("ally", 7d), Assert.Single(environment.AllyHeals));
    }

    [Fact]
    public void RainPrecomputesSeededTrajectoriesAndReleasesThreeArrowsPerTick()
    {
        var environment = new Environment();
        var cast = environment.Cast("hunter_arrow_rain", """{"range":18,"radius":3,"arrowCount":45,"damageRatios":[0.84,0.36],"openingSpeed":1.6,"openingHitRadius":0.45,"rainHitRadius":0.75}""");
        environment.Runtime.Cast(environment.Context, cast);
        var opening = Assert.Single(environment.Projectiles);
        Assert.Equal(.05, opening.Projectile.Gravity);
        Assert.Equal(.1, opening.Cast.ShieldBreakRatio);
        opening.Projectile.OnTermination!(new(0, 0, 3), new(0, .1, 3), "ENTITY");
        environment.RunUntil(0);
        Assert.Equal(4, environment.Projectiles.Count);
        environment.Context.PlayerPosition = new(1000, 0, 0);
        environment.RunUntil(14);
        Assert.Equal(46, environment.Projectiles.Count);
        Assert.All(environment.Projectiles.Skip(1), shot =>
        {
            Assert.InRange(shot.Projectile.Origin.X, -1.25, 1.25);
            Assert.InRange(shot.Projectile.Origin.Y, 6.17, 7.08);
            Assert.InRange(shot.Projectile.MaxTicks, 10, 14);
            Assert.Equal(.14, shot.Projectile.Gravity);
            Assert.Equal(.1, shot.Cast.ShieldBreakRatio);
        });
    }

    [Fact]
    public void NormalLandedAppliesAttunementBeforeStarweaveComboAndAvoidsSecondaryRecursion()
    {
        var environment = new Environment(new Dictionary<string, JsonObject>
        {
            ["phantom_archer_spectral_attunement"] = Definition("phantom_archer_spectral_attunement", "{}"),
            ["archer_starweave_followup"] = Definition("archer_starweave_followup", """{"followupRatio":0.8,"comboBonusRatio":0.4,"range":30,"flightTicks":6}""")
        });
        var normal = environment.Cast("normal", "{}");
        environment.Runtime.OnEvent(environment.Context, new("normal_landed", Hit: environment.Result(normal, false)));
        Assert.Equal(0, environment.Runtime.Marks("enemy", 0));
        environment.Runtime.OnEvent(environment.Context, new("normal_landed", Hit: environment.Result(normal, true)));
        Assert.Equal(1, environment.Runtime.Marks("enemy", 0));
        environment.RunUntil(6);
        var followup = Assert.Single(environment.Hits);
        Assert.True(followup.Secondary);
        Assert.Equal(1.2, followup.Components.Single().Ratio, 10);
        environment.Runtime.OnEvent(environment.Context, new("normal_landed", Hit: followup with { Source = "NORMAL_ATTACK" }));
        Assert.Equal(1, environment.Runtime.Marks("enemy", 6));
    }

    [Fact]
    public void StarweaveCancelsAtAnIntermediateRangeCheckEvenIfEnemyReturnsBeforeArrival()
    {
        var environment = new Environment(new Dictionary<string, JsonObject>
        {
            ["archer_starweave_followup"] = Definition("archer_starweave_followup", """{"followupRatio":0.8,"comboBonusRatio":0.4,"range":30,"flightTicks":6}""")
        });
        environment.Runtime.OnEvent(environment.Context, new("normal_landed", Hit: environment.Result(environment.Cast("normal", "{}"), true)));
        environment.Context.EnemyPosition = new(0, 0, 100);
        environment.RunUntil(2);
        environment.Context.EnemyPosition = BalanceVector.Zero;
        environment.RunUntil(6);
        Assert.Empty(environment.Hits);
    }

    [Fact]
    public void ReplacingSentryInvalidatesBothItsFutureShotsAndAlreadyFlyingArrows()
    {
        var environment = new Environment();
        var cast = environment.Cast("phantom_archer_spectral_sentry", """{"placementRange":3,"targetRadius":10,"shotCount":2,"shotIntervalTicks":12}""");
        environment.Runtime.Cast(environment.Context, cast);
        environment.RunUntil(1);
        Assert.Single(environment.Projectiles);
        environment.Runtime.Cast(environment.Context, cast);
        environment.Collide(0);
        Assert.Empty(environment.Hits);
        environment.RunUntil(13);
        Assert.Equal(2, environment.Projectiles.Count);
        environment.Collide(1);
        Assert.Single(environment.Hits);
    }

    [Fact]
    public void RequiredRangedParametersAndUnknownInheritanceCallbacksAreRejected()
    {
        var missing = Definition("sharpshooter_fire_arrow", """{"range":16,"damageRatio":1.15,"projectileSpeed":1.6,"projectileHitRadius":0.6}""");
        Assert.False(BalanceSkillRangedProfiles.Resolve(missing)!.Supported);
        var mastery = Definition("sharpshooter_inheritance_mastery", """{"normalAttackDamageMultiplier":0.5,"inheritedSkillDamageMultiplier":0.5,"inheritanceBuffs":[{"buffId":"buff:unknown","durationConsumptionTicks":600,"sourceSkillId":"skill:new_unverified_skill"}]}""");
        mastery["passive"] = new JsonObject { ["bindRequired"] = true };
        var profile = BalanceSkillRangedProfiles.Resolve(mastery)!;
        Assert.False(profile.Supported);
        Assert.Contains(profile.Reasons, reason => reason.Contains("callback"));
        var malformed = Definition("phantom_archer_wraith_step", """{"phantomDamageRatio":0.6,"readyDurationTicks":120,"modifiers":[]}""");
        malformed["passive"] = new JsonObject { ["bindRequired"] = "yes" };
        Assert.False(BalanceSkillRangedProfiles.Resolve(malformed)!.Supported);
    }

    [Fact]
    public void InheritanceWaitsForEffectiveImpactAndResourceSuccessBeforeConsumingDuration()
    {
        var mastery = Definition("sharpshooter_inheritance_mastery", """{"normalAttackDamageMultiplier":0.5,"inheritedSkillDamageMultiplier":0.5,"inheritanceBuffs":[{"buffId":"buff:thermal","durationConsumptionTicks":600,"sourceSkillId":"skill:sharpshooter_thermal_break"}]}""");
        var environment = new Environment(new Dictionary<string, JsonObject> { ["sharpshooter_inheritance_mastery"] = mastery });
        var source = environment.Cast("sharpshooter_thermal_break", """{"damageRatio":1.2,"statusBonusRatio":0.9}""");
        environment.Runtime.OnEvent(environment.Context, new("cast_completed", source));
        environment.Runtime.OnEvent(environment.Context, new("normal_launch", environment.Cast("normal", "{}")));
        Assert.Equal(.5, environment.Context.NormalDamageMultiplier);
        environment.Context.NormalImpact!(BalanceVector.Zero, environment.Result(source, false));
        Assert.Equal(600, environment.Remaining("thermal"));
        environment.CanSpend = false;
        environment.Context.NormalImpact!(BalanceVector.Zero, environment.Result(source, true));
        Assert.Equal(600, environment.Remaining("thermal"));
        environment.CanSpend = true;
        environment.Context.NormalImpact!(BalanceVector.Zero, environment.Result(source, true));
        Assert.Equal(0, environment.Remaining("thermal"));
        Assert.Equal(1, environment.SpendCount);
        Assert.Equal(.6, Assert.Single(environment.Hits).Components.Sum(component => component.Ratio), 10);
    }

    [Fact]
    public void ExtraNormalAttacksConsumeOnceAtLaunchAndDoNotRescheduleThemselves()
    {
        var mastery = Definition("sharpshooter_inheritance_mastery", """{"normalAttackDamageMultiplier":0.5,"inheritedSkillDamageMultiplier":0.5,"inheritanceBuffs":[{"buffId":"buff:phantom","durationConsumptionTicks":100,"sourceSkillId":"skill:sharpshooter_phantom_shot","consumeSourceSkillResources":false,"normalAttackExtraCount":2,"normalAttackExtraIntervalTicks":5}]}""");
        var environment = new Environment(new Dictionary<string, JsonObject> { ["sharpshooter_inheritance_mastery"] = mastery });
        environment.Runtime.OnEvent(environment.Context, new("cast_completed", environment.Cast("sharpshooter_phantom_shot", "{}")));
        environment.Runtime.OnEvent(environment.Context, new("normal_launch", environment.Cast("normal", "{}")));
        Assert.Equal(500, environment.Remaining("phantom"));
        environment.RunUntil(20);
        Assert.Equal(2, environment.NormalCount);
        Assert.Equal(480, environment.Remaining("phantom"));
        Assert.Equal(0, environment.SpendCount);
    }

    private static void AssertVector(JsonArray expected, BalanceVector actual)
    {
        Assert.Equal(expected[0]!.GetValue<double>(), actual.X, 10);
        Assert.Equal(expected[1]!.GetValue<double>(), actual.Y, 10);
        Assert.Equal(expected[2]!.GetValue<double>(), actual.Z, 10);
    }

    private static JsonObject Definition(string id, string parameters) => new()
    {
        ["id"] = id, ["implementationId"] = id, ["params"] = JsonNode.Parse(parameters)
    };

    private sealed class Environment
    {
        internal BalanceRangedRuntime Runtime { get; } = new();
        internal BalanceRuntimeContext Context { get; }
        internal List<(BalanceRuntimeCast Cast, BalanceRuntimeProjectile Projectile)> Projectiles { get; } = [];
        internal List<BalanceRuntimeHit> Hits { get; } = [];
        internal List<string> Conditions { get; } = [];
        internal List<double> Heals { get; } = [];
        internal List<(string Id, double Amount)> AllyHeals { get; } = [];
        internal bool Landed { get; set; } = true;
        internal bool CanSpend { get; set; } = true;
        internal int SpendCount { get; private set; }
        internal int NormalCount { get; private set; }
        private readonly List<(int Tick, Action Action)> queue = [];
        private readonly Dictionary<string, int> buffUntil = new(StringComparer.Ordinal);
        private readonly Dictionary<string, double> resources = new() { ["HP"] = 100, ["MP"] = 100, ["ENG"] = 100, ["ENEMY_HP"] = 1000 };
        private readonly Dictionary<string, double> stats = new() { ["MAX_HEALTH"] = 100, ["MAX_MANA"] = 100, ["MAX_ENERGY"] = 100 };

        internal Environment(IReadOnlyDictionary<string, JsonObject>? passives = null)
        {
            Context = new()
            {
                Catalog = new(), Build = new(), Request = new(), Random = new(100), Definitions = new Dictionary<string, JsonObject>(),
                Passives = passives ?? new Dictionary<string, JsonObject>(), WeaponTag = "BOW",
                ReadResource = key => resources.GetValueOrDefault(key), WriteResource = (key, amount) => resources[key] = amount,
                ReadStats = _ => stats,
                Queue = (_, delay, action, _) => queue.Add((Context!.Tick + delay, action)),
                Damage = (cast, attack, components, source, _, secondary, _) =>
                {
                    var result = new BalanceRuntimeHit(cast, attack, source, components, components.Sum(value => value.Ratio),
                        Landed ? 1 : 0, 0, !Landed, false, false, secondary);
                    Hits.Add(result); return result;
                },
                Healing = (_, amount) => { Heals.Add(amount); return amount; },
                AllyHealing = (_, ally, amount) => { AllyHeals.Add((ally.Id, amount)); return amount; },
                Condition = (_, condition, _, _, _) => { Conditions.Add(condition); return true; },
                StatEffect = (_, _, _, _, _, _) => { }, MultiplierEffect = (_, _, _, _) => { },
                Buff = (_, id) => { buffUntil[id] = Context!.Tick + 600; return true; },
                RemoveBuff = id => buffUntil.Remove(id), BuffRemaining = Remaining,
                SetBuffRemaining = (id, duration) => buffUntil[id] = Context!.Tick + duration,
                Projectile = (cast, projectile) => Projectiles.Add((cast, projectile)), SetSkillCooldown = (_, _) => { },
                Trace = (_, _, _, _) => { }, Dispatch = value => Runtime.OnEvent(Context!, value),
                MovePlayer = (origin, direction, length) => origin + direction * length,
                Velocity = (_, _) => true, CancelScope = _ => { },
                TryConsumeCastResources = _ => { if (!CanSpend) return false; SpendCount++; return true; },
                NormalAttack = () => { NormalCount++; Runtime.OnEvent(Context!, new("normal_launch", Cast("normal", "{}"))); }
            };
        }

        internal int Remaining(string id) => Math.Max(0, buffUntil.GetValueOrDefault(id) - (Context?.Tick ?? 0));
        internal BalanceRuntimeCast Cast(string id, string parameters) => new(id, Definition(id, parameters), stats);
        internal BalanceRuntimeHit Result(BalanceRuntimeCast cast, bool landed) => new(cast, "RANGED", "NORMAL_ATTACK", [new("NONE", 1)], 1, landed ? 1 : 0, 0, !landed, false, false);
        internal void Collide(int index)
        {
            var projectile = Projectiles[index];
            projectile.Projectile.OnCollision?.Invoke(Context.EnemyCenter);
        }
        internal void RunUntil(int tick)
        {
            while (queue.Any(value => value.Tick <= tick))
            {
                int index = queue.FindIndex(value => value.Tick == queue.Min(entry => entry.Tick));
                var entry = queue[index]; queue.RemoveAt(index); Context.Tick = entry.Tick; entry.Action();
            }
            Context.Tick = tick;
        }
    }

    private static JsonObject Fixture(string id)
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            var path = Path.Combine(directory.FullName, "60_tool", "skilltree-editor", "tests", "plugin-parity", "ranged-runtime-golden.json");
            if (File.Exists(path)) return JsonNode.Parse(File.ReadAllText(path))!["cases"]!.AsArray().OfType<JsonObject>()
                .Single(value => value["id"]!.ToString() == id);
        }
        throw new FileNotFoundException("Pluginのranged runtime固定期待値がありません。");
    }

    private static string RepositoryRoot()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
            if (Directory.Exists(Path.Combine(directory.FullName, "40_filebase", "30.features.skill"))) return directory.FullName;
        throw new DirectoryNotFoundException("スキルマスターのあるリポジトリがありません。");
    }
}
