using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

// Stateful melee adapters are kept separate from the shared damage and resource pipeline.
internal sealed class BalanceMeleeRuntime : IBalanceSkillRuntime
{
    private const string SkillRoot = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/";
    private const string Seijaku = "swordmaster_seijaku_issen";
    private const string Guard = "paladin_guard_convert";
    private const string Crest = "melee_dual_crest";
    private static readonly IReadOnlyDictionary<string, Descriptor> Descriptors = CreateDescriptors();

    private sealed record Descriptor(string File, string[] Keys, bool Passive = false, string[]? Requirements = null);
    private sealed class State
    {
        internal bool Bound;
        internal int CounterUntil, DodgeUntil, BastionReady, CrestCount, CrestExpiry, CrestNextHit, CrestReady;
        internal bool DodgeRecovered, CrestPending;
        internal int LastGuardGain, LastGuardDecay;
        internal double GuardDecay;
        internal bool GuardDecaying;
        internal int RechargeAt = -1;
        internal bool RechargeIncremental;
        internal Field? HolyField;
        internal readonly List<Pillar> Pillars = [];
        internal Flight? Stamp;
        internal long Serial;
    }
    private sealed record Field(BalanceRuntimeCast Cast, BalanceVector Center, int Until, int Duration, double Radius);
    private sealed class Pillar(BalanceRuntimeCast cast, BalanceVector center, int duration, double radius, double ratio, int interval, bool weakness)
    {
        internal BalanceRuntimeCast Cast = cast;
        internal BalanceVector Center = center;
        internal int Created, Until, Duration = duration, Interval = interval, NextImpact;
        internal double Radius = radius, Ratio = ratio;
        internal bool Weakness = weakness;
        internal BalanceRuntimeCast? Control;
        internal int NextControl;
        internal string Key = "";
    }
    private sealed class Flight(BalanceRuntimeCast cast, bool sneaking, int until)
    {
        internal BalanceRuntimeCast Cast = cast;
        internal bool LeftGround, Diving, PreviousSneaking = sneaking;
        internal int Until = until;
    }

    public bool Handles(string implementationId) => Descriptors.ContainsKey(implementationId);

    internal static BalanceSkillProfile? Resolve(JsonObject definition)
    {
        var id = BalanceCombatData.Text(definition["implementationId"]);
        if (!Descriptors.TryGetValue(id, out var descriptor)) return null;
        var reasons = Validate(definition, descriptor);
        var sources = new List<string> { SkillRoot + descriptor.File };
        if (id.StartsWith("swordmaster_", StringComparison.Ordinal))
            sources.Add(SkillRoot + "executor/active/swordmaster/SwordmasterSkillSupport.java");
        if (id.StartsWith("paladin_", StringComparison.Ordinal))
            sources.Add(SkillRoot + "executor/active/paladin/PaladinBuildSkillSupport.java");
        var runtime = id switch
        {
            Seijaku or "swordmaster_riposte" or "swordmaster_revenge_accel" or "swordmaster_serene_mend" => "service/SeijakuIssenSkillRuntimeService.java",
            Crest => "service/MeleeDualCrestRuntimeService.java",
            "swordsman_bastion_strike" => "service/BastionStrikeSkillRuntimeService.java",
            "administrator_just_dodge" => "service/JustDodgeSkillRuntimeService.java",
            Guard => "executor/active/paladin/PaladinGuardRuntimeService.java",
            "paladin_holy_field" or "paladin_holy_smash" or "paladin_blessed_lance" or "paladin_holy_reprieve" => "executor/active/paladin/PaladinHolyFieldRuntimeService.java",
            "paladin_holy_smite" or "paladin_holy_control" or "paladin_shield" => "executor/active/paladin/PaladinHolySmiteRuntimeService.java",
            "paladin_divine_chaser" => "executor/active/paladin/PaladinDivineChaserRuntimeService.java",
            "paladin_guardian_protect" => "executor/active/paladin/PaladinGuardianProtectRuntimeService.java",
            _ => ""
        };
        if (runtime.Length > 0) sources.Add(SkillRoot + runtime);
        if (id is "administrator_shield_recharge" or "swordsman_shield_activate")
            sources.Add("10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/service/StatusService.java");
        return new(id, [], sources.Distinct().ToArray(), descriptor.Keys,
            ["単一敵・同一world・遮蔽物のない平面で、実行イベントと現在位置を使います。未入力の構え・回避・着地は自動で成功させません。"],
            reasons, Runtime: true, Passive: descriptor.Passive, Requirements: descriptor.Requirements);
    }

