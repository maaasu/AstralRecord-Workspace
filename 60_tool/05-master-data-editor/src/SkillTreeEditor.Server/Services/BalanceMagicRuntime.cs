using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>Plugin の魔法実行状態を試行ごとに保持し、共通戦闘サービスを呼び出します。</summary>
internal sealed class BalanceMagicRuntime : IBalanceSkillRuntime
{
    private const string SkillRoot = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/";
    private static readonly IReadOnlyDictionary<string, string[]> Parameters = new Dictionary<string, string[]>(StringComparer.Ordinal)
    {
        ["adventurer_lightning_bolt"] = ["range", "damageRatio", "chainRadius", "chainDamageRatio", "maxChainTargets", "projectileSpeed", "projectileHitRadius"],
        ["adventurer_meditation"] = ["chargeTicks", "initialRegenMultiplier", "regenMultiplierIncrement", "activeDurationTicks", "buffId", "chargeParticleIntervalTicks", "activeParticleIntervalTicks", "activeSoundIntervalTicks"],
        ["mage_arcane_flow"] = ["castTimeReductionPercent"],
        ["mage_blink"] = ["maxDistance"],
        ["mage_frost_blizzard"] = ["damageRatio", "radius", "height", "movementSpeed", "durationTicks", "damageIntervalTicks", "maxTargets", "orbitVelocity", "inwardVelocity", "verticalVelocity"],
        ["mage_sparking"] = ["damageRatio", "projectileCount", "range", "projectileSpeed", "spiralRadiusGrowth", "spiralDegreesPerTick", "projectileHitRadius", "durationTicks", "shockChance", "shockDurationTicks"],
        ["mage_heal_aura"] = ["radius", "height", "healAmount", "healPercent"],
        ["wizard_burn_meteor_strike"] = [],
        ["wizard_elemental_prism"] = ["placementRange", "radius", "damageRatio", "maxTargets", "projectileCount", "durationTicks", "projectileSpeed"],
        ["wizard_emulate_spark"] = ["damageRatio", "projectileCount", "maxHitsPerTarget", "projectileSpeedPerSecond", "projectileHitRadius", "bounceHeight", "spreadAngle", "durationTicks", "shockChance", "shockDurationTicks"],
        ["wizard_lightning_strike"] = ["range", "damageRatio"],
        ["wizard_meteor"] = ["range", "radius", "damageRatio", "burningChance", "burningDurationTicks", "impactDelayTicks"],
        ["wizard_prism_condition"] = ["radius", "durationTicks", "manaRecoveryRatio"],
        ["wizard_rime_nova"] = ["radius", "maxTargets", "damageRatio", "burningMultiplier", "conditionChance", "conditionTicks", "frozenTicks"],
        ["wizard_spectral_orrery"] = ["damageRatio", "radius", "range", "maxTargets", "chainWindowTicks"],
        ["archmage_astral_ray"] = ["range", "damageRatio", "circleCount", "circleIntervalTicks"],
        ["archmage_bind_circle"] = ["range", "radius", "pullTicks", "bindTicks", "circleLifetimeTicks"],
        ["archmage_celestial_circle"] = ["placementRange", "durationTicks", "effectLevel", "radius", "finalDamageBonus", "otherBonus"],
        ["archmage_clear_circle"] = ["range", "radius", "durationTicks"],
        ["archmage_gravity_nova"] = ["range", "radius", "power"],
        ["archmage_heal_circle"] = ["range", "radius", "healPercent", "durationTicks"],
        ["archmage_phoenix_resonance"] = ["phoenixMax", "targetRange", "despawnDelayTicks", "burningChance"],
        ["archmage_reborn_protect_circle"] = ["range", "radius", "durationTicks"],
        ["archmage_repulsion_circle"] = ["range", "radius", "power", "durationTicks", "pulseTicks"],
        ["archmage_stasis_circle"] = ["range", "radius", "power", "durationTicks", "pulseTicks"],
        ["archmage_aegis_circle"] = ["range", "radius", "power", "durationTicks", "pulseTicks"],
        ["archmage_renewal_circle"] = ["range", "radius", "power", "durationTicks", "pulseTicks"],
        ["archmage_vigor_circle"] = ["range", "radius", "power", "durationTicks", "pulseTicks"]
    };

    /// <summary>実行IDを識別します。パッシブはイベント登録だけを行います。</summary>
    public bool Handles(string implementationId) => Parameters.ContainsKey(implementationId);

    /// <summary>実Pluginで検証したパラメーター、依存ソースと限定空間モデルの前提を返します。</summary>
    internal static BalanceSkillProfile? Resolve(JsonObject definition)
    {
        var id = BalanceCombatData.Text(definition["implementationId"]);
        if (!Parameters.TryGetValue(id, out var known)) return null;
        Validate(definition, id, known);
        return new(id, [], Sources(id), known,
            ["地面は入力floorYの水平面で、壁・天井・遮蔽がなく、術者と敵1体・明示した味方の位置・速度・入力tickだけを扱う。別の敵と世界移動は存在しない。",
                "設置は発動時の視線先を使用し、接触・波・投射は敵の当たり箱で毎tick再判定する。パッシブはバインド済みとしてイベントに接続する。"],
            [], Runtime: true, Passive: definition["passive"] is not null,
            Requirements: id == "archmage_reborn_protect_circle" ? ["party"] : null);
    }

    /// <summary>設置・波・弾道を開始し、ダメージや回復は共有Contextへ委譲します。</summary>
    public BalanceRuntimeCastResult Cast(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        var p = cast.Params;
        switch (cast.ImplementationId)
        {
            case "adventurer_lightning_bolt":
                c.Launch(cast, new(N(p, "range", 14), N(p, "projectileSpeed", 2.8), N(p, "projectileHitRadius", .45),
                    c.Eye, c.Facing, "MAGIC", [new("LIGHTNING", N(p, "damageRatio", 1.74))]));
                break;
            case "mage_frost_blizzard": Blizzard(c, cast); break;
            case "mage_sparking": Sparking(c, cast); break;
            case "mage_heal_aura":
                var auraCenter = c.PlayerPosition;
                c.Heal(cast, Math.Max(N(p, "healAmount", 5), c.Hp * N(p, "healPercent", 1) / 100));
                foreach (var ally in c.Allies.Where(ally => !ally.Dead && Contains(ally.Position, auraCenter, N(p, "radius", 4), N(p, "height", 3))))
                    c.HealAlly(cast, ally, Math.Max(N(p, "healAmount", 5), ally.Hp * N(p, "healPercent", 1) / 100));
                break;
            case "wizard_emulate_spark": EmulateSpark(c, cast); break;
            case "wizard_meteor": Meteor(c, cast, Sight(c, N(p, "range", 16)), N(p, "radius", 8),
                N(p, "damageRatio", 6.3), I(p, "impactDelayTicks", 60), true,
                N(p, "burningChance", 25), I(p, "burningDurationTicks", 100)); break;
            case "wizard_rime_nova": RimeNova(c, cast); break;
            case "wizard_elemental_prism":
                var basePoint = PrismGround(c, N(p, "placementRange", 3));
                if (basePoint is null) return BalanceRuntimeCastResult.Failed;
                State(c).Prism = new(cast, basePoint.Value, c.Tick + I(p, "durationTicks", 200));
                c.Record(cast, "prism_placed", detail: "吸収にはprismタグ付きの対応投射が必要");
                break;
            case "archmage_astral_ray": AstralRay(c, cast); break;
            case "archmage_gravity_nova":
                var center = GroundBuild(c, N(p, "range", 16));
                if (center is null) return BalanceRuntimeCastResult.Failed;
                if (InCylinder(c, center.Value, N(p, "radius", 3) + .75 * c.StrikeCircleCount, 3))
                {
                    var hit = c.Hit(cast, N(p, "power", 1));
                    if (hit.Landed && c.EnemyAlive) Knockback(c, center.Value, .75, .2);
                }
                break;
            case "archmage_bind_circle":
                BindCircle(c, cast, c.GroundTarget(N(p, "range", 16))); break;
            case "archmage_clear_circle": case "archmage_heal_circle": case "archmage_reborn_protect_circle":
                var contactPoint = Ground(c, N(p, "range", 16));
                if (contactPoint is null) return BalanceRuntimeCastResult.Failed;
                AddPickup(c, cast, contactPoint.Value, N(p, "radius", 3), I(p, "durationTicks", 120), cast.ImplementationId);
                break;
            case "archmage_celestial_circle":
                var celestialPoint = GroundBuild(c, N(p, "placementRange", 24), 0);
                if (celestialPoint is null) return BalanceRuntimeCastResult.Failed;
                Celestial(c, cast, celestialPoint.Value); break;
            case "archmage_repulsion_circle": case "archmage_stasis_circle":
            case "archmage_aegis_circle": case "archmage_renewal_circle": case "archmage_vigor_circle":
                var fieldPoint = GroundBuild(c, N(p, "range", 16));
                if (fieldPoint is null) return BalanceRuntimeCastResult.Failed;
                Field(c, cast, fieldPoint.Value); break;
            default: return BalanceRuntimeCastResult.Failed;
        }
        return BalanceRuntimeCastResult.Succeeded;
    }

