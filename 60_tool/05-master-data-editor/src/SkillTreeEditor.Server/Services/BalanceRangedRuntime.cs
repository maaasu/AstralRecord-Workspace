using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

// One simulation owns this state. A target is a stable simulation key, never its display name.
internal sealed class BalanceRangedRuntime : IBalanceSkillRuntime
{
    private const string Target = "enemy";
    private const string PhantomPrefix = "phantom_archer_";
    private const int MaximumMarks = 3;
    private const int MarkDurationTicks = 120;
    private readonly Dictionary<string, Mark> marks = new(StringComparer.Ordinal);
    private int readyUntil = -1;
    private int nextStarweaveTick;
    private long sentryGeneration;
    private bool airShiftLocked;
    private bool extraNormalInProgress;
    private readonly Dictionary<string, Inheritance> inheritances = new(StringComparer.Ordinal);

    public bool Handles(string implementationId) => implementationId.StartsWith(PhantomPrefix, StringComparison.Ordinal)
        || implementationId is "archer_starweave_followup" or "adventurer_blast_arrow" or "hunter_fade_shot"
            or "hunter_arrow_rain" or "hunter_crash_arrow" or "hunter_heal_arrow" or "archer_heal_arrow_alpha"
            or "sharpshooter_glacial_trap" or "hunter_spell_step" or "archer_air_shift" or "sharpshooter_inheritance_mastery"
            or "sharpshooter_fire_arrow" or "sharpshooter_ice_arrow" or "sharpshooter_piercing_line"
            or "sharpshooter_ricochet_arrow" or "sharpshooter_thermal_break" or "sharpshooter_spreading_ambition"
            or "sharpshooter_phantom_shot";