    public BalanceRuntimeCastResult Cast(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        var s = Memory(c);
        double N(string key, double fallback = 0) => Number(cast, key, fallback);
        int I(string key, int fallback = 0) => Integer(cast, key, fallback);
        BalanceRuntimeHit Hit(double ratio, string element = "NONE") => c.Hit(cast, ratio, "MELEE", element);
        bool Cone() => c.InCone(N("range"), N(cast.Params.ContainsKey("angle") ? "angle" : "targetAngle"));
        switch (cast.ImplementationId)
        {
            case "swordmaster_riposte":
            case "swordmaster_revenge_accel":
                var empowered = c.Spend("RIPOSTE", 1);
                if (Cone())
                {
                    var hit = Hit(N("damageRatio") * (empowered ? N("riposteMultiplier") : 1));
                    if (empowered && hit.Landed && cast.ImplementationId == "swordmaster_revenge_accel")
                    {
                        c.SetSkillCooldown("swordmaster_gale_reaper", 0);
                        c.SetSkillCooldown("swordmaster_crimson_drive", 0);
                    }
                }
                break;
            case "swordmaster_serene_mend":
                var hasSpirit = c.Resource("RIPOSTE") > 0;
                c.Heal(cast, cast.Stat("MAX_HEALTH") * (N("healHpRatio") + (hasSpirit ? N("riposteHealHpRatio") : 0)));
                if (hasSpirit) Recover(c, "ENG", N("energyRecovery"));
                if (Passive(c, Seijaku) is not null && !hasSpirit && N("riposteGain") > 0) c.SetResource("RIPOSTE", 1);
                break;
            case "swordmaster_gale_reaper":
            case "swordmaster_crimson_drive":
                var from = c.PlayerPosition;
                var end = c.Dash(N("range"));
                if ((end - from).LengthSquared <= 1e-8) return Failure;
                if (c.InLine(from + new BalanceVector(0, .9, 0), end + new BalanceVector(0, .9, 0), N("hitRadius")))
                {
                    var dashHit = Hit(N("damageRatio"), cast.ImplementationId == "swordmaster_crimson_drive" ? "FIRE" : "NONE");
                    if (dashHit.Landed && cast.ImplementationId == "swordmaster_crimson_drive")
                        c.ApplyCondition(cast with { ConditionAttackType = "MELEE" }, "BURNING", N("conditionChance"), I("conditionTicks"));
                    if (dashHit.Killed) return new(true, 1);
                }
                break;
            case "swordmaster_mirage_retreat":
                var echo = c.PlayerPosition + new BalanceVector(0, .8, 0);
                var start = c.PlayerPosition;
                var retreat = c.Backstep(N("range"));
                if ((retreat - start).LengthSquared <= 1e-8) return Failure;
                for (var index = 0; index < I("hitCount"); index++)
                    c.Schedule(cast, 1 + index * I("intervalTicks"), () =>
                    {
                        if (c.InSphere(echo, N("radius"))) Hit(N("damageRatio"));
                    });
                break;
            case "swordmaster_vortex_edge":
                var vortexCenter = c.PlayerPosition + new BalanceVector(0, .8, 0);
                if (c.InSphere(vortexCenter, N("radius")) && Hit(N("damageRatio")).Landed && c.EnemyAlive)
                {
                    var delta = (vortexCenter - c.EnemyPosition).Horizontal;
                    if (delta.Length > N("stopDistance"))
                    {
                        var speed = Math.Min(N("pullStrength"), (delta.Length - N("stopDistance")) * .5);
                        c.AddVelocity("enemy", delta.Normalized * speed + new BalanceVector(0, .08, 0));
                    }
                }
                break;
            case "swordsman_exept_stamp":
                if (!c.SetVelocity("player", c.Facing.Horizontal.Normalized * N("launchHorizontalVelocity") + new BalanceVector(0, N("launchVerticalVelocity"), 0))) break;
                s.Stamp = new(cast, c.Sneaking, c.Tick + I("maxFlightTicks"));
                break;
            case "swordsman_challenging_roar":
            case "paladin_fortress":
                // In the single-enemy scenario the caster already owns the target's aggro.
                c.Record(cast, "taunt", 1, "単一敵は引き続き術者を攻撃対象にします。");
                break;
            case "paladin_guardian_chain":
                c.CancelScope(cast.Id);
                for (var index = 0; index < I("pullCount"); index++)
                    c.Schedule(cast, (index + 1) * I("pullIntervalTicks"), () => PullChain(c, cast), cast.Id);
                break;
            case "paladin_guardian_bulwark":
                c.Multiply(cast.Id, "incoming", N("incomingMultiplier"), I("durationTicks"));
                c.Multiply(cast.Id, "outgoing", N("outgoingMultiplier"), I("durationTicks"));
                break;
            case "paladin_guardian_renewal": c.Heal(cast, cast.Stat("MAX_HEALTH") * N("healHpRatio")); break;
            case "paladin_shield_impact":
                if (InRadius(c, c.PlayerPosition + new BalanceVector(0, 1, 0), N("radius"), N("radius")))
                {
                    var impact = Hit(N("damageRatio"));
                    if (!impact.Evaded) DefenseDebuff(c, cast, N("defenseReductionRatio"), I("defenseDebuffDurationTicks"));
                }
                break;
            case "paladin_shield_bash": ShieldBash(c, cast); break;
            case "paladin_guardian_protect":
                if (!BalanceRuntimeParty.For(c).Protect(c, N("targetRange"), N("aimRadius"), I("durationTicks"), N("damageRedirectRatio"),
                    redirected => GainGuard(c, s, redirected, redirected))) return Failure;
                break;
            case "paladin_holy_field":
                BalanceRuntimeParty.For(c).ClearStats(c, "paladin_holy_field:party");
                c.AddStat("paladin_holy_field:caster", "SHIELD_BREAK", 0, "FLAT", 1);
                s.HolyField = new(cast, c.PlayerPosition, c.Tick + I("durationTicks"), I("durationTicks"), N("radius"));
                UpdateField(c, s);
                break;
            case "paladin_blessed_lance":
                var boosted = WithinField(c, s) ? N("fieldMultiplier") : 1;
                var lanceOrigin = c.Eye; var lanceDirection = c.Facing.Normalized;
                c.Schedule(cast, 1, () =>
                {
                    if (c.InLine(lanceOrigin, lanceOrigin + lanceDirection * N("range"), N("hitRadius"))) Hit(N("damageRatio") * boosted);
                });
                break;
            case "paladin_holy_reprieve":
                var reprieveAmount = (N("healAmount") + cast.Stat("MAX_SHIELD") * N("shieldHealRatio")) * (WithinField(c, s) ? N("fieldMultiplier") : 1);
                c.Heal(cast, reprieveAmount);
                foreach (var ally in c.Allies.Where(ally => BalanceRuntimeParty.Within(ally, c.PlayerPosition, N("radius"), N("height")))) c.HealAlly(cast, ally, reprieveAmount);
                break;
            case "paladin_holy_smash":
                if (Cone())
                {
                    var smash = Hit(N("damageRatio"));
                    if (!smash.Evaded && WithinField(c, s))
                    {
                        DefenseDebuff(c, cast, N("defenseReductionRatio"), I("defenseDebuffDurationTicks"));
                        Recover(c, "ENG", N("energyRecoveryAmount"));
                        return new(true, I("reducedCooldownTicks"));
                    }
                }
                break;
            case "paladin_holy_smite":
                AddPillar(c, s, cast, I("durationTicks"), N("radius"), N("damageRatio"), I("impactIntervalTicks"), true);
                break;
            case "paladin_shield":
                if (c.Shield < N("minimumCurrentShield")) return Failure;
                var transferred = c.Shield * N("transferRatio");
                c.AddResource("SHIELD", -transferred);
                StartRecharge(c, s, false);
                var shieldAllies = c.Allies.Where(ally => (ally.Position - c.PlayerPosition).LengthSquared <= N("targetRange") * N("targetRange")).ToArray();
                if (shieldAllies.Length > 0)
                {
                    foreach (var ally in shieldAllies) BalanceRuntimeParty.For(c).GrantShield(c, ally, transferred, I("temporaryShieldDurationTicks"));
                    break;
                }
                AddPillar(c, s, cast, I("temporaryShieldDurationTicks"), N("pillarRadius"), N("pillarDamageRatio"), I("pillarImpactIntervalTicks"), false);
                break;
            case "paladin_holy_control":
                if (c.Hp <= N("hpCost")) return Failure;
                c.AddResource("HP", -N("hpCost"));
                var pillar = s.Pillars.Where(value => value.Until > c.Tick && (value.Center - c.PlayerPosition).Length <= N("pillarSearchRadius"))
                    .OrderBy(value => (value.Center - c.PlayerPosition).LengthSquared).FirstOrDefault();
                if (pillar is null) return new(false, ConsumeCommon: true);
                ClearControl(c, pillar);
                pillar.Center = new(c.PlayerPosition.X, c.FloorY + .05, c.PlayerPosition.Z);
                pillar.Until = c.Tick + pillar.Duration;
                pillar.Control = cast; pillar.NextControl = c.Tick;
                PulsePillars(c, s);
                break;
            default: return Failure;
        }
        return BalanceRuntimeCastResult.Succeeded;
    }