    /// <summary>成功命中・状態異常・入力・死亡の順序を共有エンジンから受け取ります。</summary>
    public void OnEvent(BalanceRuntimeContext c, BalanceRuntimeEvent e)
    {
        var s = State(c);
        switch (e.Kind)
        {
            case "bind": c.Intercept = (cast, projectile, from, to) => Intercept(c, cast, projectile, from, to); break;
            case "before_cast":
                InterruptMeditation(c);
                if (e.Cast is { } next && next.Tagged("magic") && s.PreviousSkill is { } previous && previous != next.Id
                    && c.Passive("mage_arcane_flow") is { } flow)
                    c.AdditionalCastReductionPercent += N(BalanceSkillParameters.Params(flow), "castTimeReductionPercent", 5);
                break;
            case "cast_completed": if (e.Cast is { } completed) s.PreviousSkill = completed.Id; break;
            case "normal_attempt": InterruptMeditation(c); break;
            case "after_incoming": if (e.Hit is { Damage: > 0 }) InterruptMeditation(c); break;
            case "tick":
                AdvancePickups(c); Meditation(c); Phoenix(c); break;
            case "damage_applied": if (e.Hit is { Landed: true } hit && hit.Source is "SKILL" or "NORMAL_ATTACK")
                { RecordPhoenixHit(c, hit); RecordOrreryHit(c, hit); } break;
            case "condition_applied": if (e.Reason == "SKILL" && e.Cast is { } conditionCast) ConditionApplied(c, conditionCast, e.Condition); break;
            case "input":
                if (!c.Sneaking) InterruptMeditation(c);
                if (c.Sneaking && !s.WasSneaking && !c.OnGround && c.Passive("mage_blink") is { } blink) Blink(c, c.Snapshot(blink));
                s.WasSneaking = c.Sneaking;
                break;
            case "death":
                s.Elements.Clear(); s.PendingOrrery = false; s.PreviousSkill = null;
                s.PhoenixSummoned = false; s.PhoenixAttacking = false; s.PhoenixEggUntil = 0;
                s.PhoenixNormalTarget = false; s.PhoenixCircleTarget = false;
                c.SetResource("PHOENIX_ACTIVE", 0); c.SetResource("PHOENIX", 0); InterruptMeditation(c);
                // 唯一のプレイヤーが死亡すると本人所有の復活陣もlifecycleで終了する。
                // 味方のいない試行で自己蘇生を作り出さない。
                foreach (var pickup in s.Pickups.Where(value => value.Kind != "wizard_prism_condition").ToArray()) RemovePickup(c, pickup);
                break;
        }
    }

    private static void RimeNova(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        var center = c.PlayerPosition + new BalanceVector(0, .6, 0); var struck = false; var p = cast.Params;
        for (var frame = 0; frame < 5; frame++)
        {
            var radius = N(p, "radius", 6) * (frame + 1d) / 5;
            c.Schedule(cast, 1 + frame * 2, () =>
            {
                if (struck || !c.EnemyAlive || c.Hp <= 0 || !c.InSphere(center, radius)) return;
                struck = true;
                var hit = c.Hit(cast, N(p, "damageRatio", 2) * (c.EnemyConditions.Contains("BURNING") ? N(p, "burningMultiplier", 1.5) : 1), element: "ICE");
                if (!hit.Landed) return;
                c.ApplyCondition(cast, "FROZEN", N(p, "conditionChance", 70), I(p, "frozenTicks", 40));
                c.ApplyCondition(cast, "CHILLED", N(p, "conditionChance", 70), I(p, "conditionTicks", 100));
            });
        }
    }

    private static void Meteor(BalanceRuntimeContext c, BalanceRuntimeCast cast, BalanceVector impact,
        double radius, double ratio, int delay, bool wave, double burningChance = 0, int burningTicks = 0)
    {
        var key = Circle(c, cast, impact, radius, delay, false); var struck = false;
        var waveTicks = wave && radius > 3 ? 20 : 0;
        for (var elapsed = 0; elapsed <= waveTicks; elapsed += 2)
        {
            var stage = elapsed;
            c.Schedule(cast, delay + elapsed, () =>
            {
                if (stage == 0) c.Circles.Remove(key);
                if (struck || !c.EnemyAlive) return;
                var currentRadius = waveTicks == 0 ? radius : 3 + (radius - 3) * stage / waveTicks;
                if (!c.InSphere(impact, currentRadius)) return;
                // 後続波は前回半径の内部へ後から入った対象を拾わない。
                if (stage > 0 && c.InSphere(impact, 3 + (radius - 3) * (stage - 2) / waveTicks)) return;
                struck = true;
                var hit = c.Hit(cast, ratio, element: "FIRE");
                if (hit.Landed && burningTicks > 0) c.ApplyCondition(cast, "BURNING", burningChance, burningTicks);
            });
        }
    }

    private static void AstralRay(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        var p = cast.Params; var count = checked(I(p, "circleCount", 1) + 2 * c.CircleCount);
        if (count > 2_000) throw new InvalidOperationException("アストラルレイの生成陣数が上限を超えています。");
        for (var index = 0; index < count; index++)
            c.Schedule(cast, index * I(p, "circleIntervalTicks", 5), () =>
            {
                Circle(c, cast, c.PlayerPosition, 0, 20, false);
                c.Schedule(cast, 10, () => { if (c.EnemyAlive && c.InSphere(c.PlayerPosition, N(p, "range", 18))) c.Hit(cast, N(p, "damageRatio", 1.2)); });
            });
    }

    private static void Field(BalanceRuntimeContext c, BalanceRuntimeCast cast, BalanceVector center)
    {
        var p = cast.Params; var scope = cast.ImplementationId + ":field"; c.CancelScope(scope);
        foreach (var old in c.Circles.Values.Where(value => value.Kind == cast.ImplementationId).ToArray()) c.Circles.Remove(old.Key);
        var support = cast.ImplementationId is "archmage_aegis_circle" or "archmage_renewal_circle" or "archmage_vigor_circle";
        var key = Circle(c, cast, center, N(p, "radius", 3), I(p, "durationTicks", 160) + 1, support);
        for (var age = 0; age < I(p, "durationTicks", 160); age += I(p, "pulseTicks", 20))
            c.Schedule(cast, age + 1, () =>
            {
                if (!c.Circles.ContainsKey(key) || c.Hp <= 0) { c.Circles.Remove(key); return; }
                if (support)
                {
                    if (ContainsPlayer(c, center, N(p, "radius", 3), 2)) SupportPulse(c, cast, null);
                    foreach (var ally in c.Allies.Where(ally => !ally.Dead && Contains(ally.Position, center, N(p, "radius", 3), 2))) SupportPulse(c, cast, ally);
                    return;
                }
                if (!c.EnemyAlive || !InCylinder(c, center, N(p, "radius", 3), 2.5)) return;
                if (cast.ImplementationId == "archmage_repulsion_circle") Knockback(c, center, N(p, "power", 1), .12);
                else
                {
                    var state = State(c); var speed = c.EnemyStats.GetValueOrDefault("MOVEMENT_SPEED");
                    var reduction = (speed > 0 ? speed : 100) * N(p, "power", .3);
                    state.StasisReduction = c.Tick >= state.StasisUntil ? reduction : Math.Max(state.StasisReduction, reduction);
                    state.StasisUntil = c.Tick + 30;
                    c.AddStat("stasis:shared", "MOVEMENT_SPEED", -state.StasisReduction, "FLAT", 30, "enemy");
                }
            }, scope);
    }