    public BalanceRuntimeCastResult Cast(BalanceRuntimeContext context, BalanceRuntimeCast cast)
    {
        if (cast.ImplementationId.StartsWith(PhantomPrefix, StringComparison.Ordinal) && !context.HasBow)
            return BalanceRuntimeCastResult.Failed;
        var p = cast.Params;
        switch (cast.ImplementationId)
        {
            case "adventurer_blast_arrow":
                ContactProjectile(context, cast, context.Eye, context.Facing, impact => Blast(context, cast, impact, 1));
                break;
            case "hunter_fade_shot":
                foreach (var direction in PelletDirections(context.Facing, I(p, "pelletCount", 5), N(p, "spreadAngle", 30)))
                    ContactProjectile(context, cast, context.Eye, direction,
                        _ => context.Hit(cast, N(p, "damageRatio", .384), "RANGED"), false);
                context.Velocity("player", context.Facing.Horizontal.Normalized * -N(p, "backstepVelocity", .35));
                break;
            case "hunter_crash_arrow":
                ContactProjectile(context, cast, context.Eye, context.Facing, _ => Crash(context, cast, 1), false);
                break;
            case "hunter_arrow_rain":
                OpeningRain(context, cast);
                break;
            case "hunter_heal_arrow":
            case "archer_heal_arrow_alpha":
                if (cast.ImplementationId == "archer_heal_arrow_alpha" && !context.ApplyBuff(cast, "archer_heal_arrow_alpha"))
                    return BalanceRuntimeCastResult.Failed;
                context.Launch(cast, new(48, N(p, "projectileSpeed", 1.25), N(p, "projectileHitRadius", .45),
                    context.Eye, context.Facing, "RANGED", [], Gravity: .14, MaxTicks: 80, Piercing: true, Interceptable: false,
                    OnCollision: impact =>
                    {
                        context.Hit(cast, N(p, "damageRatio", .36), "RANGED");
                        HealingArea(context, cast, impact);
                    }, OnEnd: (impact, reason) => { if (reason == "BLOCK") HealingArea(context, cast, impact); }));
                break;
            case "sharpshooter_glacial_trap":
                ArmTrap(context, cast, context.GroundTarget(N(p, "range", 16)), 1);
                break;
            case "sharpshooter_fire_arrow":
            case "sharpshooter_ice_arrow":
                bool fire = cast.ImplementationId == "sharpshooter_fire_arrow";
                ContactProjectile(context, cast, context.Eye, context.Facing, _ =>
                {
                    if (context.Hit(cast, N(p, "damageRatio", 1.15), "RANGED", fire ? "FIRE" : "ICE").Landed)
                        context.ApplyCondition(cast with { ConditionAttackType = "RANGED" }, fire ? "BURNING" : "FROZEN", N(p, "conditionChance", fire ? 24.5 : 50),
                            I(p, "conditionDurationTicks", fire ? 100 : 40));
                }, false);
                break;
            case "sharpshooter_piercing_line":
            case "sharpshooter_ricochet_arrow":
                ContactProjectile(context, cast, context.Eye, context.Facing, _ => context.Hit(cast, N(p, "damageRatio", 1), "RANGED"), false);
                break;
            case "sharpshooter_thermal_break":
                ContactProjectile(context, cast, context.Eye, context.Facing, _ => Thermal(context, cast, 1), false);
                break;
            case "sharpshooter_spreading_ambition":
                foreach (var direction in PelletDirections(context.Facing, I(p, "pelletCount", 1), N(p, "spreadAngle", 20)))
                    context.Launch(cast, new(N(p, "range", 24), N(p, "projectileSpeed", 1.6), N(p, "projectileHitRadius", .6),
                        context.Eye + context.Facing.Normalized * .8, direction, "RANGED", [], Interceptable: false,
                        OnCollision: _ => context.Hit(cast, N(p, "damageRatio", 2) * (context.EnemyConditions.Count > 0 ? 2 : 1), "RANGED"),
                        OnTermination: (_, impact, reason) =>
                        {
                            if (reason == "BLOCK" && context.InSphere(impact, N(p, "explosionRadius", 3)))
                                context.Hit(cast, N(p, "explosionDamageRatio", 1) * (context.EnemyConditions.Count > 0 ? 2 : 1), "RANGED");
                        }));
                break;
            case "sharpshooter_phantom_shot":
                int lastHitTick = -1;
                void Shot()
                {
                    ContactProjectile(context, cast, context.Eye, context.Facing, _ =>
                    {
                        if (!context.Hit(cast, N(p, "damageRatio", 1.7), "RANGED").Landed) return;
                        if (lastHitTick >= 0 && context.Tick - lastHitTick <= I(p, "successiveHitWindowTicks", 40))
                        {
                            context.RecoverResource("MP", context.Stat("MAX_MANA") * N(p, "resourceRecoveryRatio", 0));
                            context.RecoverResource("ENG", context.Stat("MAX_ENERGY") * N(p, "resourceRecoveryRatio", 0));
                        }
                        lastHitTick = context.Tick;
                    }, false);
                }
                Shot();
                context.Schedule(cast, I(p, "shotIntervalTicks", 5), Shot);
                context.Schedule(cast, 2 * I(p, "shotIntervalTicks", 5), Shot);
                if (HasInheritance(context))
                    return new(true, (int)BalanceCombatMath.CooldownTicks(2 * BalanceCombatData.Integer(cast.Definition["cooldownTicks"]), cast.Stat("COOLDOWN_REDUCTION")));
                break;
            case "phantom_archer_shadow_stitch":
            case "phantom_archer_soul_pierce":
            case "phantom_archer_dread_bloom":
                ShootPhantom(context, cast, context.Eye, context.Facing);
                break;
            case "phantom_archer_eclipse_shot":
                context.Schedule(cast, I(p, "chargeTicks", 16), () => ShootPhantom(context, cast, context.Eye, context.Facing));
                break;
            case "phantom_archer_echo_volley":
                for (int shot = 0; shot < I(p, "shotCount", 4); shot++)
                    context.Schedule(cast, 1 + shot * I(p, "shotIntervalTicks", 6),
                        () => ShootPhantom(context, cast, context.Eye, context.Facing));
                break;
            case "phantom_archer_spectral_sentry":
                PlaceSentry(context, cast);
                break;
            case "phantom_archer_mist_retreat":
                if (!context.Velocity("player", context.Facing.Horizontal.Normalized * -N(p, "backstepVelocity", 1.4))) break;
                context.Multiply(cast.Id, "EVASION", N(p, "evasionMultiplier", 1.4), I(p, "durationTicks", 60));
                if (context.Passive("phantom_archer_wraith_step") is { } wraith)
                    ReadyArrow(context.Tick, I(BalanceSkillParameters.Params(wraith), "readyDurationTicks", 120));
                break;
            default:
                return BalanceRuntimeCastResult.Failed;
        }
        return BalanceRuntimeCastResult.Succeeded;
    }