    public void OnEvent(BalanceRuntimeContext c, BalanceRuntimeEvent value)
    {
        var s = Memory(c);
        if (value.Kind == "bind") Bind(c, s);
        if (value.Kind == "death")
        {
            c.SetResource("RIPOSTE", 0); c.SetResource("GUARD", 0);
            s.CounterUntil = s.DodgeUntil = s.CrestCount = 0; s.CrestPending = false; s.HolyField = null; s.Stamp = null; s.Pillars.Clear();
            return;
        }
        if (value.Kind == "tick")
        {
            BalanceRuntimeParty.For(c).Tick(c);
            if (s.CounterUntil > 0 && c.Tick >= s.CounterUntil) EndCounter(c, s);
            if (c.Tick >= s.CrestExpiry) s.CrestCount = 0;
            DecayGuard(c, s); Recharge(c, s); UpdateField(c, s); PulsePillars(c, s); AdvanceStamp(c, s);
        }
        if (value.Kind == "dodge" && Passive(c, "administrator_just_dodge") is { } dodge)
        {
            s.DodgeUntil = c.Tick + Integer(dodge, "invulnerabilityTicks"); s.DodgeRecovered = false;
        }
        if (value.Kind == "normal_attempt" && c.WeaponTag == "SWORD" && Passive(c, Seijaku) is { } counter)
        {
            if (!c.Sneaking) { s.CounterUntil = 0; }
            else
            {
                if (s.CounterUntil <= c.Tick)
                {
                    s.CounterUntil = c.Tick + Integer(counter, "counterTicks");
                    c.AddStat(Seijaku + ":slow", "MOVEMENT_SPEED", -.7, "SCALAR", Integer(counter, "counterTicks"));
                }
                c.State["normal.cancelled"] = true;
            }
        }
        if (value.Kind == "normal_launch" && IsHammer(c) && WithinField(c, s) && s.HolyField is { } field)
            s.HolyField = field with { Center = c.PlayerPosition, Until = c.Tick + field.Duration };
        if (value.Kind == "before_incoming") BeforeIncoming(c, s, value);
        if (value.Kind == "after_incoming" && value.Hit is { } incoming)
        {
            GainGuard(c, s, incoming.RawDamage, incoming.HpDamage);
            if (incoming.Landed) StartRecharge(c, s, c.Shield <= 0);
            Recharge(c, s, true);
        }
        if (value.Hit is { } hit && value.Kind is "damage_applied" or "normal_landed")
        {
            if (hit.Landed && !hit.Secondary && !c.SuppressNotifications)
            {
                OnCrest(c, s, hit);
                if (value.Kind == "damage_applied" && hit.Source == "SKILL") OnDivineChaser(c, hit);
            }
        }
    }

    private static BalanceRuntimeCastResult Failure => new(false, ConsumeCommon: false);
    private static State Memory(BalanceRuntimeContext c) => c.Memory("melee.runtime", () => new State());
    private static JsonObject? Passive(BalanceRuntimeContext c, string implementation) => c.Passives.Values
        .FirstOrDefault(value => BalanceCombatData.Text(value["implementationId"]) == implementation);
    private static double Number(BalanceRuntimeCast cast, string key, double fallback = 0) => BalanceSkillParameters.Number(cast.Params, key, fallback);
    private static double Number(JsonObject definition, string key, double fallback = 0) => BalanceSkillParameters.Number(BalanceSkillParameters.Params(definition), key, fallback);
    private static int Integer(BalanceRuntimeCast cast, string key, int fallback = 0) => BalanceSkillParameters.Integer(cast.Params, key, fallback);
    private static int Integer(JsonObject definition, string key, int fallback = 0) => BalanceSkillParameters.Integer(BalanceSkillParameters.Params(definition), key, fallback);
    private static bool IsHammer(BalanceRuntimeContext c) => c.WeaponTag == "HAMMER";
    private static void Recover(BalanceRuntimeContext c, string resource, double amount)
        => c.RecoverResource(resource, amount);

    private static void Bind(BalanceRuntimeContext c, State s)
    {
        if (s.Bound) return;
        s.Bound = true;
        c.SetResource("RIPOSTE", 0); c.SetResource("GUARD", 0);
        if (Passive(c, "administrator_shield_recharge") is { } recharge)
            c.AddStat("administrator_shield_recharge", "MAX_SHIELD", Number(recharge, "maxShield"), "FLAT", int.MaxValue);
        if (Passive(c, "swordsman_shield_activate") is not null && c.Shield <= 0) StartRecharge(c, s, true);
    }

    internal static double GuardGain(double raw, double hp, double maximumHp)
    {
        raw = Math.Max(0, raw); hp = Math.Max(0, hp);
        return Math.Max(0, (raw <= 0 ? 0 : Math.Max(0, 1 - hp / raw) * 10) + (maximumHp <= 0 ? 0 : hp / maximumHp * 10));
    }