    private static void SupportPulse(BalanceRuntimeContext c, BalanceRuntimeCast cast, BalanceRuntimeAlly? ally)
    {
        var stats = ally?.Stats ?? c.PlayerStats; var power = N(cast.Params, "power", 1);
        if (cast.ImplementationId == "archmage_renewal_circle")
        {
            var amount = stats.GetValueOrDefault("MAX_HEALTH") * power;
            if (ally is null) c.Heal(cast, amount); else c.HealAlly(cast, ally, amount);
        }
        else
        {
            var resource = cast.ImplementationId == "archmage_aegis_circle" ? "SHIELD" : "ENG";
            var amount = stats.GetValueOrDefault(resource == "SHIELD" ? "MAX_SHIELD" : "MAX_ENERGY") * power;
            if (ally is null) c.RecoverResource(resource, amount); else c.RecoverAlly(ally, resource == "ENG" ? "ENG_SELF" : resource, amount);
        }
    }

    private static void BindCircle(BalanceRuntimeContext c, BalanceRuntimeCast cast, BalanceVector center)
    {
        var key = Circle(c, cast, center, 4, 400, false); var contacted = false;
        void Contact()
        {
            if (!c.EnemyAlive || !c.Circles.ContainsKey(key)) return;
            if (!InCylinder(c, center, 4, .35)) { c.Schedule(cast, 2, Contact, key); return; }
            contacted = true;
            c.Circles[key] = c.Circles[key] with { UntilTick = c.Tick + 20 };
            for (var age = 0; age < 20; age++)
                c.Schedule(cast, age, () =>
                {
                    if (!c.EnemyAlive) return;
                    var delta = (center - c.EnemyPosition).Horizontal; var d = delta.Length;
                    c.Velocity("enemy", d <= .5 ? BalanceVector.Zero : delta.Normalized * Math.Min(.35, d * .18));
                }, key);
            c.Schedule(cast, 20, () =>
            {
                c.Circles.Remove(key); if (!c.EnemyAlive) return;
                c.Velocity("enemy", BalanceVector.Zero);
                var until = c.Tick + 600; State(c).BoundUntil = until;
                c.SetResource("BOUND_UNTIL", until);
                c.Schedule(cast, 600, () => { if (State(c).BoundUntil == until) c.SetResource("BOUND_UNTIL", 0); });
                c.Record(cast, "bind", 600);
            }, key);
        }
        Contact();
        // 接触後は400tick寿命を越えても20tickの引き寄せを完了する。
        c.Schedule(cast, 400, () => { if (!contacted) { c.Circles.Remove(key); c.CancelScope(key); } });
    }

    private static void Celestial(BalanceRuntimeContext c, BalanceRuntimeCast cast, BalanceVector center)
    {
        var s = State(c); var level = I(cast.Params, "effectLevel", 1);
        if (s.CelestialKey is { } previous) c.Circles.Remove(previous);
        foreach (var ally in c.Allies.Where(ally => s.CelestialAllies.Contains(ally.Id))) c.RemoveAllyBuff?.Invoke(ally, s.CelestialBuff);
        s.CelestialAllies.Clear();
        c.RemoveBuff(s.CelestialBuff); var key = Circle(c, cast, center, level, 39 + I(cast.Params, "durationTicks", 600), true);
        s.CelestialKey = key; s.CelestialBuff = level == 1 ? "archmage_celestial_circle" : "archmage_celestial_circle_lv" + level;
        var applied = false; var appliedAllies = s.CelestialAllies;
        void Synchronize()
        {
            if (s.CelestialKey != key || !c.Circles.ContainsKey(key))
            {
                if (s.CelestialKey == key) c.RemoveBuff(s.CelestialBuff);
                foreach (var ally in c.Allies.Where(ally => appliedAllies.Contains(ally.Id))) c.RemoveAllyBuff?.Invoke(ally, BuffId());
                return;
            }
            var inside = ContainsPlayer(c, center, level, 2.5);
            if (inside && !applied) applied = c.ApplyBuff(cast, s.CelestialBuff);
            else if (!inside && applied) { c.RemoveBuff(s.CelestialBuff); applied = false; }
            foreach (var ally in c.Allies)
            {
                var allyInside = !ally.Dead && Contains(ally.Position, center, level, 2.5);
                if (allyInside && !appliedAllies.Contains(ally.Id) && c.ApplyAllyBuff?.Invoke(cast, ally, BuffId()) == true) appliedAllies.Add(ally.Id);
                else if (!allyInside && appliedAllies.Remove(ally.Id)) c.RemoveAllyBuff?.Invoke(ally, BuffId());
            }
            c.Schedule(cast, 4, Synchronize, key);
        }
        string BuffId() => level == 1 ? "archmage_celestial_circle" : "archmage_celestial_circle_lv" + level;
        c.Schedule(cast, 39, Synchronize, key);
        c.Schedule(cast, 39 + I(cast.Params, "durationTicks", 600), () =>
        {
            if (s.CelestialKey != key) return;
            c.RemoveBuff(s.CelestialBuff); c.Circles.Remove(key); c.CancelScope(key);
            foreach (var ally in c.Allies.Where(ally => appliedAllies.Contains(ally.Id))) c.RemoveAllyBuff?.Invoke(ally, BuffId());
        });
    }

    private static void AddPickup(BalanceRuntimeContext c, BalanceRuntimeCast cast, BalanceVector center,
        double radius, int duration, string kind)
    {
        var key = Circle(c, cast, center, radius, duration + 1, kind != "archmage_reborn_protect_circle");
        var pickup = new Pickup(key, kind, cast, center, radius, c.Tick + duration + 1, c.Tick + 1);
        State(c).Pickups.Add(pickup);
        // 接触陣は最後のdelay=1の反復が終わったtickにcleanupされる。
        // PrismConditionは別のruntimeでremainingTicks=0を次tickに検出する。
        if (kind != "wizard_prism_condition") c.Schedule(cast, duration, () => RemovePickup(c, pickup));
    }

    private static void AdvancePickups(BalanceRuntimeContext c)
    {
        foreach (var pickup in State(c).Pickups.ToArray())
        {
            if (pickup.Until <= c.Tick || !c.Circles.ContainsKey(pickup.Key)) { RemovePickup(c, pickup); continue; }
            if (pickup.Start > c.Tick) continue;
            if (pickup.Kind == "archmage_reborn_protect_circle")
            {
                var dead = c.Allies.Where(ally => ally.Dead && Contains(ally.Position, pickup.Center, pickup.Radius, .45))
                    .OrderBy(ally => (ally.Position - pickup.Center).LengthSquared).ThenBy(ally => ally.Id, StringComparer.Ordinal).FirstOrDefault();
                if (dead is null) continue;
                dead.Hp = dead.Stats.GetValueOrDefault("MAX_HEALTH"); dead.Mp = dead.Stats.GetValueOrDefault("MAX_MANA");
                dead.Eng = dead.Stats.GetValueOrDefault("MAX_ENERGY"); dead.Shield = dead.ShieldEnabled ? dead.Stats.GetValueOrDefault("MAX_SHIELD") : 0;
                c.ApplyAllyBuff?.Invoke(pickup.Cast, dead, "archmage_battle_confusion"); dead.InvulnerableUntil = c.Tick + 60;
                RemovePickup(c, pickup); c.Record(pickup.Cast, "ally_revive", detail: dead.Id); continue;
            }
            var height = pickup.Kind == "wizard_prism_condition" ? 2 : .45;
            var recipients = c.Allies.Where(ally => !ally.Dead && Contains(ally.Position, pickup.Center, pickup.Radius, height))
                .Select(ally => (Id: ally.Id, Ally: (BalanceRuntimeAlly?)ally)).ToList();
            if (c.Hp > 0 && ContainsPlayer(c, pickup.Center, pickup.Radius, height)) recipients.Insert(0, (c.PlayerId, null));
            if (pickup.Kind != "wizard_prism_condition") recipients.Sort((left, right) => StringComparer.Ordinal.Compare(left.Id, right.Id));
            foreach (var recipient in recipients)
            {
                var changed = recipient.Ally is { } ally ? ApplyAllyPickup(c, pickup, ally) : ApplyPlayerPickup(c, pickup);
                if (!changed) continue;
                RemovePickup(c, pickup); c.Record(pickup.Cast, "circle_consumed", detail: recipient.Id); break;
            }
        }
    }