    public void OnEvent(BalanceRuntimeContext context, BalanceRuntimeEvent value)
    {
        if (value.Kind == "cast_completed" && value.Cast is { } completed
            && context.HasPassive("hunter_spell_step") && completed.Tagged("ranged"))
            context.State["spell_step_free_until"] = context.Tick + 20;
        if (value.Kind == "cast_completed" && value.Cast is { } inheritedSource) GrantInheritance(context, inheritedSource);
        if (value.Kind == "normal_launch" && value.Cast is { } normal) PrepareInheritance(context, normal);
        if (value.Kind == "tick" && context.OnGround) airShiftLocked = false;
        if (value.Kind == "input" && BalanceCombatData.Text(value.Input?["kind"]) == "air_shift"
            && !context.OnGround && !airShiftLocked && context.Passive("archer_air_shift") is { } airShift)
        {
            var cast = context.Snapshot(airShift);
            if (context.TryConsumeCastResources?.Invoke(cast) == true)
            {
                var forward = context.Facing.Horizontal.Normalized;
                var right = new BalanceVector(-forward.Z, 0, forward.X);
                var input = value.Input!;
                var direction = forward * ((BalanceCombatData.Boolean(input["forward"]) ? 1 : 0) - (BalanceCombatData.Boolean(input["backward"]) ? 1 : 0))
                    + right * ((BalanceCombatData.Boolean(input["right"]) ? 1 : 0) - (BalanceCombatData.Boolean(input["left"]) ? 1 : 0));
                if (direction.LengthSquared < 1e-6) direction = forward * -1;
                var velocity = direction.Normalized * 1.45 + new BalanceVector(0, .25, 0);
                context.Velocity("player", velocity);
                airShiftLocked = true;
                context.Dispatch(new("dodge", cast));
            }
        }
        if (context.HasBow && value.Kind == "normal_landed" && value.Hit is { Landed: true, AttackType: "RANGED", Source: "NORMAL_ATTACK", Secondary: false })
        {
            if (context.HasPassive("phantom_archer_spectral_attunement")) AddMark(Target, context.Tick);
            if (context.Passive("archer_starweave_followup") is { } definition)
            {
                var cast = context.Snapshot(definition);
                var p = cast.Params;
                if ((context.EnemyCenter - context.Eye).Length <= N(p, "range", 30)
                    && PrepareStarweave(Target, context.Tick, BalanceCombatData.Integer(definition["cooldownTicks"]),
                        N(p, "followupRatio", .8), N(p, "comboBonusRatio", .4), HasInheritance(context)) is { } ratio)
                    StarweaveFlight(context, cast, ratio, N(p, "range", 30), I(p, "flightTicks", 6));
            }
        }
        if (value.Kind == "death") ClearTransientState();
        if (marks.Count > 0 || context.CustomResources.ContainsKey("SPIRIT_MARK")) context.SetResource("SPIRIT_MARK", Marks(Target, context.Tick));
        if (context.HasPassive("phantom_archer_wraith_step")) context.SetResource("WRAITH_READY", readyUntil > context.Tick ? 1 : 0);
    }

    private static void StarweaveFlight(BalanceRuntimeContext context, BalanceRuntimeCast cast, double ratio, double range, int ticks)
    {
        var delta = context.EnemyCenter - context.Eye;
        var origin = delta.LengthSquared > 1e-8 ? context.Eye + delta.Normalized * .35 : context.Eye;
        var previous = origin;
        bool active = true;
        for (int step = 1; step <= ticks / 2; step++)
        {
            int frame = step;
            context.Schedule(cast, step * 2, () =>
            {
                if (!active) return;
                if (!context.HasBow || !context.EnemyAlive || (context.EnemyCenter - context.Eye).Length > range)
                { active = false; return; }
                var next = origin + (context.EnemyCenter - origin) * (frame / (ticks / 2d));
                if (Math.Min(previous.Y, next.Y) < context.FloorY) { active = false; return; }
                previous = next;
                if (frame == ticks / 2) context.Hit(cast, ratio, "RANGED", secondary: true);
            });
        }
    }