    private static void GainGuard(BalanceRuntimeContext c, State s, double raw, double hp)
    {
        if (Passive(c, Guard) is null) return;
        var gain = GuardGain(raw, hp, c.Stat("MAX_HEALTH"));
        if (gain <= 0) return;
        c.SetResource("GUARD", Math.Min(10, c.Resource("GUARD") + gain));
        s.LastGuardGain = s.LastGuardDecay = c.Tick; s.GuardDecaying = false;
    }

    private static void DecayGuard(BalanceRuntimeContext c, State s)
    {
        if (Passive(c, Guard) is null || c.Tick % 20 != 0 || c.Resource("GUARD") <= 0 || c.Tick - s.LastGuardGain < 200 || c.Tick - s.LastGuardDecay < 20) return;
        if (!s.GuardDecaying) { s.GuardDecay = c.Resource("GUARD") * .1; s.GuardDecaying = true; }
        c.AddResource("GUARD", -Math.Min(c.Resource("GUARD"), s.GuardDecay)); s.LastGuardDecay = c.Tick;
    }

    private static void BeforeIncoming(BalanceRuntimeContext c, State s, BalanceRuntimeEvent value)
    {
        if (value.Hit is { Source: not ("NORMAL_ATTACK" or "SKILL") }) return;
        if (value.Hit?.Evaded != true && c.IncomingDamage > 0 && c.Tick < s.DodgeUntil)
        {
            c.IncomingCancelled = true;
            if (!s.DodgeRecovered && Passive(c, "administrator_just_dodge") is { } dodge)
            { Recover(c, "ENG", Number(dodge, "energyRecoveryAmount")); s.DodgeRecovered = true; }
            return;
        }
        if (c.IncomingCancelled) return;
        if (c.Tick < s.CounterUntil && Passive(c, Seijaku) is { } counter)
        {
            c.IncomingCancelled = true;
            EndCounter(c, s);
            var stacks = Math.Min(Integer(counter, "maxRiposteStacks"), c.Resource("RIPOSTE") + 1);
            c.SetResource("RIPOSTE", stacks);
            var cast = c.Snapshot(counter);
            var ratio = Number(counter, "counterDamageRatio") * (1 + stacks * Number(counter, "counterDamageBonusPerStack"));
            if (c.Hit(cast, ratio, "MELEE").Landed) Recover(c, "ENG", c.Stat("MAX_ENERGY") * Number(counter, "energyRecoveryRatio"));
            var origin = c.Eye; var direction = (c.EnemyCenter - origin).Normalized;
            var swept = false;
            c.CancelScope(Seijaku + ":counter-sweep");
            for (var index = 0; index < 6; index++)
            {
                var frame = index;
                c.Schedule(cast, index + 1, () =>
                {
                    if (swept || !InViewArc(c, origin, direction, Number(counter, "counterSweepRange"), 55 - 110d * frame / 6, 55 - 110d * (frame + 1) / 6)) return;
                    swept = true; c.Hit(cast, ratio, "MELEE");
                }, Seijaku + ":counter-sweep");
            }
            return;
        }
        Recharge(c, s, true);
        if (c.Shield <= 0 || Passive(c, "swordsman_bastion_strike") is not { } bastion || c.Tick < s.BastionReady
            || Math.Abs(c.Mp - c.Stat("MAX_MANA")) > 1e-6 || c.Stat("MAX_MANA") <= 0) return;
        var shieldLoss = BalanceCombatMath.ShieldDamage(c.IncomingDamage, c.Stat("MAX_HEALTH"), c.EnemyStats.GetValueOrDefault("SHIELD_BREAK"), 1, c.Shield);
        if (shieldLoss + 1e-6 < c.Shield || !c.InLine(c.Eye, c.Eye + c.Facing.Normalized * Number(bastion, "range"), 0)) return;
        var retaliation = c.Snapshot(bastion);
        if (!c.Hit(retaliation, Number(bastion, "damageRatio"), "MELEE").Landed) return;
        c.IncomingCancelled = true;
        if (bastion["params"]?["consumeAllCurrentMana"]?.GetValue<bool>() == true) c.SetResource("MP", 0);
        c.SetResource("SHIELD", c.Stat("MAX_SHIELD")); s.RechargeAt = -1;
        s.BastionReady = c.Tick + (int)BalanceCombatMath.CooldownTicks(BalanceCombatData.Integer(bastion["cooldownTicks"]), c.Stat("COOLDOWN_REDUCTION"));
        c.Record(retaliation, "counter", 1, "盾破壊攻撃を無効化");
    }

    private static void EndCounter(BalanceRuntimeContext c, State s)
    {
        s.CounterUntil = 0;
        c.AddStat(Seijaku + ":slow", "MOVEMENT_SPEED", 0, "SCALAR", 1);
        var baseTicks = c.State.TryGetValue("normal.baseCooldownTicks", out var value) && value is int ticks ? ticks : 10;
        var cooldown = BalanceCombatMath.CooldownTicks(baseTicks, c.Stat("COOLDOWN_REDUCTION"));
        var interval = BalanceCombatMath.AttackIntervalTicks(cooldown, c.Stat("ATTACK_SPEED", 100));
        c.State["normal.cooldownUntil"] = c.Tick + (int)Math.Max(1, interval);
    }

    private static void OnCrest(BalanceRuntimeContext c, State s, BalanceRuntimeHit hit)
    {
        if (hit.AttackType != "MELEE" || hit.Source is not ("SKILL" or "NORMAL_ATTACK") || !c.EnemyAlive
            || Passive(c, Crest) is not { } definition || s.CrestPending || c.Tick < s.CrestReady || c.Tick < s.CrestNextHit
            || !c.InSphere(c.Eye, Number(definition, "targetRange"))) return;
        if (c.Tick >= s.CrestExpiry) s.CrestCount = 0;
        s.CrestExpiry = c.Tick + Integer(definition, "crestDurationTicks");
        s.CrestNextHit = c.Tick + Integer(definition, "hitIntervalTicks");
        if (s.CrestCount < Integer(definition, "hitThreshold") - 1) { s.CrestCount++; return; }
        s.CrestPending = true;
        var cast = c.Snapshot(definition);
        c.Schedule(cast, 1, () =>
        {
            s.CrestPending = false;
            if (!c.EnemyAlive || s.CrestCount <= 0 || c.Tick >= s.CrestExpiry || !c.InSphere(c.Eye, Number(definition, "targetRange"))) return;
            var cost = BalanceCombatData.Number(definition["resourceCost"]) * (1 - Math.Clamp(c.Stat("ENERGY_COST_REDUCTION"), 0, 100) / 100);
            if (!c.Spend("ENG", cost)) return;
            s.CrestCount = 0; s.CrestReady = c.Tick + BalanceCombatData.Integer(definition["cooldownTicks"]);
            c.Hit(c.Snapshot(definition), Number(definition, "damageRatio"), "MELEE", secondary: true);
        });
    }