    private static bool ApplyPlayerPickup(BalanceRuntimeContext c, Pickup pickup)
    {
        switch (pickup.Kind)
        {
            case "wizard_prism_condition":
                var before = c.Mp;
                if (!c.PlayerConditions.Contains("HEALING_INHIBITION")) c.AddResource("MP", c.Stat("MAX_MANA") * N(pickup.Cast.Params, "manaRecoveryRatio", .03));
                return c.Mp > before;
            case "archmage_heal_circle":
                var changed = c.Heal(pickup.Cast, c.Stat("MAX_HEALTH") * N(pickup.Cast.Params, "healPercent", 14) / 100) > 0;
                if (changed) c.SetSkillCooldown(pickup.Cast.Id, 0); return changed;
            case "archmage_clear_circle":
                if (c.ClearNegativeEffects is { } clear) return clear() > 0;
                var count = c.PlayerConditions.Count; c.PlayerConditions.Clear(); return count > 0;
            default: return false;
        }
    }

    private static bool ApplyAllyPickup(BalanceRuntimeContext c, Pickup pickup, BalanceRuntimeAlly ally)
    {
        switch (pickup.Kind)
        {
            case "wizard_prism_condition":
                var before = ally.Mp;
                if (!ally.Conditions.Contains("HEALING_INHIBITION")) c.RecoverAlly(ally, "MP_FIXED", ally.Stats.GetValueOrDefault("MAX_MANA") * N(pickup.Cast.Params, "manaRecoveryRatio", .03));
                return ally.Mp > before;
            case "archmage_heal_circle":
                var changed = c.HealAlly(pickup.Cast, ally, ally.Stats.GetValueOrDefault("MAX_HEALTH") * N(pickup.Cast.Params, "healPercent", 14) / 100) > 0;
                if (changed) c.SetSkillCooldown(pickup.Cast.Id, 0); return changed;
            case "archmage_clear_circle":
                if (c.ClearAllyNegativeEffects is { } clear) return clear(ally) > 0;
                var count = ally.Conditions.Count; ally.Conditions.Clear(); return count > 0;
            default: return false;
        }
    }

    private static void ConditionApplied(BalanceRuntimeContext c, BalanceRuntimeCast source, string condition)
    {
        if (c.Passive("wizard_prism_condition") is { } prismDefinition)
        {
            var cast = c.Snapshot(prismDefinition); var p = cast.Params;
            AddPickup(c, cast, c.EnemyPosition + new BalanceVector(0, .08, 0), N(p, "radius", 1), I(p, "durationTicks", 60), "wizard_prism_condition");
        }
        if (condition == "BURNING" && c.Distance <= 30 && c.Passive("wizard_burn_meteor_strike") is { } burn)
        {
            var cast = new BalanceRuntimeCast(BalanceCombatData.Text(burn["id"]), burn, source.Stats);
            if (SpendMana(c, burn)) Meteor(c, cast, c.EnemyPosition, 2.5, 1.5, 20, false);
        }
        if (condition == "SHOCKED" && c.Passive("wizard_lightning_strike") is { } lightning
            && c.Distance <= N(BalanceSkillParameters.Params(lightning), "range", 30) && SpendMana(c, lightning))
            c.Hit(new(BalanceCombatData.Text(lightning["id"]), lightning, source.Stats),
                N(BalanceSkillParameters.Params(lightning), "damageRatio", 1.5), element: "LIGHTNING");
    }

    private static void RecordOrreryHit(BalanceRuntimeContext c, BalanceRuntimeHit hit)
    {
        if (hit.Secondary || hit.AttackType != "MAGIC" || c.Passive("wizard_spectral_orrery") is not { } definition) return;
        var s = State(c); var p = BalanceSkillParameters.Params(definition);
        var center = c.EnemyPosition + new BalanceVector(0, .9, 0);
        if (s.PendingOrrery || c.Tick < s.OrreryReady || (center - c.PlayerPosition).Length > N(p, "range", 24)) return;
        if (c.Tick > s.OrreryExpires) s.Elements.Clear();
        var changed = false;
        foreach (var component in hit.Components.Where(component => component.Ratio > 0 && component.Element is "FIRE" or "ICE" or "LIGHTNING"))
        {
            if (s.Elements.Count == 0) s.OrreryExpires = c.Tick + I(p, "chainWindowTicks", 200);
            changed |= s.Elements.Add(component.Element);
        }
        if (!changed || s.Elements.Count != 3) return;
        s.Elements.Clear(); s.PendingOrrery = true;
        var pendingGeneration = ++s.OrreryGeneration;
        c.Schedule(c.Snapshot(definition), 1, () =>
        {
            if (s.OrreryGeneration != pendingGeneration || !s.PendingOrrery) return;
            s.PendingOrrery = false;
            if (c.Hp <= 0 || (center - c.PlayerPosition).Length > N(p, "range", 24)
                || !c.EnemyAlive || !c.InSphere(center, N(p, "radius", 2.5)) || !SpendMana(c, definition)) return;
            s.OrreryReady = c.Tick + BalanceCombatData.Integer(definition["cooldownTicks"], 160);
            var ratio = N(p, "damageRatio", .4); var cast = c.Snapshot(definition);
            c.Hit(cast, [new("FIRE", ratio), new("ICE", ratio), new("LIGHTNING", ratio)], secondary: true);
        });
    }

    private static void Meditation(BalanceRuntimeContext c)
    {
        if (c.Passive("adventurer_meditation") is not { } definition) return;
        var s = State(c); var p = BalanceSkillParameters.Params(definition);
        if (!c.Sneaking || c.Hp <= 0) { InterruptMeditation(c); return; }
        if (s.MeditationCompleted) return;
        s.MeditationStarted ??= c.Tick;
        var age = c.Tick - s.MeditationStarted.Value - I(p, "chargeTicks", 60);
        if (age < 0) return;
        if (age >= I(p, "activeDurationTicks", 140))
        {
            c.SetResource("MP", c.Stat("MAX_MANA")); c.SetResource("ENG", c.Stat("MAX_ENERGY"));
            c.RemoveBuff("adventurer_meditation"); s.MeditationCompleted = true; s.MeditationActive = false;
            c.Record(c.Snapshot(definition), "meditation_complete"); return;
        }
        if (!s.MeditationActive) { s.MeditationActive = true; c.ApplyBuff(c.Snapshot(definition), "adventurer_meditation"); }
        c.NaturalRegenMultiplier *= N(p, "initialRegenMultiplier", 2) + age / 20 * N(p, "regenMultiplierIncrement", .5);
    }

    private static void InterruptMeditation(BalanceRuntimeContext c)
    {
        var s = State(c); if (s.MeditationActive) c.RemoveBuff("adventurer_meditation");
        s.MeditationStarted = null; s.MeditationActive = false; s.MeditationCompleted = false;
    }