    private bool HasInheritance(BalanceRuntimeContext context) => inheritances.Values.Any(value => ActiveInheritance(context, value));

    private bool ActiveInheritance(BalanceRuntimeContext context, Inheritance value)
        => inheritances.TryGetValue(value.BuffId, out var current) && ReferenceEquals(value, current)
            && (context.BuffRemaining?.Invoke(value.BuffId) ?? value.Until - context.Tick) > 0;

    private void GrantInheritance(BalanceRuntimeContext context, BalanceRuntimeCast source)
    {
        if (context.Passive("sharpshooter_inheritance_mastery") is not { } mastery) return;
        var rows = BalanceCombatData.Array(BalanceSkillParameters.Params(mastery)["inheritanceBuffs"]).OfType<JsonObject>().ToArray();
        for (int order = 0; order < rows.Length; order++)
        {
            var row = rows[order];
            if (BalanceCombatData.Id(row["sourceSkillId"]) != source.Id) continue;
            string buffId = BalanceCombatData.Id(row["buffId"]);
            if (!context.ApplyBuff(source, buffId)) continue;
            var buff = BalanceCombatData.Find(BalanceCombatData.Masters(context.Catalog), "buff", buffId)?.Content;
            int duration = context.BuffRemaining?.Invoke(buffId) ?? BalanceCombatData.Integer(buff?["durationTicks"]);
            inheritances[buffId] = new(source, buffId, row, order, context.Tick + duration);
        }
    }

    private void PrepareInheritance(BalanceRuntimeContext context, BalanceRuntimeCast normal)
    {
        if (context.Passive("sharpshooter_inheritance_mastery") is not { } mastery) return;
        var parameters = BalanceSkillParameters.Params(mastery);
        context.NormalDamageMultiplier *= N(parameters, "normalAttackDamageMultiplier", .5);
        var captured = inheritances.Values.Where(value => ActiveInheritance(context, value)).OrderBy(value => value.Order).ToArray();
        foreach (var value in captured)
        {
            if (value.Cast.ImplementationId == "sharpshooter_fire_arrow") context.NormalAdditiveElements.Add("FIRE");
            if (value.Cast.ImplementationId == "sharpshooter_ice_arrow") context.NormalAdditiveElements.Add("ICE");
            if (value.Cast.ImplementationId == "sharpshooter_spreading_ambition" && context.HasBow)
            {
                context.NormalDirections.Clear();
                context.NormalDirections.AddRange(PelletDirections(context.Facing, 5, 20));
            }
        }
        if (extraNormalInProgress) return;
        var onImpact = new List<Inheritance>();
        foreach (var value in captured)
        {
            int extraCount = BalanceCombatData.Integer(value.Config["normalAttackExtraCount"]);
            if (extraCount == 0) { onImpact.Add(value); continue; }
            if (!ConsumeInheritance(context, value, false)) continue;
            for (int index = 1; index <= extraCount; index++)
                context.Schedule(normal, index * BalanceCombatData.Integer(value.Config["normalAttackExtraIntervalTicks"]), () =>
                {
                    bool previous = extraNormalInProgress;
                    extraNormalInProgress = true;
                    try { context.NormalAttack?.Invoke(); }
                    finally { extraNormalInProgress = previous; }
                });
        }
        var previousImpact = context.NormalImpact;
        context.NormalImpact = (impact, hit) =>
        {
            previousImpact?.Invoke(impact, hit);
            foreach (var value in onImpact)
            {
                if (!ActiveInheritance(context, value)) continue;
                string id = value.Cast.ImplementationId;
                bool requireEffective = id is "sharpshooter_fire_arrow" or "sharpshooter_ice_arrow"
                    or "sharpshooter_piercing_line" or "sharpshooter_thermal_break" or "sharpshooter_ricochet_arrow" or "sharpshooter_glacial_trap";
                if (requireEffective && hit is not { Landed: true }) continue;
                if (id == "hunter_crash_arrow" && (hit is null || !context.EnemyAlive)) continue;
                bool spend = !value.Config.ContainsKey("consumeSourceSkillResources") || BalanceCombatData.Boolean(value.Config["consumeSourceSkillResources"]);
                if (!ConsumeInheritance(context, value, spend)) continue;
                InheritedEffect(context, value.Cast, impact, N(parameters, "inheritedSkillDamageMultiplier", .5), hit);
            }
        };
    }