    private static void OnDivineChaser(BalanceRuntimeContext c, BalanceRuntimeHit hit)
    {
        if (!hit.Cast.Tagged("holy_knight") || Passive(c, "paladin_divine_chaser") is not { } definition || !c.EnemyAlive) return;
        var cost = BalanceCombatData.Number(definition["resourceCost"], 2) * (1 - Math.Clamp(c.Stat("MANA_COST_REDUCTION"), 0, 100) / 100);
        if (!c.Spend("MP", cost)) return;
        var power = (c.Stat("MELEE_DEFENSE") + c.Stat("MAGIC_DEFENSE")) * c.Stat("STRENGTH") / 100 + c.Stat("DEFENSE") / 2;
        var cast = c.Snapshot(definition) with { ResolvedAttackPower = Math.Max(0, power) };
        var origin = c.Eye + new BalanceVector(0, 2, 0);
        var delta = c.EnemyPosition + new BalanceVector(0, 1, 0) - origin;
        c.Launch(cast, new(Math.Max(.1, delta.Length), Number(definition, "projectileSpeed"), Number(definition, "projectileHitRadius"),
            origin, delta.Normalized, "MELEE", [new("NONE", Number(definition, "damageRatio"))], Secondary: true));
    }

    private static void StartRecharge(BalanceRuntimeContext c, State s, bool broken)
    {
        if (Passive(c, "swordsman_shield_activate") is null || c.Stat("MAX_SHIELD") <= 0) return;
        if (broken)
        {
            if (s.RechargeAt >= 0 && !s.RechargeIncremental) return;
            s.RechargeAt = c.Tick + RechargeDelay(30, c.Stat("SHIELD_RECHARGE_REDUCTION"));
            s.RechargeIncremental = false;
        }
        else if (c.Shield > 0 && c.Shield < c.Stat("MAX_SHIELD") && Passive(c, "administrator_shield_recharge") is { } definition)
        {
            s.RechargeAt = c.Tick + RechargeDelay(Number(definition, "rechargeDelaySeconds"), c.Stat("SHIELD_RECHARGE_REDUCTION"));
            s.RechargeIncremental = true;
        }
    }

    private static int RechargeDelay(double seconds, double reduction)
        => (int)Math.Ceiling(Math.Floor(seconds * 1000 * (1 - Math.Clamp(reduction, 0, 100) / 100) + .5) / 50);

    private static void Recharge(BalanceRuntimeContext c, State s, bool damageEvent = false)
    {
        if (!damageEvent && c.Tick % 20 != 0) return;
        if (s.RechargeAt < 0 || c.Tick < s.RechargeAt || Passive(c, "swordsman_shield_activate") is null) return;
        // Periodic calls happen each second; the incoming path additionally calls this before and after damage.
        if (!s.RechargeIncremental) { c.SetResource("SHIELD", c.Stat("MAX_SHIELD")); s.RechargeAt = -1; return; }
        if (Passive(c, "administrator_shield_recharge") is not { } definition) { s.RechargeAt = -1; return; }
        c.AddResource("SHIELD", c.Stat("MAX_SHIELD") * Number(definition, "rechargePercentPerSecond") / 100);
        if (c.Shield >= c.Stat("MAX_SHIELD")) s.RechargeAt = -1;
    }