    private static void Blink(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        var state = State(c);
        if (c.Tick < state.BlinkReady || c.PlayerConditions.Contains("FROZEN") || c.Facing.Normalized.Y < -.9) return;
        var p = cast.Params; var direction = c.Facing.Normalized; var start = c.PlayerPosition;
        // 平坦な床では、Plugin同様0/1/-1Yの候補で床を捕捉できる地点だけへ移動する。
        var destination = start;
        var limit = Math.Min(10, N(p, "maxDistance", 10));
        if (direction.Y < -1e-8) limit = Math.Min(limit, Math.Max(0, (c.FloorY - start.Y) / direction.Y - .05));
        for (var distance = limit; distance >= .25; distance -= .25)
        {
            var candidate = start + direction * distance;
            if (direction.Y > .99 || new[] { 0, 1, -1 }.Any(offset => Math.Floor(candidate.Y + offset) == Math.Ceiling(c.FloorY)))
            { destination = direction.Y > .99 ? candidate : candidate with { Y = c.FloorY }; break; }
        }
        if ((destination - start).LengthSquared <= 1e-6 || !SpendMana(c, cast.Definition)) return;
        c.PlayerPosition = destination; c.OnGround = Math.Abs(destination.Y - c.FloorY) <= 1e-6;
        InterruptMeditation(c);
        var cooldown = BalanceCombatData.Integer(cast.Definition["cooldownTicks"]);
        state.BlinkReady = checked(c.Tick + (int)Math.Max(1, BalanceCombatMath.CooldownTicks(cooldown, c.Stat("COOLDOWN_REDUCTION"))));
        c.SetSkillCooldown(cast.Id, state.BlinkReady - c.Tick);
        c.Dispatch(new("cast_completed", cast));
        c.Record(cast, "blink", (destination - start).Length);
    }

    private static void RecordPhoenixHit(BalanceRuntimeContext c, BalanceRuntimeHit hit)
    {
        if (State(c).ApplyingPhoenix || c.Passive("archmage_phoenix_resonance") is not { } definition) return;
        var s = State(c);
        if (!s.PhoenixSummoned && s.PhoenixEggUntil == 0 && c.EnemyAlive)
        {
            s.PhoenixSummoned = true; s.PhoenixNextAttack = c.Tick + 160; s.PhoenixLastInRange = c.Tick;
            c.SetResource("PHOENIX", N(BalanceSkillParameters.Params(definition), "phoenixMax", 500)); c.SetResource("PHOENIX_ACTIVE", 1);
        }
        if (hit.Source == "NORMAL_ATTACK") s.PhoenixNormalTarget = true;
        if (s.PhoenixSummoned && SupportedPlayer(c)) s.PhoenixCircleTarget = true;
        s.PhoenixLastInRange = c.Tick;
    }

    private static void Phoenix(BalanceRuntimeContext c)
    {
        if (c.Passive("archmage_phoenix_resonance") is not { } definition) return;
        var s = State(c); var p = BalanceSkillParameters.Params(definition);
        if (s.PhoenixEggUntil > 0)
        {
            if (c.Tick < s.PhoenixEggUntil) return;
            s.PhoenixEggUntil = 0; s.PhoenixSummoned = true; s.PhoenixNextAttack = c.Tick + 160;
            s.PhoenixLastInRange = c.Tick; c.SetResource("PHOENIX", N(p, "phoenixMax", 500)); c.SetResource("PHOENIX_ACTIVE", 1);
            if (c.Skill("wizard_meteor") is { } meteor)
            {
                var upgraded = MaxLevelParameters(meteor); var mp = BalanceSkillParameters.Params(upgraded);
                Meteor(c, c.Snapshot(upgraded), s.PhoenixEggPosition, N(mp, "radius", 8), N(mp, "damageRatio", 6.3),
                    I(mp, "impactDelayTicks", 60), true, N(mp, "burningChance", 25), I(mp, "burningDurationTicks", 100));
            }
        }
        if (!s.PhoenixSummoned) return;
        if (c.EnemyAlive && c.Distance <= N(p, "targetRange", 50)) s.PhoenixLastInRange = c.Tick;
        if (c.Tick - s.PhoenixLastInRange >= I(p, "despawnDelayTicks", 600))
        { s.PhoenixSummoned = false; c.SetResource("PHOENIX", 0); c.SetResource("PHOENIX_ACTIVE", 0); return; }
        if (s.PhoenixAttacking && c.Tick >= s.PhoenixAttackEnd)
        {
            s.PhoenixAttacking = false; c.AddResource("PHOENIX", -20);
            if (c.EnemyAlive && c.Distance <= N(p, "targetRange", 50))
            {
                s.ApplyingPhoenix = true;
                try { c.Hit(c.Snapshot(definition), .3, element: "FIRE"); }
                finally { s.ApplyingPhoenix = false; }
            }
            if (c.Resource("PHOENIX") <= 0)
            { s.PhoenixSummoned = false; s.PhoenixEggUntil = c.Tick + 600; s.PhoenixEggPosition = s.PhoenixAttackPosition with { Y = c.FloorY + .08 }; c.SetResource("PHOENIX_ACTIVE", 0); return; }
            if (s.PhoenixExtra > 0 && StartPhoenixAttack(c, s, p)) { s.PhoenixExtra--; return; }
            s.PhoenixNextAttack = c.Tick + 160;
        }
        if (!s.PhoenixAttacking && c.Tick >= s.PhoenixNextAttack)
        {
            s.PhoenixExtra = (int)Math.Min(int.MaxValue, Math.Max(0, Math.Floor(c.Stat("PHOENIX_EXTRA_ATTACK_COUNT"))));
            if (!StartPhoenixAttack(c, s, p)) s.PhoenixNextAttack = c.Tick + 20;
        }
    }

    private static bool StartPhoenixAttack(BalanceRuntimeContext c, MagicState s, JsonObject p)
    {
        if (s.PhoenixCircleTarget && !SupportedPlayer(c)) s.PhoenixCircleTarget = false;
        if (!(s.PhoenixNormalTarget || s.PhoenixCircleTarget) || !c.EnemyAlive || c.Distance > N(p, "targetRange", 50) || c.Resource("PHOENIX") < 20 || !c.Spend("MP", 20)) return false;
        var angle = c.Random.NextDouble() * Math.PI * 2; var radius = Math.Sqrt(c.Random.NextDouble()) * 3;
        s.PhoenixAttackPosition = c.EnemyPosition + new BalanceVector(Math.Cos(angle) * radius, 2.2, Math.Sin(angle) * radius);
        s.PhoenixAttacking = true; s.PhoenixAttackEnd = c.Tick + 30; return true;
    }

    private static bool Intercept(BalanceRuntimeContext c, BalanceRuntimeCast cast, BalanceRuntimeProjectile projectile, BalanceVector from, BalanceVector to)
    {
        if (!projectile.Interceptable || !cast.Tagged("prism") || cast.ImplementationId is not
            ("adventurer_lightning_bolt" or "mage_fireball" or "mage_frost_ball" or "wizard_elemental_ball" or "wizard_prismatic_dart")
            || State(c).Prism is not { } prism || prism.Until <= c.Tick || projectile.Components.Count != 1) return false;
        var element = projectile.Components[0].Element;
        if (element is not ("NONE" or "FIRE" or "ICE" or "LIGHTNING")) return false;
        var delta = to - from; var distance = delta.Length;
        var along = BalanceMagicGeometry.FirstSphereHit(from, delta.Normalized, prism.Base + new BalanceVector(0, 1.45, 0), .82 + projectile.Radius, distance);
        if (!double.IsFinite(along)) return false;
        if (BalanceRuntimeGeometry.SegmentHitsBox(from, to, c.EnemyPosition, c.EnemyHalfWidth, c.EnemyHeight, projectile.Radius, out var fraction)
            && fraction * distance + 1e-6 < along) return false;
        var p = prism.Cast.Params; var origin = prism.Base + new BalanceVector(0, 1.45, 0);
        c.Record(cast, "prism_absorbed");
        if (!c.EnemyAlive || !c.InSphere(origin, N(p, "radius", 8))) return true;
        for (var index = 0; index < I(p, "projectileCount", 3); index++)
            c.Schedule(prism.Cast, index + 1, () =>
            {
                if (!c.EnemyAlive) return;
                if (element == "LIGHTNING") c.Hit(prism.Cast, N(p, "damageRatio", .5), element: element);
                else c.Launch(prism.Cast, new(N(p, "radius", 8) + 2, N(p, "projectileSpeed", 1.4), .35,
                    origin, (c.EnemyPosition + new BalanceVector(0, 1, 0) - origin).Normalized, "MAGIC", [new(element, N(p, "damageRatio", .5))], Interceptable: false));
            });
        return true;
    }