    private bool ConsumeInheritance(BalanceRuntimeContext context, Inheritance value, bool spend)
    {
        int remaining = context.BuffRemaining?.Invoke(value.BuffId) ?? value.Until - context.Tick;
        int consumption = BalanceCombatData.Integer(value.Config["durationConsumptionTicks"]);
        if (remaining <= 0 || !ActiveInheritance(context, value) || (spend && context.TryConsumeCastResources?.Invoke(value.Cast) != true)) return false;
        int shortened = Math.Max(0, remaining - consumption);
        value.Until = context.Tick + shortened;
        context.SetBuffRemaining?.Invoke(value.BuffId, shortened);
        if (shortened == 0)
        {
            inheritances.Remove(value.BuffId);
            context.RemoveBuff(value.BuffId);
        }
        return true;
    }

    private static void InheritedEffect(BalanceRuntimeContext context, BalanceRuntimeCast source,
        BalanceVector impact, double multiplier, BalanceRuntimeHit? hit)
    {
        var p = source.Params;
        switch (source.ImplementationId)
        {
            case "hunter_arrow_rain": BeginRain(context, source, impact, multiplier); break;
            case "adventurer_blast_arrow": Blast(context, source, impact, multiplier); break;
            case "hunter_crash_arrow": Crash(context, source, multiplier); break;
            case "sharpshooter_fire_arrow":
            case "sharpshooter_ice_arrow":
                context.ApplyCondition(source with { ConditionAttackType = "RANGED" }, source.ImplementationId == "sharpshooter_fire_arrow" ? "BURNING" : "FROZEN",
                    N(p, "inheritedConditionChance", source.ImplementationId == "sharpshooter_fire_arrow" ? 25 : 50),
                    I(p, "conditionDurationTicks", source.ImplementationId == "sharpshooter_fire_arrow" ? 100 : 40));
                break;
            case "sharpshooter_piercing_line":
            case "sharpshooter_ricochet_arrow":
                if (context.EnemyAlive) context.Hit(source, N(p, "damageRatio", 1) * multiplier, "RANGED");
                break;
            case "sharpshooter_thermal_break":
                if (context.EnemyAlive) Thermal(context, source, multiplier);
                break;
            case "sharpshooter_glacial_trap": ArmTrap(context, source, impact, multiplier); break;
        }
    }

    private static void Thermal(BalanceRuntimeContext context, BalanceRuntimeCast cast, double multiplier)
    {
        double ratio = (N(cast.Params, "damageRatio", 1) +
            (context.EnemyConditions.Contains("BURNING") || context.EnemyConditions.Contains("FROZEN")
                ? N(cast.Params, "statusBonusRatio", 0) : 0)) * multiplier;
        context.Hit(cast, [new("FIRE", ratio * .5), new("ICE", ratio * .5)], "RANGED");
    }

    private sealed class Inheritance(BalanceRuntimeCast cast, string buffId, JsonObject config, int order, int until)
    {
        internal BalanceRuntimeCast Cast { get; } = cast;
        internal string BuffId { get; } = buffId;
        internal JsonObject Config { get; } = config;
        internal int Order { get; } = order;
        internal int Until { get; set; } = until;
    }

    private static void ContactProjectile(BalanceRuntimeContext context, BalanceRuntimeCast cast, BalanceVector origin,
        BalanceVector direction, Action<BalanceVector> impact, bool blockImpact = true)
    {
        var p = cast.Params;
        context.Launch(cast, new(N(p, "range", 14), N(p, "projectileSpeed", 1.35), N(p, "projectileHitRadius", .45),
            origin, direction, "RANGED", [], Interceptable: false, OnCollision: impact,
            OnTermination: (_, point, reason) => { if (blockImpact && reason == "BLOCK") impact(point); }));
    }

    private static void Blast(BalanceRuntimeContext context, BalanceRuntimeCast cast, BalanceVector impact, double multiplier)
    {
        if (context.InSphere(impact, N(cast.Params, "radius", 2.25)))
            context.Hit(cast, N(cast.Params, "damageRatio", 1.44) * multiplier, "RANGED");
    }