    private static bool WithinField(BalanceRuntimeContext c, State s) => s.HolyField is { } field && field.Until > c.Tick
        && (c.PlayerPosition - field.Center).Horizontal.LengthSquared <= field.Radius * field.Radius;
    private static void UpdateField(BalanceRuntimeContext c, State s)
    {
        if (WithinField(c, s) && s.HolyField is { } field)
            c.AddStat("paladin_holy_field:caster", "SHIELD_BREAK", Number(field.Cast, "casterShieldBreak"), "FLAT", 1);
        if (s.HolyField is not { } partyField || partyField.Until <= c.Tick) return;
        foreach (var ally in c.Allies.Where(ally => BalanceRuntimeParty.Within(ally, partyField.Center, partyField.Radius)))
            BalanceRuntimeParty.For(c).AddStat(c, ally, "paladin_holy_field:party", "SHIELD_BREAK", Number(partyField.Cast, "partyShieldBreak"), 1);
    }
    private static void DefenseDebuff(BalanceRuntimeContext c, BalanceRuntimeCast cast, double reduction, int duration)
    {
        // Both the general and typed defenses are multiplied after their own penetration calculation.
        foreach (var key in new[] { "DEFENSE", "MELEE_DEFENSE", "RANGED_DEFENSE", "MAGIC_DEFENSE" })
            c.AddStat(cast.ImplementationId + ":defense:" + key, key, -reduction / 100, "FINAL_SCALAR", duration, "enemy");
    }
    private static void AddPillar(BalanceRuntimeContext c, State s, BalanceRuntimeCast cast, int duration, double radius, double ratio, int interval, bool weakness)
    {
        var pillar = new Pillar(cast, new(c.PlayerPosition.X, c.FloorY + .05, c.PlayerPosition.Z), duration, radius, ratio, interval, weakness)
        { Created = c.Tick, Until = c.Tick + duration, NextImpact = c.Tick, Key = cast.Id + ":pillar:" + ++s.Serial };
        s.Pillars.Add(pillar); PulsePillars(c, s);
    }
    private static void PulsePillars(BalanceRuntimeContext c, State s)
    {
        foreach (var expired in s.Pillars.Where(pillar => pillar.Until <= c.Tick).ToArray()) { ClearControl(c, expired); s.Pillars.Remove(expired); }
        foreach (var pillar in s.Pillars.ToArray())
        {
            if (c.Tick >= pillar.NextImpact)
            {
                pillar.NextImpact = c.Tick + pillar.Interval;
                if (c.InSphere(pillar.Center, pillar.Radius))
                {
                    var hit = c.Hit(pillar.Cast, pillar.Ratio, "MELEE");
                    if (pillar.Weakness && hit.Landed) c.ApplyCondition(pillar.Cast, "WEAKNESS", Number(pillar.Cast, "weaknessChance"), Integer(pillar.Cast, "weaknessDurationTicks"), Number(pillar.Cast, "weaknessStrength"));
                }
            }
            if (pillar.Control is not { } control || c.Tick < pillar.NextControl) continue;
            pillar.NextControl = c.Tick + 20;
            var radius = Number(control, "effectRadius");
            if ((c.PlayerPosition - pillar.Center).Horizontal.LengthSquared <= radius * radius)
            {
                c.AddStat(pillar.Key + ":control", "SKILL_DAMAGE_INCREASE", Number(control, "partyDamageIncrease"), "FLAT", 20);
                c.Heal(control, Number(control, "hpRecovery")); Recover(c, "MP", c.Stat("MAX_MANA") * Number(control, "mpRecoveryMaxManaRatio"));
            }
            foreach (var ally in c.Allies.Where(ally => BalanceRuntimeParty.Within(ally, pillar.Center, radius)))
            {
                BalanceRuntimeParty.For(c).AddStat(c, ally, pillar.Key + ":control", "SKILL_DAMAGE_INCREASE", Number(control, "partyDamageIncrease"), 20);
                c.HealAlly(control, ally, Number(control, "hpRecovery"));
                c.RecoverAlly(ally, "MP", c.Stat("MAX_MANA") * Number(control, "mpRecoveryMaxManaRatio"));
            }
            if (InRadius(c, pillar.Center, radius, radius)) c.Multiply(pillar.Key + ":control:enemy", "outgoing", 1 + Number(control, "mobDamageTakenIncrease") / 100, 20);
        }
    }
    private static void PullChain(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        if (!InRadius(c, c.PlayerPosition + new BalanceVector(0, 1, 0), Number(cast, "radius"), Number(cast, "radius"))) return;
        var toward = (c.PlayerPosition - c.EnemyPosition).Horizontal;
        var strength = Number(cast, "minimumPullStrength") + (Number(cast, "maximumPullStrength") - Number(cast, "minimumPullStrength"))
            * Math.Clamp(toward.Length / Number(cast, "radius"), 0, 1);
        c.AddVelocity("enemy", toward.Normalized * strength + new BalanceVector(0, Number(cast, "verticalStrength"), 0));
    }
    private static void ShieldBash(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        c.CancelScope(cast.Id);
        var count = Integer(cast, "travelTicks"); var origin = c.PlayerPosition + new BalanceVector(0, Number(cast, "height") / 2, 0);
        var direction = c.Facing.Normalized; var lastHit = int.MinValue;
        for (var frame = 0; frame < count; frame++)
        {
            var current = frame;
            c.Schedule(cast, frame, () =>
            {
                var center = origin + direction * (Number(cast, "range") * (current + 1) / count);
                if ((long)current - lastHit < Integer(cast, "hitCooldownTicks") || !InRadius(c, center, Math.Max(.5, Number(cast, "width") * .6), Number(cast, "height") * .55)) return;
                lastHit = current; c.Hit(cast, Number(cast, "damageRatio"), "MELEE");
                c.AddStat(cast.Id + ":slow", "MOVEMENT_SPEED", -Number(cast, "movementSpeedReductionRatio"), "SCALAR", Integer(cast, "slowDurationTicks"), "enemy");
                var away = (c.EnemyPosition - (center - direction)).Horizontal.Normalized;
                c.AddVelocity("enemy", away * Number(cast, "knockbackStrength") + new BalanceVector(0, .12, 0));
            }, cast.Id);
        }
    }
    private static void AdvanceStamp(BalanceRuntimeContext c, State s)
    {
        if (s.Stamp is not { } flight) return;
        if (c.Tick >= flight.Until) { s.Stamp = null; return; }
        if (!c.OnGround) flight.LeftGround = true;
        if (!flight.LeftGround) { flight.PreviousSneaking = c.Sneaking; return; }
        if (!c.OnGround && !flight.Diving && c.Sneaking && !flight.PreviousSneaking)
        {
            flight.Diving = c.SetVelocity("player", new(0, -Number(flight.Cast, "diveVelocity"), 0));
        }
        flight.PreviousSneaking = c.Sneaking;
        if (!c.OnGround) return;
        s.Stamp = null;
        if (!flight.Diving || !InRadius(c, new(c.PlayerPosition.X, c.FloorY + .05, c.PlayerPosition.Z), Number(flight.Cast, "radius"), Number(flight.Cast, "radius"))) return;
        if (c.Hit(flight.Cast, Number(flight.Cast, "damageRatio"), "MELEE").Landed)
            c.AddStat(flight.Cast.Id + ":slow", "MOVEMENT_SPEED", -Number(flight.Cast, "movementSpeedReduction"), "FLAT", Integer(flight.Cast, "movementSpeedDebuffDurationTicks"), "enemy");
    }

    private static bool InRadius(BalanceRuntimeContext c, BalanceVector center, double radius, double height)
        => c.InRadius(center, radius, height);

    private static void ClearControl(BalanceRuntimeContext c, Pillar pillar)
    {
        if (pillar.Control is null) return;
        BalanceRuntimeParty.For(c).ClearStats(c, pillar.Key + ":control");
        c.AddStat(pillar.Key + ":control", "SKILL_DAMAGE_INCREASE", 0, "FLAT", 1);
        c.Multiply(pillar.Key + ":control:enemy", "outgoing", 1, 1);
    }