    private static void Blizzard(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        var p = cast.Params; var direction = c.Facing.Normalized; var firstBlock = FloorRay(c, c.Eye, direction, 2.55);
        var stopped = double.IsFinite(firstBlock); var center = c.Eye + direction * (stopped ? Math.Max(.3, firstBlock - .55) : 2);
        for (var tick = 0; tick < I(p, "durationTicks", 200); tick++)
        {
            var age = tick;
            c.Schedule(cast, tick, () =>
            {
                if (!stopped)
                {
                    var speed = N(p, "movementSpeed", .18); var collision = FloorRay(c, center, direction, speed + .55);
                    var travel = double.IsFinite(collision) ? Math.Max(0, Math.Min(speed, collision - .55)) : speed;
                    center += direction * travel; stopped = double.IsFinite(collision);
                }
                if (!c.EnemyAlive || age % I(p, "damageIntervalTicks", 10) != 0 && age % 4 != 0
                    || !InCylinder(c, center, N(p, "radius", 2.75), N(p, "height", 2.5))) return;
                c.AddVelocity("enemy", BalanceMagicGeometry.VortexVelocity(center, c.EnemyPosition, direction,
                    N(p, "orbitVelocity", .16), N(p, "inwardVelocity", .08), N(p, "verticalVelocity", .04)));
                if (age % I(p, "damageIntervalTicks", 10) == 0) c.Hit(cast, N(p, "damageRatio", .24), element: "ICE");
            });
        }
    }

    private static void Sparking(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        var p = cast.Params; var yaw = Math.Atan2(-c.Facing.X, c.Facing.Z);
        // 初期雷弾はダメージを与えず着弾だけで螺旋を展開する。
        c.Launch(cast, new(N(p, "range", 16), N(p, "projectileSpeed", 1.45), N(p, "projectileHitRadius", .6),
            c.Eye, c.Facing, "MAGIC", [], Interceptable: false, OnTermination: (location, effectLocation, termination) =>
            {
                if (termination is not ("ENTITY" or "BLOCK" or "entity" or "block")) return;
                var origin = termination is "BLOCK" or "block" ? effectLocation : location;
                var sparks = Enumerable.Range(0, I(p, "projectileCount", 5)).Select(index =>
                    new Spiral(origin, yaw + Math.PI * 2 * index / I(p, "projectileCount", 5))).ToArray();
                var lastHit = -10;
                for (var tick = 0; tick < I(p, "durationTicks", 50); tick++)
                {
                    var age = tick;
                    c.Schedule(cast, tick, () =>
                    {
                        foreach (var spark in sparks)
                        {
                            var movement = spark.Advance(N(p, "spiralRadiusGrowth", .14), N(p, "spiralDegreesPerTick", 3) * Math.PI / 180);
                            var next = spark.Position + movement;
                            if (c.EnemyAlive && age - lastHit >= 10 && c.InLine(spark.Position, next, N(p, "projectileHitRadius", .6)))
                            {
                                lastHit = age; var hit = c.Hit(cast, N(p, "damageRatio", 1.2), element: "LIGHTNING");
                                if (hit.Landed) c.ApplyCondition(cast, "SHOCKED", N(p, "shockChance", 25), I(p, "shockDurationTicks", 100));
                            }
                            spark.Position = next;
                        }
                    });
                }
            }));
    }

    private static void EmulateSpark(BalanceRuntimeContext c, BalanceRuntimeCast cast)
    {
        var p = cast.Params; var count = I(p, "projectileCount", 1); var attempts = 0;
        var forward = c.Facing.Horizontal.Normalized; var origin = c.Eye + forward * .35;
        for (var index = 0; index < count; index++)
        {
            var angle = count == 1 ? 0 : (-N(p, "spreadAngle", 30) / 2 + N(p, "spreadAngle", 30) * index / (count - 1)) * Math.PI / 180;
            var horizontal = BalanceMagicGeometry.RotateY(forward, angle); var position = origin; var vertical = -.12; var active = true;
            for (var tick = 0; tick < I(p, "durationTicks", 30); tick++)
                c.Schedule(cast, tick, () =>
                {
                    if (!active) return;
                    var remaining = 1d;
                    for (var reflection = 0; reflection < 4 && remaining > 1e-7; reflection++)
                    {
                        var step = horizontal * (N(p, "projectileSpeedPerSecond", 15) / 20 * remaining)
                            + new BalanceVector(0, vertical * remaining - .5 * .04 * remaining * remaining, 0);
                        var next = position + step;
                        var floorFraction = next.Y < c.FloorY && step.Y < 0 ? Math.Clamp((c.FloorY - position.Y) / step.Y, 0, 1) : 1;
                        var end = position + step * floorFraction;
                        if (c.EnemyAlive && c.InLine(position, end, N(p, "projectileHitRadius", .35)))
                        {
                            active = false;
                            if (attempts++ < I(p, "maxHitsPerTarget", 4))
                            {
                                var hit = c.Hit(cast, N(p, "damageRatio", .75), element: "LIGHTNING");
                                if (hit.Landed) c.ApplyCondition(cast, "SHOCKED", N(p, "shockChance", 12), I(p, "shockDurationTicks", 100));
                            }
                            break;
                        }
                        if (floorFraction >= 1) { position = next; vertical -= .04 * remaining; break; }
                        position = end + new BalanceVector(0, .05, 0);
                        vertical = BalanceMagicGeometry.BounceSpeed(N(p, "bounceHeight", 1.3));
                        remaining *= 1 - floorFraction;
                    }
                });
        }
    }

    private static string Circle(BalanceRuntimeContext c, BalanceRuntimeCast cast, BalanceVector center, double radius, int duration, bool support)
    {
        var s = State(c); var key = cast.ImplementationId + ":" + ++s.Sequence;
        c.Circles[key] = new(key, cast.ImplementationId, center, radius, c.Tick, c.Tick + duration, support);
        return key;
    }
    private static BalanceVector Sight(BalanceRuntimeContext c, double range)
        => c.RayEnd(range);
    private static double FloorRay(BalanceRuntimeContext c, BalanceVector from, BalanceVector direction, double range)
    {
        var distance = direction.Y < -1e-8 ? (c.FloorY - from.Y) / direction.Y : double.PositiveInfinity;
        return distance >= 0 && distance <= range ? distance : double.PositiveInfinity;
    }
    private static BalanceVector? Ground(BalanceRuntimeContext c, double range)
    {
        var sight = Sight(c, range);
        if (c.FloorY < sight.Y - 7.5 || c.FloorY > sight.Y + .5) return null;
        var floor = sight with { Y = c.FloorY };
        return (floor - c.Eye).LengthSquared <= range * range + 1e-6 ? floor + new BalanceVector(0, .06, 0) : null;
    }
    private static BalanceVector? GroundBuild(BalanceRuntimeContext c, double range, double offset = .06)
    {
        var ground = c.GroundTarget(range);
        return Math.Abs(ground.Y - c.FloorY - .05) <= 1e-6 && (ground - c.Eye).LengthSquared <= range * range
            ? ground + new BalanceVector(0, offset, 0) : null;
    }
    private static BalanceVector? PrismGround(BalanceRuntimeContext c, double range)
        => c.FloorY >= Math.Floor(c.Eye.Y) - 32 && c.FloorY <= Math.Floor(c.Eye.Y) + 3
            ? new(c.Eye.X + c.Facing.Horizontal.Normalized.X * range, c.FloorY + .05, c.Eye.Z + c.Facing.Horizontal.Normalized.Z * range) : null;
    private static bool ContainsPlayer(BalanceRuntimeContext c, BalanceVector center, double radius, double height)
        => Contains(c.PlayerPosition, center, radius, height);
    private static bool Contains(BalanceVector position, BalanceVector center, double radius, double height)
        => Math.Abs(position.Y - center.Y) <= height && (position - center).Horizontal.LengthSquared <= radius * radius;
    private static bool InCylinder(BalanceRuntimeContext c, BalanceVector center, double radius, double height)
    {
        var dx = Math.Clamp(center.X, c.EnemyPosition.X - c.EnemyHalfWidth, c.EnemyPosition.X + c.EnemyHalfWidth) - center.X;
        var dz = Math.Clamp(center.Z, c.EnemyPosition.Z - c.EnemyHalfWidth, c.EnemyPosition.Z + c.EnemyHalfWidth) - center.Z;
        return dx * dx + dz * dz <= radius * radius && c.EnemyPosition.Y + c.EnemyHeight >= center.Y - height && c.EnemyPosition.Y <= center.Y + height;
    }
    private static bool SupportedPlayer(BalanceRuntimeContext c) => c.Circles.Values.Any(circle => circle.Support && circle.UntilTick > c.Tick
        && (circle.Kind != "archmage_celestial_circle" || c.Tick - circle.CreatedTick >= 39)
        && ContainsPlayer(c, circle.Center, circle.Radius, circle.Kind is "archmage_heal_circle" or "archmage_clear_circle" ? .45 : circle.Kind == "archmage_celestial_circle" ? 2.5 : 2));
    private static void Knockback(BalanceRuntimeContext c, BalanceVector center, double horizontal, double vertical)
    {
        var away = (c.EnemyPosition - center).Horizontal;
        c.AddVelocity("enemy", (away.LengthSquared < 1e-12 ? BalanceVector.Zero : away.Normalized * horizontal) + new BalanceVector(0, vertical, 0));
    }
    private static bool SpendMana(BalanceRuntimeContext c, JsonObject definition)
        => c.TryConsumeCastResources?.Invoke(c.Snapshot(definition))
            ?? throw new InvalidOperationException("共通のスキル資源消費サービスが接続されていません。");
    private static void RemovePickup(BalanceRuntimeContext c, Pickup pickup) { c.Circles.Remove(pickup.Key); State(c).Pickups.Remove(pickup); }
    private static MagicState State(BalanceRuntimeContext c) => c.Memory("magic-runtime", () => new MagicState());
    private static double N(JsonObject p, string key, double fallback = 0) => BalanceSkillParameters.Number(p, key, fallback);
    private static int I(JsonObject p, string key, int fallback = 0) => BalanceSkillParameters.Integer(p, key, fallback);
    private static JsonObject MaxLevelParameters(JsonObject definition)
    {
        var clone = (JsonObject)definition.DeepClone(); var p = BalanceSkillParameters.Params(clone);
        foreach (var level in BalanceCombatData.Array(clone["levels"]).OfType<JsonObject>())
            if (BalanceCombatData.Integer(level["level"]) <= BalanceCombatData.Integer(clone["maxLevel"], 1) && level["paramDeltas"] is JsonObject deltas)
                foreach (var entry in deltas) p[entry.Key] = N(p, entry.Key) + BalanceCombatData.Number(entry.Value);
        return clone;
    }

