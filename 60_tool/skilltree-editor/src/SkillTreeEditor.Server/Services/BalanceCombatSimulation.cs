using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

public static partial class BalanceCombat
{
    private sealed class SkillCast(Skill skill, Dictionary<string, double> stats)
    {
        internal Skill Skill { get; } = skill;
        internal Dictionary<string, double> Stats { get; } = stats;
        internal bool Landed, Critical, Killed;
        internal double Damage, ShieldDamage;
        internal BalanceVector PlayerPosition, Eye, Facing, EnemyPosition;
    }
    private sealed record PendingEffect(SkillCast Cast, BalanceSkillEffect Effect, int Order, int Generation, bool CollisionConfirmed = false);
    private sealed record TimedEffect(string Key, BalanceSkillEffect Effect, int Until);
    private sealed class SimCondition(string name, int until, double power, int nextDot, int nextControl, SkillCast cast)
    {
        internal string Name = name;
        internal int Until = until, NextDot = nextDot, NextControl = nextControl, BlockedUntil;
        internal double Power = power;
        internal SkillCast Cast = cast;
    }

    private static JsonObject Run(JsonObject request, int seed, double maxSeconds, Dictionary<string, double> player,
        Dictionary<string, double> enemy, JsonObject mob, JsonObject? enemyNormal, (string Type, int Ticks) weapon,
        string weaponName, IReadOnlyList<Skill> skills, string classId,
        List<(string Code, string Message, string Severity)> diagnostics, List<string> assumptions,
        JsonObject catalog, JsonObject build, CancellationToken token, IReadOnlyList<Skill> passives, string weaponTag, bool dynamic)
    {
        var rng = new Random(seed);
        var maxTicks = Math.Min(72_000, (int)Math.Ceiling(maxSeconds * 20));
        var hp = Math.Max(1, Stat(player, "MAX_HEALTH"));
        var mp = Math.Max(0, Stat(player, "MAX_MANA"));
        var eng = Math.Max(0, Stat(player, "MAX_ENERGY"));
        var shield = Math.Clamp(BalanceCombatData.Number(build["initialShield"]), 0, Math.Max(0, Stat(player, "MAX_SHIELD")));
        var enemyHp = Math.Max(1, Stat(enemy, "MAX_HEALTH"));
        var enemyShield = BalanceCombatData.Boolean(mob["shield"]?["enabled"]) ? BalanceCombatData.Number(mob["shield"]?["max"]) : 0;
        var normalAttacks = build["normalAttacks"]?.GetValue<bool>() != false;
        var distance = BalanceCombatData.Number(request["enemy"]?["distanceBlocks"], 3);
        var halfDepth = BalanceCombatData.Number(request["enemy"]?["targetHalfDepth"], .3);
        assumptions.Add(dynamic ? "位置はworldとinputsを正本にします。敵の当たり箱は指定した半幅・高さで、平面以外の遮蔽物はありません。"
            : $"敵は正面中心線の{distance}ブロック先、奥行き半幅{halfDepth}の固定当たり箱です。投射は遮蔽・重力なしの対応弾だけを扱います。");
        assumptions.Add($"通常攻撃: {(normalAttacks ? "使用" : "使用しない")}。開始時の自盾={shield}。");
        var baseEnemyInterval = BalanceCombatData.Integer(request["enemy"]?["attackIntervalTicks"], BalanceCombatData.Integer(enemyNormal?["intervalTicks"]));
        var enemyRange = Math.Max(0, BalanceCombatData.Number(enemyNormal?["range"], BalanceCombatData.Number(mob["ai"]?["combat"]?["preferredRange"], 1)));
        var enemyInRange = distance * distance <= enemyRange * enemyRange + 4;
        assumptions.Add($"敵通常攻撃はPluginの距離²≤射程²+4（射程{enemyRange}）を適用します。現在は{(enemyInRange ? "射程内" : "射程外で攻撃しない")}。敵は接近しません。");
        var enemyInterval = BalanceCombatMath.AttackIntervalTicks(baseEnemyInterval, Stat(enemy, "ATTACK_SPEED"));
        if (enemyInterval <= 0) enemyInterval = long.MaxValue;
        var enemyType = BalanceCombatData.Text(mob["ai"]?["combat"]?["style"]);
        if (enemyType is not ("MELEE" or "RANGED" or "MAGIC")) enemyType = "MELEE";
        long nextPlayer = 0, nextEnemy = enemyInterval;
        var normalCount = 0;
        long lastNormalTick = long.MinValue;
        var dealt = 0.0; var taken = 0.0;
        var contributions = new Dictionary<string, (string Name, int Uses, double Damage)>(StringComparer.OrdinalIgnoreCase);
        var ready = skills.ToDictionary(skill => skill.Id, _ => 0L, StringComparer.OrdinalIgnoreCase);
        var pending = new SortedDictionary<int, List<PendingEffect>>();
        var timed = new List<TimedEffect>();
        var circles = new Dictionary<string, int>(StringComparer.Ordinal);
        var scopeGenerations = new Dictionary<string, int>(StringComparer.Ordinal);
        var conditions = new Dictionary<string, SimCondition>(StringComparer.Ordinal);
        Skill? casting = null; long castAt = long.MaxValue;
        int? mpExhausted = null, engExhausted = null;
        var resources = new JsonArray();
        var timeline = new JsonArray();
        var outcome = "timeout"; var elapsed = maxTicks;
        var currentTick = 0; var sequence = 0;
        var actions = new SortedDictionary<int, List<(int Order, Action Action, string Scope, int Generation)>>();
        var buffUntil = new Dictionary<string, int>(StringComparer.Ordinal);
        var buffGroups = new Dictionary<string, string>(StringComparer.Ordinal);
        IBalanceSkillRuntime[] runtimes = [new BalanceMeleeRuntime(), new BalanceRangedRuntime(), new BalanceMagicRuntime()];
        var definitions = BalanceCombatData.Masters(catalog).Where(master => master.Kind == "skill")
            .GroupBy(master => BalanceCombatData.Text(master.Content["implementationId"]), StringComparer.Ordinal)
            .ToDictionary(group => group.Key, group => group.First().Content, StringComparer.Ordinal);
        foreach (var skill in skills.Concat(passives)) definitions[skill.Profile.ImplementationId] = skill.Definition;
        BalanceRuntimeContext runtime = null!;
        BalanceRuntimeWorld world = null!;
        runtime = new()
        {
            Catalog = catalog, Build = build, Request = request, Random = rng, Definitions = definitions,
            Passives = passives.ToDictionary(skill => skill.Profile.ImplementationId, skill => skill.Definition, StringComparer.Ordinal),
            WeaponTag = weaponTag, ReadResource = ReadResource, WriteResource = WriteResource,
            ReadStats = enemySide => Stats(enemySide ? enemy : player, enemySide ? "enemy" : "player"),
            Queue = (cast, delay, action, scope) =>
            {
                if (actions.Sum(pair => pair.Value.Count) >= 20_000) throw new BalanceRuntimeUnsupportedException("同時予約処理が20000件を超えました。");
                var at = currentTick + delay;
                if (!actions.TryGetValue(at, out var list)) actions[at] = list = [];
                list.Add((sequence++, action, scope, scopeGenerations.GetValueOrDefault(scope)));
            },
            Damage = RuntimeHit, Healing = Heal, Condition = RuntimeCondition,
            StatEffect = (key, status, value, mode, duration, target) => AddTimed(key, new("stat", Value: value, Status: status, Mode: mode, Target: target), duration),
            MultiplierEffect = (key, kind, value, duration) =>
            {
                if (kind is "incoming" or "outgoing") AddTimed(key, new(kind, Value: value), duration);
                else if (kind == "EVASION") AddTimed(key, new("stat", Status: kind, Value: value - 1, Mode: "FINAL_SCALAR"), duration);
                else throw new BalanceRuntimeUnsupportedException($"未対応の倍率効果 {kind} です。");
            },
            Buff = ApplyBuff, RemoveBuff = RemoveBuff,
            BuffRemaining = id => Math.Max(0, buffUntil.GetValueOrDefault(id) - currentTick),
            SetBuffRemaining = (id, remaining) =>
            {
                if (remaining <= 0) RemoveBuff(id);
                else { buffUntil[id] = currentTick + remaining; for (var i = 0; i < timed.Count; i++) if (timed[i].Key.StartsWith("buff:" + id + ":", StringComparison.Ordinal)) timed[i] = timed[i] with { Until = currentTick + remaining }; }
            },
            Projectile = (cast, projectile) => world.Launch(cast, projectile),
            SetSkillCooldown = (id, ticks) => { foreach (var skill in skills.Where(skill => skill.Id == id || skill.Profile.ImplementationId == id)) ready[skill.Id] = currentTick + Math.Max(0, ticks); },
            Trace = RuntimeTrace, Dispatch = Dispatch,
            MovePlayer = (start, direction, length) => world.Move(start, direction, length),
            Velocity = (target, velocity) => world.Velocity(target, velocity),
            AdditionalVelocity = (target, velocity) => world.AddVelocity(target, velocity),
            ClearNegativeEffects = ClearNegative,
            CancelScope = scope => scopeGenerations[scope] = scopeGenerations.GetValueOrDefault(scope) + 1,
            NormalAttack = () => NormalAttack(true), ResourceRecovery = Recover,
            TryConsumeCastResources = ConsumeResources
            ,AllyHealing = HealAlly, AllyRecovery = RecoverAlly,
            ApplyAllyBuff = (cast, ally, id) => ApplyBuffTo(cast, id, "ally:" + ally.Id),
            RemoveAllyBuff = (ally, id) => RemoveBuffFrom(id, "ally:" + ally.Id),
            ClearAllyNegativeEffects = ally => { var count = ally.Conditions.Count; ally.Conditions.Clear(); return count + timed.RemoveAll(item => item.Effect.Target == "ally:" + ally.Id && item.Effect.Value < 0); },
            PlayerId = BalanceCombatData.Text(build["actorId"]) is { Length: > 0 } actorId ? actorId : "00000000-0000-0000-0000-000000000000"
        };
        world = new(runtime); world.Initialize();
        runtime.State["normal.baseCooldownTicks"] = weapon.Ticks;
        if (dynamic)
        {
            foreach (var passive in passives)
                foreach (var (modifier, index) in BalanceCombatData.Array(passive.Definition["params"]?["modifiers"]).OfType<JsonObject>().Select((value, index) => (value, index)))
                    AddTimed("passive:" + passive.Id + ":" + index + ":" + BalanceCombatData.Text(modifier["status"]),
                        new("stat", Value: BalanceCombatData.Number(modifier["value"]), Status: BalanceCombatData.Text(modifier["status"]), Mode: BalanceCombatData.Text(modifier["type"])), maxTicks + 1);
            Dispatch(new("bind"));
        }

        try
        {
        for (var tick = 0; tick <= maxTicks; tick++)
        {
            currentTick = tick; token.ThrowIfCancellationRequested();
            runtime.Tick = tick;
            runtime.NaturalRegenMultiplier = 1;
            timed.RemoveAll(item => item.Until <= tick);
            foreach (var ally in runtime.Allies)
            {
                var values = Stats(ally.BaseStats, "ally:" + ally.Id); ally.Stats.Clear(); foreach (var (key, value) in values) ally.Stats[key] = value;
            }
            if (dynamic) { world.Tick(); Dispatch(new("tick")); }
            foreach (var key in circles.Where(pair => pair.Value <= tick).Select(pair => pair.Key).ToArray()) circles.Remove(key);
            foreach (var key in conditions.Where(pair => pair.Value.Until <= tick).Select(pair => pair.Key).ToArray()) conditions.Remove(key);
            runtime.EnemyConditions.RemoveWhere(key => conditions.ContainsKey(key) == false && key != "BOUND");
            foreach (var key in conditions.Keys) runtime.EnemyConditions.Add(key);
            ProcessConditions(tick);
            if (enemyHp <= 0) { outcome = "victory"; elapsed = tick; break; }
            if (tick > 0 && tick % 20 == 0)
            {
                var stats = Stats(player, "player");
                if (hp < Stat(stats, "MAX_HEALTH") && Stat(stats, "HP_REGEN") > 0)
                    Heal(new("natural_regen", new(), stats), Stat(stats, "HP_REGEN") / 5);
                Recover("MP", Math.Max(0, Stat(stats, "MP_REGEN")) / 5 * runtime.NaturalRegenMultiplier);
                Recover("ENG", Math.Max(0, Stat(stats, "ENERGY_REGEN")) / 5 * runtime.NaturalRegenMultiplier);
            }
            ProcessPending(tick);
            if (enemyHp <= 0) { outcome = "victory"; elapsed = tick; break; }
            var used = false;
            if (casting is not null && tick >= castAt) { Cast(casting, tick); casting = null; used = true; }
            if (casting is null && !used)
                foreach (var skill in skills)
                {
                    if (tick < ready[skill.Id]) continue;
                    var stats = Stats(player, "player");
                    if (skill.Kind == "heal" && hp >= Stat(stats, "MAX_HEALTH") * .75) continue;
                    var cost = Cost(skill, stats);
                    var mana = ManaCost(skill, stats);
                    if (mp + 1e-6 < mana) { mpExhausted ??= tick; continue; }
                    if (skill.Resource == "ENERGY" && eng + 1e-6 < cost) { engExhausted ??= tick; continue; }
                    if (skill.Resource == "GUARD" && runtime.Resource("GUARD") + 1e-6 < cost) continue;
                    runtime.AdditionalCastReductionPercent = 0; runtime.CastReductionTicks = 0;
                    if (dynamic) Dispatch(new("before_cast", RuntimeCast(skill, stats)));
                    if (skill.CastTicks > 0) { casting = skill; castAt = tick + Math.Max(0, BalanceCombatMath.CastTimeTicks(skill.CastTicks, Stat(stats, "CAST_TIME_REDUCTION") + runtime.AdditionalCastReductionPercent) - runtime.CastReductionTicks); }
                    else Cast(skill, tick);
                    used = true; break;
                }
            if (!used && casting is null && normalAttacks && tick >= nextPlayer)
            {
                if (dynamic) { NormalAttack(false); }
                else
                {
                var stats = Stats(player, "player");
                if (tick - lastNormalTick >= 200) normalCount = 0;
                normalCount++; lastNormalTick = tick;
                var delay = (int)Math.Floor(Math.Max(0, Stat(stats, "NORMAL_ATTACK_DEGRADATION_DELAY")));
                var stage = classId.Equals("adventurer", StringComparison.OrdinalIgnoreCase) || normalCount < 5 + delay ? 0 : Math.Min(11, normalCount - (5 + delay) + 1);
                var hit = BalanceSkillDamage.Calculate(stats, Stats(enemy, "enemy"), weapon.Type, [new("NONE", 1)], "NORMAL_ATTACK",
                    BalanceCombatMath.DegradationDamageMultiplier(stage) * Multiplier("outgoing"), true, rng);
                var damage = ApplyEnemyDamage(hit.Damage, stats, 1, out _);
                var old = contributions.GetValueOrDefault("normal_attack", (weaponName, 0, 0));
                contributions["normal_attack"] = (weaponName, old.Uses + 1, old.Damage + damage);
                var cooldown = BalanceCombatMath.CooldownTicks(weapon.Ticks, Stat(stats, "COOLDOWN_REDUCTION"));
                nextPlayer = tick + Math.Max(1, BalanceCombatMath.AttackIntervalTicks(cooldown, Stat(stats, "ATTACK_SPEED") * BalanceCombatMath.DegradationSpeedMultiplier(stage)));
                }
            }
            ProcessPending(tick);
            if (enemyHp <= 0) { outcome = "victory"; elapsed = tick; break; }
            var blocked = conditions.ContainsKey("FROZEN") || tick < runtime.Resource("BOUND_UNTIL") || conditions.Values.Any(condition => condition.BlockedUntil > tick);
            var allyTarget = runtime.Allies.FirstOrDefault(ally => ally.Id == BalanceCombatData.Text(request["enemy"]?["targetAllyId"]));
            if (tick >= nextEnemy && !blocked && (dynamic ? (allyTarget is not null ? !allyTarget.Dead && (allyTarget.Position - runtime.EnemyPosition).LengthSquared <= enemyRange * enemyRange + 4
                : runtime.Distance * runtime.Distance <= enemyRange * enemyRange + 4) : enemyInRange))
            {
                var enemyStats = Stats(enemy, "enemy");
                if (allyTarget is not null)
                {
                    var hitAlly = tick < allyTarget.InvulnerableUntil ? 0 : BalanceSkillDamage.Calculate(enemyStats, allyTarget.Stats, enemyType, [new("NONE", 1)], "NORMAL_ATTACK",
                        conditions.ContainsKey("WEAKNESS") ? .5 : 1, false, rng).Damage;
                    if (hitAlly > 0 && allyTarget.Shield > 0)
                        allyTarget.Shield -= BalanceCombatMath.ShieldDamage(hitAlly, Stat(allyTarget.Stats, "MAX_HEALTH"), Stat(enemyStats, "SHIELD_BREAK"), 1, allyTarget.Shield);
                    else if (hitAlly > 0)
                    {
                        var before = hp;
                        hitAlly = runtime.AllyDamageInterceptor?.Invoke(allyTarget, hitAlly) ?? hitAlly;
                        allyTarget.Hp = Math.Max(0, allyTarget.Hp - Math.Max(0, hitAlly)); taken += Math.Max(0, before - hp);
                    }
                    nextEnemy = tick + enemyInterval;
                }
                else
                {
                var victimStats = Stats(player, "player");
                if (runtime.HasPassive("paladin_defense_conversion")) victimStats["DEFENSE"] = Stat(victimStats, "DEFENSE") + Stat(victimStats, "ATTACK") * .2;
                var incoming = BalanceSkillDamage.Calculate(enemyStats, victimStats, enemyType, [new("NONE", 1)], "NORMAL_ATTACK",
                    (conditions.ContainsKey("WEAKNESS") ? .5 : 1) * Multiplier("incoming"), false, rng);
                var hit = incoming.Damage;
                var incomingCast = new BalanceRuntimeCast("enemy_normal_attack", new() { ["id"] = "enemy_normal_attack" }, enemyStats);
                var before = new BalanceRuntimeHit(incomingCast, enemyType, "NORMAL_ATTACK", [new("NONE", 1)], incoming.PreDefenseDamage, 0, 0, !incoming.Landed, incoming.Critical, false);
                runtime.IncomingCancelled = tick < runtime.InvulnerableUntil; runtime.IncomingMultiplier = 1;
                runtime.IncomingDamage = hit; runtime.IncomingRawDamage = incoming.PreDefenseDamage;
                if (dynamic) Dispatch(new("before_incoming", Hit: before));
                hit = runtime.IncomingCancelled ? 0 : hit * runtime.IncomingMultiplier;
                var oldHp = hp; var oldShield = shield;
                if (shield > 0 && hit > 0) shield -= BalanceCombatMath.ShieldDamage(hit, Stat(player, "MAX_HEALTH"), Stat(enemy, "SHIELD_BREAK"), 1, shield);
                else { var actual = Math.Min(Math.Max(0, hp), hit); hp = Math.Max(0, hp - hit); taken += actual; }
                if (dynamic && !runtime.IncomingCancelled) Dispatch(new("after_incoming", Hit: before with { HpDamage = oldHp - hp, ShieldDamage = oldShield - shield, Killed = hp <= 0 }));
                runtime.IncomingDamage = 0; runtime.IncomingRawDamage = 0;
                nextEnemy = tick + enemyInterval;
                }
                if (hp <= 0 && dynamic) Dispatch(new("death"));
                if (hp <= 0) { outcome = "death"; elapsed = tick; break; }
            }
            if (tick % 20 == 0 || tick == maxTicks) Trace(tick);
        }
        }
        catch (BalanceRuntimeUnsupportedException ex)
        {
            diagnostics.Add(("runtime_condition_missing", ex.Message, "error")); outcome = "unsupported"; elapsed = currentTick;
        }
        if (resources.Count == 0 || BalanceCombatData.Integer(resources[^1]?["tick"]) != elapsed) Trace(elapsed);
        var rows = new JsonArray(contributions.Select(pair => (JsonNode?)new JsonObject
            { ["id"] = pair.Key, ["name"] = pair.Value.Name, ["uses"] = pair.Value.Uses, ["damage"] = pair.Value.Damage }).ToArray());
        var result = Result(outcome, elapsed, dealt, taken, elapsed > 0 ? dealt / elapsed * 20 : dealt * 20,
            mpExhausted, engExhausted, rows, player, enemy, resources, diagnostics, assumptions.Distinct(), catalog);
        result["skillTimeline"] = timeline;
        result["sources"] = new JsonArray(Sources.Concat(skills.Concat(passives).SelectMany(skill => skill.Profile.Sources)).Distinct()
            .Select(path => (JsonNode?)JsonValue.Create(path)).ToArray());
        return result;

        Dictionary<string, double> Stats(Dictionary<string, double> baseline, string target)
        {
            var copy = new Dictionary<string, double>(baseline, StringComparer.OrdinalIgnoreCase);
            foreach (var group in timed.Where(item => item.Effect.Kind == "stat" && item.Effect.Target == target).GroupBy(item => item.Effect.Status))
            {
                var flat = group.Where(item => item.Effect.Mode == "FLAT").Sum(item => item.Effect.Value);
                var scalar = group.Where(item => item.Effect.Mode == "SCALAR").Sum(item => item.Effect.Value);
                var final = group.Where(item => item.Effect.Mode == "FINAL_SCALAR").Aggregate(1.0, (value, item) => value * Math.Max(0, 1 + item.Effect.Value));
                copy[group.Key] = (Stat(baseline, group.Key) * (1 + scalar) + flat) * final;
            }
            return copy;
        }
        double Multiplier(string kind) => timed.Where(item => item.Effect.Kind == kind).Aggregate(1.0, (value, item) => value * item.Effect.Value);
        double Cost(Skill skill, Dictionary<string, double> stats) => skill.Resource == "GUARD" ? Math.Max(0, skill.Cost)
            : Math.Max(0, skill.Cost * (1 - Math.Max(0, Stat(stats, skill.Resource == "MANA" ? "MANA_COST_REDUCTION" : "ENERGY_COST_REDUCTION")) / 100));
        double ManaCost(Skill skill, Dictionary<string, double> stats) => skill.Resource == "MANA" ? Cost(skill, stats)
            : Math.Max(0, skill.AdditionalManaCost * (1 - Math.Max(0, Stat(stats, "MANA_COST_REDUCTION")) / 100));
        BalanceSkillView View(SkillCast cast) => new(cast.Stats, Stats(enemy, "enemy"), hp, enemyHp, mp, eng, shield, enemyShield,
            cast.Landed, cast.Critical, cast.Killed, conditions.Keys.ToHashSet(StringComparer.Ordinal), dynamic ? runtime.StrikeCircleCount : Math.Min(3, circles.Count), cast.Damage, cast.ShieldDamage);

        void Cast(Skill skill, int tick)
        {
            normalCount = 0;
            var stats = Stats(player, "player");
            var cast = new SkillCast(skill, stats) { PlayerPosition = runtime.PlayerPosition, Eye = runtime.Eye, Facing = runtime.Facing, EnemyPosition = runtime.EnemyPosition };
            if (dynamic && !skill.Profile.Runtime && !BalanceStaticGeometry.CanCast(runtime, skill.Definition))
            { RuntimeTrace(RuntimeCast(skill, stats), "cast_failed", 0, "対象なし"); return; }
            if (skill.Profile.Runtime)
            {
                var runtimeCast = RuntimeCast(skill, stats);
                var handler = runtimes.FirstOrDefault(handler => handler.Handles(skill.Profile.ImplementationId))
                    ?? throw new BalanceRuntimeUnsupportedException($"{skill.Id} の実行アダプターがありません。");
                var answer = handler.Cast(runtime, runtimeCast);
                RuntimeTrace(runtimeCast, answer.Success ? "cast_success" : "cast_failed", 0, "");
                if (!answer.Success && !answer.ConsumeCommon) return;
                var previous = contributions.GetValueOrDefault(skill.Id, (skill.Name, 0, 0));
                contributions[skill.Id] = (skill.Name, previous.Uses + 1, previous.Damage);
                ProcessPending(tick);
                mp = Math.Max(0, mp - ManaCost(skill, stats));
                if (skill.Resource == "ENERGY") eng = Math.Max(0, eng - Cost(skill, stats));
                if (skill.Resource == "GUARD") runtime.SetResource("GUARD", Math.Max(0, runtime.Resource("GUARD") - Cost(skill, stats)));
                ready[skill.Id] = tick + Math.Max(1, BalanceCombatMath.CooldownTicks(answer.CooldownTicks ?? skill.CooldownTicks, Stat(stats, "COOLDOWN_REDUCTION")));
                if (answer.Success) Dispatch(new("cast_completed", runtimeCast));
                return;
            }
            var snapshot = View(cast);
            var scopes = skill.Profile.Effects.Where(effect => effect.Scope.Length > 0).Select(effect => effect.Scope).Distinct().ToArray();
            foreach (var scope in scopes) scopeGenerations[scope] = scopeGenerations.GetValueOrDefault(scope) + 1;
            var old = contributions.GetValueOrDefault(skill.Id, (skill.Name, 0, 0));
            contributions[skill.Id] = (skill.Name, old.Uses + 1, old.Damage);
            foreach (var original in skill.Profile.Effects)
            {
                if (dynamic && original.FlightSpeed > 0) continue;
                token.ThrowIfCancellationRequested();
                if (original.EvaluateAtCast && original.When?.Invoke(snapshot) == false) continue;
                var effect = original.EvaluateAtCast ? original with { Value = original.Evaluate?.Invoke(snapshot) ?? original.Value,
                    Evaluate = null, When = null } : original;
                if (!dynamic && BalanceSkillDamage.OutOfRange(distance, halfDepth, effect)
                    && effect.Target != "player") continue;
                if (!dynamic && effect.Kind == "damage" && BalanceSkillDamage.OutOfRange(distance, halfDepth, effect)) continue;
                var at = tick + effect.Tick + BalanceSkillDamage.FlightTicks(distance, halfDepth, effect);
                if (!pending.TryGetValue(at, out var list)) pending[at] = list = [];
                list.Add(new(cast, effect, sequence++, effect.Scope.Length == 0 ? 0 : scopeGenerations[effect.Scope]));
            }
            if (dynamic)
                foreach (var group in skill.Profile.Effects.Where(effect => effect.FlightSpeed > 0)
                    .GroupBy(effect => (effect.Tick, effect.FlightSpeed, effect.HitRadius, effect.Range, effect.FlightOriginOffset)))
                {
                    var effects = group.ToArray(); var first = effects[0]; var runtimeCast = RuntimeCast(skill, stats);
                    runtime.Schedule(runtimeCast, first.Tick, () => world.Launch(runtimeCast,
                        new(first.Range, first.FlightSpeed, first.HitRadius,
                            runtime.Eye + runtime.Facing * first.FlightOriginOffset, runtime.Facing, first.AttackType, [],
                            Interceptable: skill.Profile.ImplementationId is "mage_fireball" or "mage_frost_ball" or "wizard_elemental_ball" or "wizard_prismatic_dart",
                            InterceptionComponents: effects.Where(effect => effect.Kind == "damage").Select(effect => new BalanceSkillComponent(effect.Element, effect.Value)).ToArray(),
                            OnCollision: _ => { foreach (var effect in effects) ApplyPending(new(cast, effect with { FlightSpeed = 0 }, sequence++, 0, true)); })));
                }
            // Plugin invokes executor.cast before it consumes the common resources/cooldown.
            ProcessPending(tick);
            mp = Math.Max(0, mp - ManaCost(skill, stats));
            if (skill.Resource == "ENERGY") eng = Math.Max(0, eng - Cost(skill, stats));
            ready[skill.Id] = tick + Math.Max(1, BalanceCombatMath.CooldownTicks(skill.CooldownTicks, Stat(stats, "COOLDOWN_REDUCTION")));
            if (dynamic) Dispatch(new("cast_completed", RuntimeCast(skill, stats)));
        }

        void ProcessPending(int tick)
        {
            var processed = 0;
            while (pending.ContainsKey(tick) || actions.ContainsKey(tick))
            {
                pending.Remove(tick, out var items); actions.Remove(tick, out var callbacks);
                var batch = (items ?? []).Select(item => (item.Order, Invoke: (Action)(() => ApplyPending(item))))
                    .Concat((callbacks ?? []).Where(item => item.Scope.Length == 0 || scopeGenerations.GetValueOrDefault(item.Scope) == item.Generation)
                        .Select(item => (item.Order, Invoke: item.Action))).OrderBy(item => item.Order).ToArray();
                foreach (var action in batch)
                {
                    if (++processed > 20_000) throw new BalanceRuntimeUnsupportedException("1tickの処理が20000件を超えました。");
                    token.ThrowIfCancellationRequested(); action.Invoke();
                }
            }
        }
        void ApplyPending(PendingEffect item)
        {
                var tick = currentTick;
                token.ThrowIfCancellationRequested();
                if (item.Effect.Scope.Length > 0 && scopeGenerations.GetValueOrDefault(item.Effect.Scope) != item.Generation) return;
                var view = View(item.Cast); var effect = item.Effect;
                if (effect.When?.Invoke(view) == false || effect.Trigger == "landed" && !view.LastLanded
                    || effect.Trigger == "critical" && !view.LastCritical || effect.Trigger == "killed" && !view.LastKilled) return;
                if (dynamic && !item.CollisionConfirmed && !BalanceStaticGeometry.Matches(runtime, item.Cast.Skill.Definition, effect,
                    item.Cast.PlayerPosition, item.Cast.Eye, item.Cast.Facing, item.Cast.EnemyPosition)) return;
                var value = effect.Evaluate?.Invoke(view) ?? effect.Value;
                if (!double.IsFinite(value)) throw new InvalidOperationException("スキル効果を有限数として計算できません。");
                var amount = value * (effect.Basis switch
                {
                    "maxHealth" => Stat(player, "MAX_HEALTH"), "currentHealth" => hp, "maxMana" => Stat(player, "MAX_MANA"),
                    "maxEnergy" => Stat(player, "MAX_ENERGY"), "maxShield" => Stat(player, "MAX_SHIELD"),
                    "lastShieldDamage" => view.LastShieldDamage, _ => 1
                });
                var damage = 0.0;
                switch (effect.Kind)
                {
                    case "damage":
                        if (enemyHp <= 0) break;
                        var components = effect.Components is null ? new[] { new BalanceSkillComponent(effect.Element, value) }
                            : effect.Components.Select(component => component with { Ratio = component.Ratio * (effect.Evaluate is null && !effect.EvaluateAtCast ? 1 : value) }).ToArray();
                        var hit = BalanceSkillDamage.Calculate(item.Cast.Stats, Stats(enemy, "enemy"), effect.AttackType, components, "SKILL", Multiplier("outgoing"), true, rng);
                        damage = ApplyEnemyDamage(hit.Damage, item.Cast.Stats, effect.ShieldMultiplier, out var shieldDamage);
                        item.Cast.Landed = hit.Landed && damage > 0; item.Cast.Critical = item.Cast.Landed && hit.Critical; item.Cast.Killed = enemyHp <= 0;
                        item.Cast.Damage = damage; item.Cast.ShieldDamage = shieldDamage;
                        Credit(item.Cast, damage);
                        if (dynamic) Dispatch(new("damage_applied", Hit: new(RuntimeCast(item.Cast.Skill, item.Cast.Stats), effect.AttackType, "SKILL", components,
                            hit.PreDefenseDamage, Math.Max(0, damage - shieldDamage), shieldDamage, !hit.Landed, hit.Critical, enemyHp <= 0)));
                        break;
                    case "heal":
                        var healing = Math.Max(effect.Minimum, amount) * (1 + Math.Max(0, Stat(Stats(player, "player"), "SUPPORT_POWER")) / 100)
                            * (1 + Math.Max(0, Stat(Stats(player, "player"), "HEALING_INCREASE")) / 100);
                        hp = Math.Min(Stat(Stats(player, "player"), "MAX_HEALTH"), hp + Math.Max(0, healing) * (runtime.Resource("PHOENIX_ACTIVE") > 0 ? .5 : 1)); break;
                    case "resource":
                        if (effect.Status == "MP") mp = Math.Clamp(mp + amount, 0, Math.Max(0, Stat(Stats(player, "player"), "MAX_MANA")));
                        else if (effect.Status == "ENG") eng = Math.Clamp(eng + amount, 0, Math.Max(0, Stat(Stats(player, "player"), "MAX_ENERGY")));
                        else if (effect.Status == "SHIELD") shield = Math.Clamp(shield + amount, 0, Math.Max(0, Stat(Stats(player, "player"), "MAX_SHIELD")));
                        else if (effect.Status == "HP") hp = Math.Clamp(hp + amount, 0, Math.Max(1, Stat(Stats(player, "player"), "MAX_HEALTH")));
                        else throw new InvalidOperationException($"未対応リソース {effect.Status}");
                        break;
                    case "stat": case "outgoing": case "incoming":
                        var key = item.Cast.Skill.Profile.ImplementationId + ":" + effect.Kind + ":" + effect.Status;
                        timed.RemoveAll(existing => existing.Key == key);
                        timed.Add(new(key, effect with { Value = value }, tick + effect.DurationTicks)); break;
                    case "circle": circles[effect.Status] = tick + effect.DurationTicks + 1; break;
                    case "condition": if (enemyHp > 0) ApplyCondition(item.Cast, effect, tick); break;
                    default: throw new InvalidOperationException($"未対応効果 {effect.Kind}");
                }
                if (timeline.Count < 2_000) timeline.Add(new JsonObject { ["tick"] = tick, ["skillId"] = item.Cast.Skill.Id,
                    ["kind"] = effect.Kind, ["value"] = value, ["damage"] = damage, ["landed"] = item.Cast.Landed, ["critical"] = item.Cast.Critical });
        }
        BalanceRuntimeCast RuntimeCast(Skill skill, IReadOnlyDictionary<string, double> stats)
            => new(skill.Id, skill.Definition, new Dictionary<string, double>(stats, StringComparer.OrdinalIgnoreCase), sequence++);
        void Dispatch(BalanceRuntimeEvent value)
        {
            if (value.Kind == "clear_negative_effects")
            {
                runtime.PlayerConditions.Clear();
                timed.RemoveAll(item => item.Effect.Target == "player" && (item.Effect.Kind == "stat" && item.Effect.Value < 0
                    || item.Effect.Kind == "outgoing" && item.Effect.Value < 1 || item.Effect.Kind == "incoming" && item.Effect.Value > 1));
            }
            foreach (var handler in runtimes) handler.OnEvent(runtime, value);
            if (value.Kind == "input" && value.Input is { } input)
            {
                var kind = BalanceCombatData.Text(input["kind"]);
                if (kind == "normal_attack" && currentTick >= nextPlayer && casting is null) NormalAttack(false);
                if (kind == "dodge" && runtime.OnGround && casting is null && hp > 0)
                {
                    var free = runtime.State.GetValueOrDefault("spell_step_free_until") is int until && until > currentTick;
                    if (free) runtime.State.Remove("spell_step_free_until");
                    if (free || eng >= 15)
                    {
                        if (!free) eng -= 15;
                        world.Velocity("player", runtime.Facing.Horizontal.Normalized + new BalanceVector(0, .15, 0));
                        runtime.Dodging = true;
                        Dispatch(new("dodge", Input: input));
                        var marker = runtime.Snapshot(definitions.Values.FirstOrDefault() ?? new JsonObject { ["id"] = "dodge" });
                        runtime.Schedule(marker, 8, () => runtime.Dodging = false);
                    }
                }
            }
        }
        int ClearNegative()
        {
            var count = runtime.PlayerConditions.Count;
            runtime.PlayerConditions.Clear();
            count += timed.RemoveAll(item => item.Effect.Target == "player" && (item.Effect.Kind == "stat" && item.Effect.Value < 0
                || item.Effect.Kind == "outgoing" && item.Effect.Value < 1 || item.Effect.Kind == "incoming" && item.Effect.Value > 1));
            return count;
        }
        void AddTimed(string key, BalanceSkillEffect effect, int duration)
        {
            timed.RemoveAll(item => item.Key == key);
            if (duration > 0) timed.Add(new(key, effect, (int)Math.Min(int.MaxValue, (long)currentTick + duration)));
        }
        double ReadResource(string key) => key switch
        { "HP" => hp, "MP" => mp, "ENG" => eng, "SHIELD" => shield, "ENEMY_HP" => enemyHp, "ENEMY_SHIELD" => enemyShield, _ => 0 };
        void WriteResource(string key, double value)
        {
            var stats = Stats(player, "player");
            switch (key)
            {
                case "HP": hp = Math.Clamp(value, 0, Math.Max(1, Stat(stats, "MAX_HEALTH"))); break;
                case "MP": mp = Math.Clamp(value, 0, Math.Max(0, Stat(stats, "MAX_MANA"))); break;
                case "ENG": eng = Math.Clamp(value, 0, Math.Max(0, Stat(stats, "MAX_ENERGY"))); break;
                case "SHIELD": shield = Math.Clamp(value, 0, Math.Max(0, Stat(stats, "MAX_SHIELD"))); break;
                case "ENEMY_HP": enemyHp = Math.Clamp(value, 0, Math.Max(1, Stat(Stats(enemy, "enemy"), "MAX_HEALTH"))); break;
                case "ENEMY_SHIELD": enemyShield = Math.Max(0, value); break;
            }
        }
        void Recover(string resource, double value)
        {
            if (runtime.PlayerConditions.Contains("HEALING_INHIBITION")) return;
            if (resource == "SHIELD")
            {
                if (runtime.HasPassive("swordsman_shield_activate")) WriteResource(resource, ReadResource(resource) + Math.Max(0, value));
                return;
            }
            var amount = value * (1 + Math.Max(0, Stat(Stats(player, "player"), "SUPPORT_POWER")) / 100);
            WriteResource(resource, ReadResource(resource) + amount);
        }
        double Heal(BalanceRuntimeCast cast, double amount)
        {
            if (runtime.PlayerConditions.Contains("HEALING_INHIBITION")) return 0;
            var stats = Stats(player, "player");
            var healing = HealingAmount(cast, amount, stats, buffUntil.GetValueOrDefault("archer_heal_arrow_alpha") > currentTick,
                cast.ImplementationId is not ("archer_heal_arrow_alpha" or "wizard_self_heal"));
            var before = hp; hp = Math.Min(Stat(stats, "MAX_HEALTH"), hp + healing); return Math.Max(0, hp - before);
        }
        double HealingAmount(BalanceRuntimeCast cast, double amount, IReadOnlyDictionary<string, double> target, bool alpha, bool followUp)
        {
            if (amount <= 0) return 0;
            var supported = amount * (1 + Math.Max(0, Stat(Stats(player, "player"), "SUPPORT_POWER")) / 100);
            if (alpha && followUp) supported += Stat(target, "MAX_HEALTH") * .01;
            return supported * (1 + Math.Max(0, Stat(target, "HEALING_INCREASE")) / 100) * (runtime.Resource("PHOENIX_ACTIVE") > 0 ? .5 : 1);
        }
        double HealAlly(BalanceRuntimeCast cast, BalanceRuntimeAlly ally, double amount)
        {
            if (ally.Dead || ally.Conditions.Contains("HEALING_INHIBITION")) return 0;
            var before = ally.Hp; ally.Hp = Math.Min(Stat(ally.Stats, "MAX_HEALTH"), ally.Hp + HealingAmount(cast, amount, ally.Stats,
                buffUntil.GetValueOrDefault("ally:" + ally.Id + ":archer_heal_arrow_alpha") > currentTick, true));
            return Math.Max(0, ally.Hp - before);
        }
        void RecoverAlly(BalanceRuntimeAlly ally, string resource, double amount)
        {
            if (ally.Dead || ally.Conditions.Contains("HEALING_INHIBITION")) return;
            var supported = Math.Max(0, amount) * (1 + Math.Max(0, Stat(Stats(player, "player"), "SUPPORT_POWER")) / 100);
            if (resource == "MP") ally.Mp = Math.Min(Stat(ally.Stats, "MAX_MANA"), ally.Mp + supported);
            if (resource == "MP_FIXED") ally.Mp = Math.Min(Stat(ally.Stats, "MAX_MANA"), ally.Mp + Math.Max(0, amount));
            if (resource == "ENG") ally.Eng = Math.Min(Stat(ally.Stats, "MAX_ENERGY"), ally.Eng + supported);
            if (resource == "ENG_SELF") ally.Eng = Math.Min(Stat(ally.Stats, "MAX_ENERGY"), ally.Eng + Math.Max(0, amount) * (1 + Math.Max(0, Stat(ally.Stats, "SUPPORT_POWER")) / 100));
            if (resource == "SHIELD" && ally.ShieldEnabled) ally.Shield = Math.Min(Stat(ally.Stats, "MAX_SHIELD"), ally.Shield + Math.Max(0, amount));
        }
        bool ConsumeResources(BalanceRuntimeCast cast)
        {
            var definition = cast.Definition; var resource = BalanceCombatData.Text(definition["resourceType"]);
            var cost = Math.Max(0, BalanceCombatData.Number(definition["resourceCost"], BalanceCombatData.Number(definition["manaCost"])));
            var stats = Stats(player, "player");
            var amount = resource == "GUARD" ? cost : cost * Math.Max(0, 1 - Math.Max(0, Stat(stats, resource == "MANA" ? "MANA_COST_REDUCTION" : "ENERGY_COST_REDUCTION")) / 100);
            var mana = resource == "MANA" ? amount : BalanceCombatData.Number(definition["manaCost"]) * Math.Max(0, 1 - Math.Max(0, Stat(stats, "MANA_COST_REDUCTION")) / 100);
            if (mp + 1e-6 < mana || resource == "ENERGY" && eng + 1e-6 < amount || resource == "GUARD" && runtime.Resource("GUARD") + 1e-6 < amount) return false;
            mp = Math.Max(0, mp - mana);
            if (resource == "ENERGY") eng = Math.Max(0, eng - amount);
            if (resource == "GUARD") runtime.AddResource("GUARD", -amount);
            return true;
        }
        bool ApplyBuff(BalanceRuntimeCast cast, string id) => ApplyBuffTo(cast, id, "player");
        bool ApplyBuffTo(BalanceRuntimeCast cast, string id, string target)
        {
            id = BalanceCombatData.Id(JsonValue.Create(id));
            var matches = BalanceCombatData.Masters(catalog).Where(master => master.Kind == "buff" && BalanceCombatData.Text(master.Content["id"]) == id).ToArray();
            if (matches.Length != 1) throw new BalanceRuntimeUnsupportedException($"参照Buff {id} の定義を一意に取得できません。");
            var definition = matches[0].Content;
            var knownFields = new[] { "schemaVersion", "id", "type", "name", "lore", "icon", "durationTicks", "isDebuff", "resetOnChallenge", "stackGroup", "modifiers" };
            if (definition.Select(pair => pair.Key).Any(key => !knownFields.Contains(key, StringComparer.Ordinal)) || BalanceCombatData.Text(definition["type"]) != "BUFF"
                || definition["modifiers"] is not JsonArray)
                throw new BalanceRuntimeUnsupportedException($"参照Buff {id} の追加効果・形式は未検証です。");
            var duration = BalanceCombatData.Integer(definition["durationTicks"]);
            if (duration < 1 || duration > 72_000) throw new BalanceRuntimeUnsupportedException($"参照Buff {id} の持続時間が不正です。");
            RemoveBuffFrom(id, target); var buffKey = target == "player" ? id : target + ":" + id; buffUntil[buffKey] = currentTick + duration;
            var stackGroup = BalanceCombatData.Text(definition["stackGroup"]).Trim().ToLowerInvariant();
            if (stackGroup.Length > 0)
            {
                var groupKey = target + ":" + stackGroup;
                if (buffGroups.TryGetValue(groupKey, out var previousId) && previousId != id) RemoveBuffFrom(previousId, target);
                buffGroups[groupKey] = id;
            }
            foreach (var (node, index) in BalanceCombatData.Array(definition["modifiers"]).Select((value, index) => (value, index)))
            {
                if (node is not JsonObject modifier || modifier.Select(pair => pair.Key).Any(key => key is not ("status" or "type" or "value")))
                    throw new BalanceRuntimeUnsupportedException($"参照Buff {id} に未知のmodifierがあります。");
                var status = BalanceCombatData.Text(modifier["status"]); var mode = BalanceCombatData.Text(modifier["type"]);
                if (!SupportedStatuses.Contains(status) || mode is not ("FLAT" or "SCALAR" or "FINAL_SCALAR"))
                    throw new BalanceRuntimeUnsupportedException($"参照Buff {id} の効果 {status}/{mode} は未対応です。");
                AddTimed("buff:" + buffKey + ":" + index + ":" + status, new("stat", Status: status, Value: BalanceSkillParameters.Number(modifier, "value"), Mode: mode, Target: target), duration);
            }
            foreach (var ally in runtime.Allies.Where(ally => target == "ally:" + ally.Id))
            { var values = Stats(ally.BaseStats, target); ally.Stats.Clear(); foreach (var (key, value) in values) ally.Stats[key] = value; }
            RuntimeTrace(cast, "buff", duration, id); return true;
        }
        void RemoveBuff(string id) => RemoveBuffFrom(id, "player");
        void RemoveBuffFrom(string id, string target) { var key = target == "player" ? id : target + ":" + id; buffUntil.Remove(key); timed.RemoveAll(item => item.Key.StartsWith("buff:" + key + ":", StringComparison.Ordinal)); }
        BalanceRuntimeHit RuntimeHit(BalanceRuntimeCast cast, string type, IReadOnlyList<BalanceSkillComponent> components,
            string source, double shieldMultiplier, bool secondary, IReadOnlyDictionary<string, double>? victimOverride)
        {
            if (enemyHp <= 0) return new(cast, type, source, components, 0, 0, 0, false, false, true, secondary);
            var hit = BalanceSkillDamage.Calculate(cast.Stats, victimOverride ?? Stats(enemy, "enemy"), type, components, source,
                Multiplier("outgoing"), true, rng, cast.ResolvedAttackPower, cast.SuperCriticalChance, cast.AdditiveElements);
            double damage; double shieldDamage;
            if (cast.ShieldBreakRatio is { } ratio && enemyShield > 0 && hit.Damage > 0)
            { shieldDamage = Math.Min(enemyShield, Math.Max(0, Stat(cast.Stats, "SHIELD_BREAK")) * ratio); enemyShield -= shieldDamage; dealt += shieldDamage; damage = shieldDamage; }
            else damage = ApplyEnemyDamage(hit.Damage, cast.Stats, shieldMultiplier, out shieldDamage);
            var result = new BalanceRuntimeHit(cast, type, source, components, hit.PreDefenseDamage, Math.Max(0, damage - shieldDamage), shieldDamage,
                !hit.Landed, hit.Critical, enemyHp <= 0, secondary);
            var name = BalanceCombatData.Text(cast.Definition["name"]); if (name.Length == 0) name = cast.Id;
            var previous = contributions.GetValueOrDefault(cast.Id, (name, 0, 0));
            contributions[cast.Id] = (name, previous.Uses, previous.Damage + damage);
            RuntimeTrace(cast, source == "NORMAL_ATTACK" ? "normal_hit" : "damage", damage, type);
            Dispatch(new("damage_applied", cast, result));
            if (source == "NORMAL_ATTACK") Dispatch(new("normal_landed", cast, result));
            return result;
        }
        bool RuntimeCondition(BalanceRuntimeCast cast, string name, double chance, int duration, double strength)
        {
            if (enemyHp <= 0) return false;
            var skill = ResolveSkill(cast.Definition, catalog) ?? throw new BalanceRuntimeUnsupportedException($"{cast.Id} の状態異常元を解決できません。");
            var temporary = new SkillCast(skill, new Dictionary<string, double>(cast.Stats, StringComparer.OrdinalIgnoreCase));
            var before = conditions.GetValueOrDefault(name)?.Until ?? 0;
            ApplyCondition(temporary, new("condition", Status: name, Chance: chance, DurationTicks: duration,
                AttackType: cast.ConditionAttackType), currentTick);
            return (conditions.GetValueOrDefault(name)?.Until ?? 0) > before;
        }
        void RuntimeTrace(BalanceRuntimeCast cast, string kind, double value, string detail)
        {
            if (timeline.Count < 2_000) timeline.Add(new JsonObject { ["tick"] = currentTick, ["skillId"] = cast.Id,
                ["kind"] = kind, ["value"] = value, ["damage"] = kind is "damage" or "normal_hit" ? value : 0, ["detail"] = detail });
        }
        void NormalAttack(bool extra)
        {
            if (hp <= 0 || enemyHp <= 0) return;
            if (!extra && runtime.State.GetValueOrDefault("normal.cooldownUntil") is int until && currentTick < until) { nextPlayer = until; return; }
            runtime.State.Remove("normal.cancelled");
            var stats = Stats(player, "player");
            var definition = new JsonObject { ["id"] = "normal_attack", ["implementationId"] = "normal_attack", ["name"] = weaponName,
                ["tags"] = new JsonArray(weapon.Type.ToLowerInvariant()) };
            var cast = new BalanceRuntimeCast("normal_attack", definition, stats, sequence++);
            Dispatch(new("normal_attempt", cast));
            if (runtime.State.GetValueOrDefault("normal.cancelled") is true) { nextPlayer = currentTick + 1; return; }
            if (currentTick - lastNormalTick >= 200) normalCount = 0;
            normalCount++; lastNormalTick = currentTick;
            var delay = (int)Math.Floor(Math.Max(0, Stat(stats, "NORMAL_ATTACK_DEGRADATION_DELAY")));
            var stage = classId == "adventurer" || normalCount < 5 + delay ? 0 : Math.Min(11, normalCount - (5 + delay) + 1);
            runtime.NormalDamageMultiplier = 1; runtime.NormalDamageComponents.Clear(); runtime.NormalDamageComponents.Add(new("NONE", 1));
            runtime.NormalAdditiveElements.Clear(); runtime.NormalDirections.Clear(); runtime.NormalDirections.Add(runtime.Facing); runtime.NormalImpact = null;
            Dispatch(new("normal_launch", cast));
            var components = runtime.NormalDamageComponents.Select(component => component with { Ratio = component.Ratio * runtime.NormalDamageMultiplier * BalanceCombatMath.DegradationDamageMultiplier(stage) }).ToArray();
            cast = cast with { AdditiveElements = runtime.NormalAdditiveElements.ToHashSet(StringComparer.Ordinal) };
            var impact = runtime.NormalImpact; var first = false;
            void First(BalanceVector location, BalanceRuntimeHit? hit) { if (first) return; first = true; impact?.Invoke(location, hit); }
            var previous = contributions.GetValueOrDefault("normal_attack", (weaponName, 0, 0));
            contributions["normal_attack"] = (weaponName, previous.Uses + 1, previous.Damage);
            if (weapon.Type == "MELEE")
            {
                var spear = weaponTag == "SPEAR";
                var origin = runtime.Eye + runtime.Facing * (weaponTag == "HAMMER" ? 1.25 : spear ? 1 : 1.05) + new BalanceVector(0, weaponTag == "HAMMER" ? -.25 : spear ? -.12 : -.15, 0);
                var step = spear ? .35 : weaponTag == "HAMMER" ? .7 : .6; var range = spear ? 5.5 : weaponTag == "HAMMER" ? 3 : 2.45;
                var radius = spear ? .45 : weaponTag == "HAMMER" ? 1.2 : .85;
                var target = Enumerable.Range(0, (int)Math.Ceiling(range / step) + 1).Any(index =>
                    BalanceRuntimeGeometry.SegmentHitsBox(origin + runtime.Facing * (step * index), origin + runtime.Facing * (step * index), runtime.EnemyPosition, runtime.EnemyHalfWidth, runtime.EnemyHeight, radius, out _));
                if (target) for (var index = 0; index < (spear ? 3 : 1); index++)
                    runtime.Schedule(cast, index * 2, () => { var hit = RuntimeHit(cast, weapon.Type, components.Select(component => component with { Ratio = component.Ratio * (spear ? .34 : 1) }).ToArray(), "NORMAL_ATTACK", 1, false, null); First(origin, hit); });
            }
            else foreach (var direction in runtime.NormalDirections.ToArray())
            {
                BalanceRuntimeHit? last = null;
                var bow = runtime.HasBow;
                var speed = weaponTag switch { "SHORTBOW" => 1.8, "LONGBOW" => 1, "BOW" => 1.35, "WAND" => 1.45, _ => 1.3 };
                var range = weaponTag switch { "SHORTBOW" => 10.5, "LONGBOW" => 23.1, "BOW" => 15.75, _ => 9 };
                world.Launch(cast, new(range, speed, .75, runtime.Eye + direction * (bow ? 1.2 : 1) + new BalanceVector(0, bow ? -.1 : -.05, 0), direction, weapon.Type, components,
                    Gravity: bow ? weaponTag == "LONGBOW" ? .02 : .04 : 0, Interceptable: false,
                    OnHit: hit => last = hit, OnEnd: (location, reason) => { if (reason is "ENTITY" or "BLOCK") First(location, last); },
                    PointCollision: true, HomingStrength: bow ? 0 : .18, HomingRange: bow ? 0 : 4.75));
            }
            var cooldown = BalanceCombatMath.CooldownTicks(weapon.Ticks, Stat(stats, "COOLDOWN_REDUCTION"));
            if (!extra) nextPlayer = currentTick + Math.Max(1, BalanceCombatMath.AttackIntervalTicks(cooldown, Stat(stats, "ATTACK_SPEED") * BalanceCombatMath.DegradationSpeedMultiplier(stage)));
        }
        double ApplyEnemyDamage(double value, IReadOnlyDictionary<string, double> attacker, double shieldMultiplier, out double shieldDamage)
        {
            shieldDamage = 0;
            if (enemyShield > 0 && value > 0)
            {
                shieldDamage = BalanceCombatMath.ShieldDamage(value, Stat(enemy, "MAX_HEALTH"), Stat(attacker, "SHIELD_BREAK"), shieldMultiplier, enemyShield);
                enemyShield -= shieldDamage; dealt += shieldDamage; return shieldDamage;
            }
            var actual = Math.Min(Math.Max(0, enemyHp), Math.Max(0, value)); enemyHp = Math.Max(0, enemyHp - value); dealt += actual; return actual;
        }
        void Credit(SkillCast cast, double value)
        {
            var old = contributions.GetValueOrDefault(cast.Skill.Id, (cast.Skill.Name, 0, 0));
            contributions[cast.Skill.Id] = (cast.Skill.Name, old.Uses, old.Damage + value);
        }
        void Trace(int tick) => resources.Add(new JsonObject { ["tick"] = tick, ["hp"] = hp, ["mp"] = mp, ["eng"] = eng,
            ["shield"] = shield, ["enemyHp"] = enemyHp, ["enemyShield"] = enemyShield, ["circleCount"] = dynamic ? runtime.CircleCount : Math.Min(3, circles.Count),
            ["custom"] = new JsonObject(runtime.CustomResources.Select(pair => new KeyValuePair<string, JsonNode?>(pair.Key, JsonValue.Create(pair.Value)))),
            ["allies"] = new JsonArray(runtime.Allies.Select(ally => (JsonNode?)new JsonObject { ["id"] = ally.Id, ["hp"] = ally.Hp, ["mp"] = ally.Mp, ["shield"] = ally.Shield }).ToArray()),
            ["playerPosition"] = BalanceRuntimeWorld.Json(runtime.PlayerPosition), ["enemyPosition"] = BalanceRuntimeWorld.Json(runtime.EnemyPosition) });

        void ApplyCondition(SkillCast cast, BalanceSkillEffect effect, int tick)
        {
            var name = effect.Status;
            var chance = Math.Clamp(effect.Chance * (1 + Stat(cast.Stats, name + "_APPLY_CHANCE") / 100)
                * (1 - Stat(Stats(enemy, "enemy"), name + "_RESISTANCE") / 100), 0, 100);
            if (chance < 100 && rng.NextDouble() * 100 >= chance) return;
            var duration = effect.DurationTicks > 0 ? effect.DurationTicks : name == "FROZEN" ? 40 : 100;
            duration = (int)Math.Floor(duration * (1 + Math.Max(0, Stat(cast.Stats, "CONDITION_DURATION_INCREASE")) / 100) + .5);
            if (BalanceCombatData.Text(mob["category"]) == "BOSS" && name is "FROZEN" or "CHILLED" or "BLINDNESS") duration = (int)Math.Floor(duration * .25 + .5);
            if (duration < 1) return;
            var typed = effect.AttackType switch { "MELEE" => "MELEE_ATTACK", "RANGED" => "RANGED_ATTACK", _ => "MAGIC_ATTACK" };
            var primary = effect.AttackType switch { "MELEE" => "STRENGTH", "RANGED" => "DEXTERITY", _ => "INTELLIGENCE" };
            var power = BalanceCombatMath.AttackPower(Stat(cast.Stats, "ATTACK"), Stat(cast.Stats, typed), Stat(cast.Stats, primary))
                * (name == "BURNING" ? .2 : name == "SHOCKED" ? .1 : 0);
            if (conditions.TryGetValue(name, out var existing))
            {
                existing.Until = Math.Max(existing.Until, tick + duration);
                if (power > existing.Power) { existing.Power = power; existing.Cast = cast; existing.NextDot = tick + 20; }
            }
            else conditions[name] = new(name, tick + duration, power, tick + 20,
                name == "SHOCKED" ? tick + rng.Next(16, 33) : int.MaxValue, cast);
            runtime.EnemyConditions.Add(name);
            if (dynamic) Dispatch(new("condition_applied", RuntimeCast(cast.Skill, cast.Stats), Condition: name));
        }
        void ProcessConditions(int tick)
        {
            foreach (var condition in conditions.Values)
            {
                if (condition.Name == "SHOCKED" && condition.NextControl <= tick)
                { condition.BlockedUntil = tick + 6; condition.NextControl = tick + rng.Next(16, 33); }
                if (condition.Power <= 0 || condition.NextDot > tick || enemyHp <= 0) continue;
                var stats = condition.Cast.Stats; var victim = Stats(enemy, "enemy"); var name = condition.Name;
                var damage = condition.Power * Math.Max(0, 1 + Stat(stats, name + "_DAMAGE_INCREASE") / 100)
                    * Math.Max(0, 1 - (Stat(victim, name + "_DAMAGE_RESISTANCE") - Stat(stats, name + "_DAMAGE_PENETRATION")) / 100)
                    * Multiplier("outgoing") * Math.Max(0, Stat(stats, "FINAL_DAMAGE_MULTIPLIER", 100)) / 100;
                var actual = Math.Min(Math.Max(0, enemyHp), damage); enemyHp = Math.Max(0, enemyHp - damage); dealt += actual;
                Credit(condition.Cast, actual); condition.NextDot = tick + 20;
                if (timeline.Count < 2_000) timeline.Add(new JsonObject { ["tick"] = tick, ["skillId"] = condition.Cast.Skill.Id,
                    ["kind"] = "condition_tick", ["condition"] = name, ["damage"] = actual });
            }
        }
    }

    private static double Stat(IReadOnlyDictionary<string, double> values, string key, double fallback = 0) => BalanceCombatData.Stat(values, key, fallback);
}