    private static bool InViewArc(BalanceRuntimeContext c, BalanceVector origin, BalanceVector forward, double range, double start, double end)
    {
        var offset = c.EnemyCenter - origin;
        if (offset.LengthSquared <= 1e-8 || offset.LengthSquared > range * range) return false;
        var right = new BalanceVector(-forward.Z, 0, forward.X);
        right = right.LengthSquared <= 1e-8 ? new(1, 0, 0) : right.Normalized;
        var up = new BalanceVector(right.Y * forward.Z - right.Z * forward.Y, right.Z * forward.X - right.X * forward.Z, right.X * forward.Y - right.Y * forward.X).Normalized;
        var front = BalanceVector.Dot(offset, forward);
        if (front <= 0) return false;
        var angle = Math.Atan2(BalanceVector.Dot(offset, right), front) * 180 / Math.PI;
        return angle >= Math.Min(start, end) && angle <= Math.Max(start, end) && Math.Abs(BalanceVector.Dot(offset, up)) <= 1.35;
    }

    private static IReadOnlyDictionary<string, Descriptor> CreateDescriptors()
    {
        var result = new Dictionary<string, Descriptor>(StringComparer.Ordinal);
        void Add(string id, string file, string keys, bool passive = false, string[]? requirements = null)
            => result[id] = new(file, keys.Split(' ', StringSplitOptions.RemoveEmptyEntries), passive, requirements);
        Add(Seijaku, "executor/SwordmasterSeijakuIssenExecutor.java", "counterTicks counterDamageRatio energyRecoveryRatio counterSweepRange counterSweepMaxTargets maxRiposteStacks counterDamageBonusPerStack", true);
        Add(Crest, "executor/MeleeDualCrestExecutor.java", "damageRatio hitThreshold hitIntervalTicks crestDurationTicks targetRange", true);
        Add("swordsman_bastion_strike", "executor/SwordsmanBastionStrikeExecutor.java", "range damageRatio consumeAllCurrentMana levelFiveRequiredManaRatio", true);
        Add("swordsman_shield_activate", "executor/SwordsmanShieldActivateSkillExecutor.java", "", true);
        Add("administrator_just_dodge", "executor/AdministratorJustDodgeSkillExecutor.java", "invulnerabilityTicks energyRecoveryAmount", true);
        Add("administrator_shield_recharge", "executor/AdministratorShieldRechargeSkillExecutor.java", "maxShield rechargeDelaySeconds rechargePercentPerSecond particleIntervalTicks", true);
        Add(Guard, "executor/PaladinGuardConvertSkillExecutor.java", "", true);
        Add("paladin_defense_conversion", "executor/PaladinDefenseConversionSkillExecutor.java", "", true);
        Add("paladin_divine_chaser", "executor/PaladinDivineChaserSkillExecutor.java", "damageRatio sphereRadius spherePoints projectileSpeed projectileHitRadius", true);
        foreach (var (id, file, keys, moving) in new (string, string, string, bool)[]
        {
            ("swordmaster_riposte", "SwordmasterRiposteExecutor", "range angle maxTargets damageRatio riposteMultiplier", false),
            ("swordmaster_revenge_accel", "SwordmasterRevengeAccelExecutor", "range angle maxTargets damageRatio riposteMultiplier", false),
            ("swordmaster_serene_mend", "SwordmasterSereneMendExecutor", "healHpRatio riposteHealHpRatio energyRecovery riposteGain", false),
            ("swordmaster_gale_reaper", "SwordmasterGaleReaperExecutor", "range hitRadius maxTargets damageRatio", false),
            ("swordmaster_crimson_drive", "SwordmasterCrimsonDriveExecutor", "range hitRadius maxTargets damageRatio conditionChance conditionTicks", false),
            ("swordmaster_mirage_retreat", "SwordmasterMirageRetreatExecutor", "range radius maxTargets damageRatio hitCount intervalTicks", false),
            ("swordmaster_vortex_edge", "SwordmasterVortexEdgeExecutor", "radius maxTargets damageRatio pullStrength stopDistance", true)
        }) Add(id, "executor/active/swordmaster/" + file + ".java", keys, requirements: moving ? ["velocity_replay"] : null);
        Add("swordsman_challenging_roar", "executor/active/swordsman/SwordsmanChallengingRoarExecutor.java", "radius height maxTargets durationTicks visualIntervalTicks tauntIntervalTicks tauntHoldTicks");
        Add("swordsman_exept_stamp", "executor/active/swordsman/SwordsmanExeptStampExecutor.java", "radius damageRatio movementSpeedReduction movementSpeedDebuffDurationTicks launchHorizontalVelocity launchVerticalVelocity diveVelocity maxFlightTicks", requirements: ["velocity_replay"]);
        foreach (var (id, file, keys) in new (string, string, string)[]
        {
            ("paladin_guardian_bulwark", "PaladinGuardianBulwarkExecutor", "durationTicks incomingMultiplier outgoingMultiplier"),
            ("paladin_guardian_renewal", "PaladinGuardianRenewalExecutor", "healHpRatio"),
            ("paladin_shield_impact", "PaladinShieldImpactExecutor", "radius damageRatio defenseReductionRatio defenseDebuffDurationTicks"),
            ("paladin_holy_field", "PaladinHolyFieldExecutor", "radius durationTicks casterShieldBreak partyShieldBreak"),
            ("paladin_holy_smash", "PaladinHolySmashExecutor", "range targetAngle maxTargets damageRatio defenseReductionRatio defenseDebuffDurationTicks energyRecoveryAmount reducedCooldownTicks"),
            ("paladin_blessed_lance", "PaladinBlessedLanceExecutor", "range hitRadius maxTargets damageRatio fieldMultiplier"),
            ("paladin_holy_reprieve", "PaladinHolyReprieveExecutor", "radius height healAmount shieldHealRatio fieldMultiplier"),
            ("paladin_holy_smite", "PaladinHolySmiteExecutor", "damageRatio radius durationTicks impactIntervalTicks maxTargets weaknessChance weaknessDurationTicks weaknessStrength pillarHeight slashRingRadius"),
            ("paladin_holy_control", "PaladinHolyControlExecutor", "hpCost pillarSearchRadius effectRadius partyDamageIncrease mobDamageTakenIncrease hpRecovery mpRecoveryMaxManaRatio"),
            ("paladin_shield", "PaladinShieldExecutor", "minimumCurrentShield transferRatio targetRange temporaryShieldDurationTicks pillarHeight pillarDamageRatio pillarRadius pillarImpactIntervalTicks pillarMaxTargets pillarSlashRingRadius"),
            ("paladin_fortress", "PaladinFortressExecutor", "radius height maxTargets durationTicks pulseIntervalTicks tauntHoldTicks visualIntervalTicks")
        }) Add(id, "executor/active/paladin/" + file + ".java", keys);
        Add("paladin_guardian_chain", "executor/active/paladin/PaladinGuardianChainExecutor.java", "radius pullCount pullIntervalTicks minimumPullStrength maximumPullStrength verticalStrength curveHeight curvePoints", requirements: ["velocity_replay"]);
        Add("paladin_shield_bash", "executor/active/paladin/PaladinShieldBashExecutor.java", "range width height travelTicks hitCooldownTicks damageRatio movementSpeedReductionRatio slowDurationTicks knockbackStrength", requirements: ["velocity_replay"]);
        Add("paladin_guardian_protect", "executor/active/paladin/PaladinGuardianProtectExecutor.java", "targetRange aimRadius durationTicks visualIntervalTicks damageRedirectRatio", requirements: ["party"]);
        return result;
    }