    private sealed class MagicState
    {
        internal string? PreviousSkill, CelestialKey;
        internal string CelestialBuff = "archmage_celestial_circle";
        internal readonly HashSet<string> Elements = new(StringComparer.Ordinal);
        internal readonly HashSet<string> CelestialAllies = new(StringComparer.Ordinal);
        internal readonly List<Pickup> Pickups = [];
        internal Prism? Prism;
        internal int Sequence, OrreryExpires = -1, OrreryReady, OrreryGeneration, BoundUntil, StasisUntil, BlinkReady;
        internal double StasisReduction;
        internal bool PendingOrrery, WasSneaking, MeditationActive, MeditationCompleted;
        internal int? MeditationStarted;
        internal bool PhoenixSummoned, PhoenixAttacking, PhoenixNormalTarget, PhoenixCircleTarget, ApplyingPhoenix;
        internal int PhoenixNextAttack, PhoenixAttackEnd, PhoenixExtra, PhoenixLastInRange, PhoenixEggUntil;
        internal BalanceVector PhoenixEggPosition, PhoenixAttackPosition;
    }
    private sealed record Pickup(string Key, string Kind, BalanceRuntimeCast Cast, BalanceVector Center, double Radius, int Until, int Start);
    private sealed record Prism(BalanceRuntimeCast Cast, BalanceVector Base, int Until);
    private sealed class Spiral(BalanceVector origin, double yaw)
    {
        internal BalanceVector Position = origin;
        private double radius, angle;
        internal BalanceVector Advance(double growth, double radians)
            => BalanceMagicGeometry.AdvanceSpiral(ref radius, ref angle, yaw, growth, radians);
    }

    private static void Validate(JsonObject definition, string id, string[] known)
    {
        var p = BalanceSkillParameters.Params(definition);
        foreach (var key in known.Where(key => key != "buffId" && p.ContainsKey(key))) N(p, key);
        // 各実executorの必須値を確認し、モデルの無制限なtask生成も防止する。
        var optional = id is "archmage_phoenix_resonance" or "wizard_lightning_strike" or "wizard_spectral_orrery";
        foreach (var key in known.Where(key => key is not ("buffId" or "finalDamageBonus" or "otherBonus" or "burningChance") && !(id == "wizard_rime_nova" && key == "frozenTicks")))
        {
            if (!p.ContainsKey(key)) { if (optional) continue; throw new ArgumentException($"{id} の params.{key} は必須です。"); }
            var value = N(p, key);
            var zeroAllowed = key is "shockChance" or "spreadAngle" or "inwardVelocity" or "verticalVelocity";
            if (value < 0 || !zeroAllowed && value == 0) throw new ArgumentException($"{id} の params.{key} の値が範囲外です。");
            if (key.EndsWith("Ticks", StringComparison.Ordinal) || key is "projectileCount" or "maxTargets" or "maxChainTargets" or "maxHitsPerTarget" or "circleCount" or "effectLevel") I(p, key);
            if (key.EndsWith("Ticks", StringComparison.Ordinal) && value > 72_000 || key is "projectileCount" or "circleCount" && value > 2_000)
                throw new ArgumentException($"{id} の params.{key} は計算モデルの上限を超えています。");
        }
        foreach (var key in new[] { "shockChance", "burningChance", "conditionChance" }.Concat(id == "archmage_heal_circle" ? ["healPercent"] : Array.Empty<string>()))
            if (p.ContainsKey(key) && N(p, key) is < 0 or > 100) throw new ArgumentException($"{id} の params.{key} は0～100です。");
        if (id == "mage_blink" && N(p, "maxDistance") > 10) throw new ArgumentException("ブリンクの最大距離は10です。");
        if (id == "mage_arcane_flow" && N(p, "castTimeReductionPercent") != 5) throw new ArgumentException("Lv1のアーケインフローは5%です。");
        if (id == "wizard_meteor" && (I(p, "impactDelayTicks") <= 2 || I(p, "impactDelayTicks") % 2 != 0)) throw new ArgumentException("メテオの着弾遅延は4tick以上の偶数です。");
        if (id == "wizard_meteor" && !p.ContainsKey("burningChance")) throw new ArgumentException("メテオの params.burningChance は必須です。");
        if (id == "wizard_emulate_spark" && (N(p, "bounceHeight") <= .05 || I(p, "maxHitsPerTarget") is < 1 or > 9 || N(p, "spreadAngle") > 180)) throw new ArgumentException("跳躍高・同一敵命中数・散開角がPluginの許容範囲外です。");
        if (id == "archmage_bind_circle" && (N(p, "radius") != 4 || I(p, "pullTicks") != 20 || I(p, "bindTicks") != 600 || I(p, "circleLifetimeTicks") != 400)) throw new ArgumentException("バインドサークルの固定値がPluginと一致しません。");
        if (id is "archmage_repulsion_circle" or "archmage_stasis_circle" or "archmage_aegis_circle" or "archmage_renewal_circle" or "archmage_vigor_circle")
            if (I(p, "durationTicks") < 10 || I(p, "durationTicks") % 10 != 0 || I(p, "pulseTicks") < 10 || I(p, "pulseTicks") % 10 != 0) throw new ArgumentException("設置陣の時間は10tick単位です。");
        if (id == "archmage_celestial_circle" && (I(p, "effectLevel") is < 1 or > 10 || N(p, "radius") != I(p, "effectLevel"))) throw new ArgumentException("セレスティアルの半径とeffectLevelは1～10の同値です。");
        if (id == "wizard_rime_nova" && (N(p, "radius") > 10 || I(p, "frozenTicks", 40) is < 1 or > 60)) throw new ArgumentException("ライムノヴァの半径・凍結時間が許容範囲外です。");
        if (id == "wizard_rime_nova" && (I(p, "maxTargets") > 8 || N(p, "damageRatio") > 20 || N(p, "burningMultiplier") > 20 || I(p, "conditionTicks") > 400)) throw new ArgumentException("ライムノヴァの効果値がWizard共通上限を超えています。");
        if (id == "wizard_prism_condition" && (N(p, "radius") != 1 || I(p, "durationTicks") != 60 || N(p, "manaRecoveryRatio") != .03)) throw new ArgumentException("Lv1のプリズムコンディションの固定値が一致しません。");
        if (id == "archmage_phoenix_resonance" && (N(p, "phoenixMax", 500) != 500 || N(p, "targetRange", 50) != 50 || I(p, "despawnDelayTicks", 600) != 600)) throw new ArgumentException("不死鳥の固定値がPluginと一致しません。");
        if (id == "adventurer_meditation" && (I(p, "chargeTicks") != 60 || N(p, "initialRegenMultiplier") != 2 || N(p, "regenMultiplierIncrement") != .5 || I(p, "activeDurationTicks") != 140 || BalanceCombatData.Text(p["buffId"]) != "buff:adventurer_meditation")) throw new ArgumentException("メディテーションの固定値がPluginと一致しません。");
        if (id == "wizard_spectral_orrery")
        {
            Bounded("damageRatio", .4, .01, 2); Bounded("radius", 2.5, .1, 5); Bounded("range", 24, 1, 32);
            Bounded("maxTargets", 3, 1, 5); Bounded("chainWindowTicks", 200, 20, 400);
            var cost = BalanceCombatData.Number(definition["resourceCost"], double.NaN); var cd = BalanceCombatData.Integer(definition["cooldownTicks"]);
            if (BalanceCombatData.Text(definition["resourceType"]) != "MANA" || !double.IsFinite(cost) || cost is < 1 or > 100 || cd is < 40 or > 1200) throw new ArgumentException("天球儀のMP/CDが許容範囲外です。");
        }
        void Bounded(string key, double fallback, double min, double max) { if (N(p, key, fallback) < min || N(p, key, fallback) > max) throw new ArgumentException($"{id} の params.{key} が許容範囲外です。"); }
    }