    private static void Crash(BalanceRuntimeContext context, BalanceRuntimeCast cast, double multiplier)
        => context.Hit(cast with { SuperCriticalChance = N(cast.Params, "superStarCriticalChance", 50) },
            N(cast.Params, "damageRatio", .45) * multiplier, "RANGED", shieldMultiplier: N(cast.Params, "shieldBreakMultiplier", 3));

    private static void ArmTrap(BalanceRuntimeContext context, BalanceRuntimeCast cast, BalanceVector center, double multiplier)
        => context.Schedule(cast, 12, () =>
        {
            if (!context.InSphere(center, N(cast.Params, "radius", 3))) return;
            var hit = context.Hit(cast, N(cast.Params, "damageRatio", .9) * multiplier, "RANGED", "ICE");
            if (hit.Landed) context.ApplyCondition(cast with { ConditionAttackType = "RANGED" }, "FROZEN", N(cast.Params, "conditionChance", 0), I(cast.Params, "conditionTicks", 1));
        });

    private static void HealingArea(BalanceRuntimeContext context, BalanceRuntimeCast cast, BalanceVector center)
    {
        bool healed = false;
        var healedAllies = new HashSet<string>(StringComparer.Ordinal);
        double radius = N(cast.Params, "radius", 2);
        for (int tick = 0; tick < I(cast.Params, "areaDurationTicks", 60); tick++)
            context.Schedule(cast, tick, () =>
            {
                var delta = context.PlayerPosition - center;
                if (!healed && delta.Horizontal.LengthSquared <= radius * radius && Math.Abs(delta.Y) <= radius)
                {
                    healed = true;
                    double amount = cast.ImplementationId == "archer_heal_arrow_alpha"
                        ? context.Stat("MAX_HEALTH") * N(cast.Params, "healPercent", 0) / 100 : N(cast.Params, "healAmount", 12);
                    context.Heal(cast, amount);
                }
                foreach (var ally in context.Allies)
                {
                    var allyDelta = ally.Position - center;
                    if (ally.Dead || healedAllies.Contains(ally.Id) || allyDelta.Horizontal.LengthSquared > radius * radius || Math.Abs(allyDelta.Y) > radius) continue;
                    healedAllies.Add(ally.Id);
                    double amount = cast.ImplementationId == "archer_heal_arrow_alpha"
                        ? ally.Stats.GetValueOrDefault("MAX_HEALTH") * N(cast.Params, "healPercent", 0) / 100 : N(cast.Params, "healAmount", 12);
                    context.HealAlly(cast, ally, amount);
                }
            });
    }

    private static void OpeningRain(BalanceRuntimeContext context, BalanceRuntimeCast cast)
    {
        var p = cast.Params;
        var ratios = BalanceSkillParameters.Numbers(p, "damageRatios");
        context.Launch(cast with { ShieldBreakRatio = .1 }, new(N(p, "range", 18), N(p, "openingSpeed", 1.6), N(p, "openingHitRadius", .45),
            context.Eye, context.Facing, "RANGED", [new("NONE", ratios[0])], Gravity: .05, MaxTicks: 24, Interceptable: false,
            OnTermination: (_, impact, reason) => { if (reason is "ENTITY" or "BLOCK") BeginRain(context, cast, impact, 1); }));
    }

    private static void BeginRain(BalanceRuntimeContext context, BalanceRuntimeCast cast, BalanceVector center, double multiplier)
    {
        var p = cast.Params;
        double ratio = BalanceSkillParameters.Numbers(p, "damageRatios")[1] * multiplier;
        // The Plugin precomputes every random trajectory before scheduling its three arrows per tick.
        var trajectories = new List<(BalanceVector Origin, BalanceVector Velocity, int Ticks, double Length)>();
        for (int index = 0; index < I(p, "arrowCount", 45); index++)
        {
            var origin = context.Eye + new BalanceVector(0, 5, 0) + RandomDisk(context.Random, 1.25)
                + new BalanceVector(0, -.45 + context.Random.NextDouble() * .91, 0);
            var target = center + RandomDisk(context.Random, N(p, "radius", 3));
            if (context.FloorY >= Math.Floor(target.Y) - 32 && context.FloorY <= Math.Floor(target.Y) + 6)
                target = target with { Y = context.FloorY + .05 };
            int ticks = context.Random.Next(10, 15);
            var velocity = BallisticVelocity(origin, target, ticks, .14);
            trajectories.Add((origin, velocity, ticks, BallisticPathLength(velocity, ticks, .14) + .25));
        }
        for (int index = 0; index < trajectories.Count; index++)
        {
            var path = trajectories[index];
            context.Schedule(cast, index / 3, () => context.Launch(cast with { ShieldBreakRatio = .1 },
                new(path.Length, path.Velocity.Length, N(p, "rainHitRadius", .75), path.Origin, path.Velocity.Normalized,
                    "RANGED", [new("NONE", ratio)], Gravity: .14, MaxTicks: path.Ticks, Interceptable: false)));
        }
    }