    private static IReadOnlyList<string> Validate(JsonObject definition, Descriptor descriptor)
    {
        var reasons = new List<string>(); var p = BalanceSkillParameters.Params(definition);
        var id = BalanceCombatData.Text(definition["implementationId"]);
        foreach (var key in descriptor.Keys)
        {
            if (key == "consumeAllCurrentMana")
            {
                if (p[key]?.GetValueKind() != System.Text.Json.JsonValueKind.True && p[key]?.GetValueKind() != System.Text.Json.JsonValueKind.False)
                    reasons.Add($"params.{key} は真偽値が必要です。");
                continue;
            }
            var number = BalanceSkillParameters.Number(p, key, double.NaN);
            var allowZero = key is "weaknessChance" or "defenseReductionRatio" or "movementSpeedReductionRatio";
            var negative = key == "partyShieldBreak";
            var integer = key.EndsWith("Ticks", StringComparison.Ordinal) || key is "maxTargets" or "pillarMaxTargets" or "spherePoints" or "pullCount" or "curvePoints" or "hitCount" or "hitThreshold" or "counterSweepMaxTargets" or "maxRiposteStacks" or "riposteGain";
            var max = double.PositiveInfinity;
            if (id.StartsWith("swordmaster_", StringComparison.Ordinal) && id != Seijaku)
                max = key.EndsWith("Ticks", StringComparison.Ordinal) ? 400 : key == "maxTargets" ? 12 : key == "hitCount" ? 6
                    : key == "angle" ? 180 : key == "range" ? 12 : key == "radius" || key.EndsWith("Radius", StringComparison.Ordinal) ? 6
                    : key.EndsWith("HpRatio", StringComparison.Ordinal) ? 1 : key == "conditionChance" ? 100 : 30;
            if (id is "paladin_blessed_lance" or "paladin_holy_reprieve" or "paladin_guardian_bulwark" or "paladin_guardian_renewal")
                max = key switch
                {
                    "range" or "radius" or "height" or "maxTargets" => 12,
                    "hitRadius" or "healHpRatio" or "shieldHealRatio" or "incomingMultiplier" or "outgoingMultiplier" => 1,
                    "durationTicks" => 100, "healAmount" => 200, _ => 5
                };
            if (key is "transferRatio" or "damageRedirectRatio" or "movementSpeedReductionRatio" or "levelFiveRequiredManaRatio" or "energyRecoveryRatio") max = 1;
            if (key is "defenseReductionRatio" or "weaknessChance" or "targetAngle") max = key == "targetAngle" ? 180 : 100;
            if (id == Seijaku && key == "counterTicks") max = 20;
            if (id == Seijaku && key == "maxRiposteStacks") max = 10;
            if (id == Seijaku && key == "counterDamageBonusPerStack") max = 1;
            if (id == "paladin_divine_chaser" && key == "damageRatio") max = 1;
            if (!double.IsFinite(number) || negative && number >= 0 || !negative && (number < 0 || !allowZero && number == 0 || number > max) || integer && number != Math.Truncate(number))
                reasons.Add($"params.{key} は実Plugin executorの許容範囲外です。");
            if (integer && number > 72_000 || key == "pullCount" && number > 2_000)
                reasons.Add($"params.{key} は計算モデルの実行回数・時刻上限を超えています。");
        }
        if (id == "paladin_divine_chaser" && (Integer(definition, "spherePoints") < 4 || BalanceCombatData.Text(definition["id"]) != id)) reasons.Add("ディバインチェイサーはID一致とspherePoints4以上が必要です。");
        if (id is "swordsman_challenging_roar" or "paladin_fortress")
        {
            var visual = Integer(definition, "visualIntervalTicks");
            var interval = Integer(definition, id == "paladin_fortress" ? "pulseIntervalTicks" : "tauntIntervalTicks");
            if (visual > 0 && interval % visual != 0) reasons.Add("挑発間隔は演出間隔の倍数が必要です。");
        }
        if (id == "swordsman_bastion_strike" && (p["consumeAllCurrentMana"]?.GetValueKind() != System.Text.Json.JsonValueKind.True
            || BalanceCombatData.Integer(definition["cooldownTicks"]) <= 0)) reasons.Add("バスティオンストライクはMP全消費と正のcooldownTicksが必要です。");
        if (id == "swordmaster_vortex_edge" && (Number(definition, "pullStrength") > 1.5 || Number(definition, "stopDistance") >= Number(definition, "radius"))) reasons.Add("pullStrength は1.5以下、stopDistance はradius未満です。");
        if (id == Crest)
        {
            foreach (var (key, expected) in new (string, double)[] { ("hitThreshold", 3), ("hitIntervalTicks", 10), ("crestDurationTicks", 120), ("targetRange", 7) })
                if (Number(definition, key) != expected) reasons.Add($"params.{key} はPlugin固定値{expected}が必要です。");
            if (Number(definition, "damageRatio") is < .35 or > .55) reasons.Add("damageRatio は0.35〜0.55です。");
            if (BalanceCombatData.Number(definition["resourceCost"], 2) != 2 || BalanceCombatData.Integer(definition["cooldownTicks"], 80) != 80) reasons.Add("DualCrestはENG2・CD80tickです。");
        }
        return reasons;
    }
}