    private static string[] Sources(string id)
    {
        var direct = id switch
        {
            "adventurer_lightning_bolt" => "executor/active/adventurer/AdventurerLightningBoltExecutor.java",
            "adventurer_meditation" => "executor/MeditationSkillExecutor.java",
            "mage_arcane_flow" => "executor/MageArcaneFlowSkillExecutor.java",
            "mage_blink" => "executor/passive/mage/MageBlinkSkillExecutor.java",
            "wizard_burn_meteor_strike" => "executor/WizardBurnMeteorStrikeSkillExecutor.java",
            "wizard_lightning_strike" => "executor/WizardLightningStrikeSkillExecutor.java",
            "wizard_prism_condition" => "executor/WizardPrismConditionSkillExecutor.java",
            "wizard_spectral_orrery" => "executor/WizardSpectralOrreryExecutor.java",
            "archmage_phoenix_resonance" => "executor/ArchmagePhoenixResonanceExecutor.java",
            _ => "executor/active/" + id.Split('_')[0] + "/" + string.Concat(id.Split('_').Select(word => char.ToUpperInvariant(word[0]) + word[1..])) + "Executor.java"
        };
        var result = new List<string> { SkillRoot + direct, SkillRoot + "executor/active/support/PlayerActiveSkillExecutor.java",
            SkillRoot + "active/service/SkillCombatService.java", SkillRoot + "active/service/SkillTargetingService.java", SkillRoot + "active/service/SkillTaskService.java",
            SkillRoot + "active/service/SkillProjectileService.java", SkillRoot + "active/service/ActiveSkillLifecycleService.java" };
        var runtime = id switch
        {
            "adventurer_meditation" => "service/MeditationSkillRuntimeService.java",
            "mage_arcane_flow" => "service/ArcaneFlowSkillRuntimeService.java",
            "mage_blink" => "service/MageBlinkSkillRuntimeService.java",
            "wizard_burn_meteor_strike" => "service/WizardBurnMeteorStrikeRuntimeService.java",
            "wizard_lightning_strike" => "executor/passive/wizard/WizardLightningStrikeRuntimeService.java",
            "wizard_prism_condition" => "service/WizardPrismConditionRuntimeService.java",
            "wizard_spectral_orrery" => "service/WizardSpectralOrreryRuntimeService.java",
            "archmage_phoenix_resonance" => "service/ArchmagePhoenixRuntimeService.java",
            "archmage_celestial_circle" => "executor/active/archmage/ArchmageCelestialCircleRuntimeService.java",
            "wizard_elemental_prism" => "active/service/ElementalPrismRuntimeService.java",
            _ => ""
        };
        if (runtime.Length > 0) result.Add(SkillRoot + runtime);
        if (id is "wizard_burn_meteor_strike" or "archmage_phoenix_resonance") result.Add(SkillRoot + "executor/active/wizard/WizardMeteorExecutor.java");
        if (id is "archmage_repulsion_circle" or "archmage_stasis_circle" or "archmage_aegis_circle" or "archmage_renewal_circle" or "archmage_vigor_circle") result.Add(SkillRoot + "executor/active/archmage/ArchmageFieldSkillExecutor.java");
        if (id == "mage_blink") result.Add(SkillRoot + "active/service/SkillMovementService.java");
        if (id == "archmage_bind_circle") result.Add(SkillRoot + "service/BindCircleRuntimeService.java");
        if (id.StartsWith("archmage_", StringComparison.Ordinal)) result.Add(SkillRoot + "executor/active/archmage/ArchmageBuildSupport.java");
        if (id == "wizard_rime_nova") result.Add(SkillRoot + "executor/active/wizard/WizardSkillSupport.java");
        if (id == "adventurer_meditation") result.Add(SkillRoot + "event/MeditationSkillEventHandler.java");
        result.Add(SkillRoot + "active/service/SkillMagicCircleRegistry.java");
        return result.Distinct(StringComparer.Ordinal).ToArray();
    }
}

/// <summary>Plugin の非描画の位置・速度処理です。Java実メソッドの固定出力で照合します。</summary>
internal static class BalanceMagicGeometry
{
    internal static double FirstSphereHit(BalanceVector origin, BalanceVector direction, BalanceVector center, double radius, double maxDistance)
    {
        if (maxDistance <= 0) return double.NaN;
        var offset = origin - center; var along = BalanceVector.Dot(offset, direction);
        var discriminant = along * along - (offset.LengthSquared - radius * radius);
        if (discriminant < 0) return double.NaN;
        var root = Math.Sqrt(discriminant); if (-along + root < 0) return double.NaN;
        var distance = Math.Max(0, -along - root); return distance < maxDistance ? distance : double.NaN;
    }
    internal static BalanceVector VortexVelocity(BalanceVector center, BalanceVector target, BalanceVector fallback, double orbit, double inward, double vertical)
    {
        var outward = (target - center).Horizontal;
        if (outward.LengthSquared <= 1e-8) outward = fallback.Horizontal;
        if (outward.LengthSquared <= 1e-8) outward = new(0, 0, 1);
        outward = outward.Normalized;
        return (new BalanceVector(-outward.Z, 0, outward.X) * Math.Max(0, orbit) - outward * Math.Max(0, inward)) with { Y = Math.Max(0, vertical) };
    }
    internal static BalanceVector RotateY(BalanceVector vector, double angle)
        => new(vector.X * Math.Cos(angle) + vector.Z * Math.Sin(angle), vector.Y, -vector.X * Math.Sin(angle) + vector.Z * Math.Cos(angle));
    internal static double BounceSpeed(double height) => Math.Sqrt(2 * .04 * (height - .05));
    internal static BalanceVector AdvanceSpiral(ref double radius, ref double angle, double yaw, double growth, double radians)
    {
        var previousRadial = radius * Math.Cos(angle); var previousTangential = radius * Math.Sin(angle);
        radius += growth; angle += radians;
        return new BalanceVector(-Math.Sin(yaw), 0, Math.Cos(yaw)) * (radius * Math.Cos(angle) - previousRadial)
            + new BalanceVector(-Math.Cos(yaw), 0, -Math.Sin(yaw)) * (radius * Math.Sin(angle) - previousTangential);
    }
}