    private static BalanceVector RandomDisk(Random random, double radius)
    {
        double distance = radius * Math.Sqrt(random.NextDouble()), angle = random.NextDouble() * Math.PI * 2;
        return new(Math.Cos(angle) * distance, 0, Math.Sin(angle) * distance);
    }

    internal static IReadOnlyList<BalanceVector> PelletDirections(BalanceVector forward, int count, double spread)
    {
        forward = forward.Normalized;
        var cross = new BalanceVector(-forward.Z, 0, forward.X);
        var right = cross.LengthSquared <= 1e-8 ? new BalanceVector(1, 0, 0) : cross.Normalized;
        var result = new List<BalanceVector>();
        for (int index = 0; index < count; index++)
        {
            double angle = (-spread / 2 + spread * (count == 1 ? .5 : (double)index / (count - 1))) * Math.PI / 180;
            result.Add((forward * Math.Cos(angle) + right * Math.Sin(angle)).Normalized);
        }
        return result;
    }

    internal static BalanceVector BallisticVelocity(BalanceVector origin, BalanceVector target, int ticks, double gravity)
        => (target - origin + new BalanceVector(0, gravity * ticks * (ticks - 1) / 2, 0)) / ticks;

    internal static double BallisticPathLength(BalanceVector velocity, int ticks, double gravity)
    {
        double length = 0;
        for (int frame = 0; frame < ticks; frame++) length += (velocity + new BalanceVector(0, -gravity * frame, 0)).Length;
        return length;
    }

    private void ShootPhantom(BalanceRuntimeContext context, BalanceRuntimeCast cast, BalanceVector origin,
        BalanceVector direction, bool sentry = false, Func<bool>? active = null)
    {
        var p = cast.Params;
        bool area = cast.ImplementationId == "phantom_archer_dread_bloom";
        bool consume = cast.ImplementationId is "phantom_archer_soul_pierce" or "phantom_archer_eclipse_shot";
        void Collision(BalanceVector impact)
            {
                if (active?.Invoke() == false || !context.HasBow) return;
                if (area && !context.InSphere(impact, N(p, "impactRadius", 3))) return;
                double curse = context.Passive("phantom_archer_curse_weaving") is { } definition
                    ? N(BalanceSkillParameters.Params(definition), "markBonusRatio", 0) : 0;
                double ratio = consume
                    ? MarkDamageRatio(Target, context.Tick, N(p, "damageRatio", 1), N(p, "markDamageRatio", 0),
                        cast.ImplementationId == "phantom_archer_soul_pierce", curse,
                        context.EnemyConditions.Contains("WEAKNESS") || context.EnemyConditions.Contains("BLINDNESS"),
                        N(p, "conditionBonusRatio", 0))
                    : N(p, "damageRatio", sentry ? .8 : 1);
                var hit = context.Hit(cast, ratio, "RANGED");
                if (!hit.Landed) return;
                if (cast.ImplementationId is "phantom_archer_shadow_stitch" or "phantom_archer_dread_bloom")
                    context.ApplyCondition(cast with { ConditionAttackType = "RANGED" }, area ? "BLINDNESS" : "WEAKNESS", N(p, "conditionChance", 0), I(p, "conditionTicks", 1));
                if (consume) ConsumeMarks(Target);
                else AddMark(Target, context.Tick);
            }
        context.Launch(cast, new(N(p, sentry ? "targetRadius" : "range", sentry ? 10 : 16),
            N(p, "projectileSpeed", 1.8), N(p, "projectileHitRadius", .4), origin, direction,
            "RANGED", [], Piercing: consume || cast.ImplementationId == "phantom_archer_echo_volley", Interceptable: false,
            OnCollision: Collision, OnEnd: (impact, reason) => { if (area && reason == "BLOCK") Collision(impact); }));
        if (!area && !sentry && cast.ImplementationId != "phantom_archer_echo_volley"
            && TakeArrow(context.Tick, true, context.HasPassive("phantom_archer_wraith_step")))
        {
            var wraith = BalanceSkillParameters.Params(context.Passive("phantom_archer_wraith_step")!);
            var cross = new BalanceVector(-direction.Z, 0, direction.X);
            var right = cross.LengthSquared <= 1e-8 ? new BalanceVector(1, 0, 0) : cross.Normalized;
            context.Launch(cast, new(N(p, "range", 16), N(p, "projectileSpeed", 1.8), N(p, "projectileHitRadius", .4),
                origin + right * .35, direction, "RANGED", [], Interceptable: false,
                OnCollision: _ => context.Hit(cast, N(p, "damageRatio", 1) * N(wraith, "phantomDamageRatio", 0), "RANGED", secondary: true)));
        }
    }

    private void PlaceSentry(BalanceRuntimeContext context, BalanceRuntimeCast cast)
    {
        var p = cast.Params;
        long generation = ++sentryGeneration;
        var center = context.GroundTarget(N(p, "placementRange", 12)) + new BalanceVector(0, 1.4, 0);
        if ((center - context.Eye).Length > N(p, "placementRange", 12))
            center = context.Eye + context.Facing.Normalized * N(p, "placementRange", 12);
        for (int shot = 0; shot < I(p, "shotCount", 8); shot++)
            context.Schedule(cast, 1 + shot * I(p, "shotIntervalTicks", 12), () =>
            {
                if (generation == sentryGeneration && context.EnemyAlive && context.InSphere(center, N(p, "targetRadius", 10))
                    && (context.EnemyCenter - center).LengthSquared > 1e-8)
                    ShootPhantom(context, cast, center, (context.EnemyCenter - center).Normalized, true, () => generation == sentryGeneration);
            });
    }

    private static double N(JsonObject p, string key, double fallback) => BalanceSkillParameters.Number(p, key, fallback);
    private static int I(JsonObject p, string key, int fallback) => BalanceSkillParameters.Integer(p, key, fallback);

    internal int Marks(string target, int tick)
    {
        if (!marks.TryGetValue(target, out var mark)) return 0;
        if (mark.ExpiresAt > tick) return mark.Stacks;
        marks.Remove(target);
        return 0;
    }

    internal void AddMark(string target, int tick)
        => marks[target] = new(Math.Min(MaximumMarks, Marks(target, tick) + 1), tick + MarkDurationTicks);

    internal void ConsumeMarks(string target) => marks.Remove(target);

    internal void ReadyArrow(int tick, int durationTicks) => readyUntil = tick + durationTicks;

    internal bool TakeArrow(int tick, bool hasBow, bool wraithActive)
    {
        int expires = readyUntil;
        readyUntil = -1;
        return expires > tick && hasBow && wraithActive;
    }

    internal double MarkDamageRatio(string target, int tick, double ratio, double perMark,
        bool soulPierce, double curseBonus, bool hasWeaknessOrBlindness, double conditionBonus)
    {
        int stacks = Marks(target, tick);
        if (soulPierce && stacks > 0) perMark += curseBonus;
        ratio += stacks * perMark;
        return soulPierce && hasWeaknessOrBlindness ? ratio * (1 + conditionBonus) : ratio;
    }

    internal double? PrepareStarweave(string target, int tick, int cooldownTicks,
        double ratio, double comboBonus, bool inheritanceActive)
    {
        if (tick < nextStarweaveTick) return null;
        nextStarweaveTick = tick + cooldownTicks;
        return ratio + (Marks(target, tick) > 0 || inheritanceActive ? comboBonus : 0);
    }

    internal void ClearTransientState()
    {
        marks.Clear();
        readyUntil = -1;
        sentryGeneration++;
        inheritances.Clear();
        airShiftLocked = false;
        // Starweave's cooldown survives death, weapon changes and cancelled flights.
    }

    private sealed record Mark(int Stacks, int ExpiresAt);
}
