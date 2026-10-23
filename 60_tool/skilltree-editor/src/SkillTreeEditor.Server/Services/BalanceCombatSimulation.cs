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
    }
    private sealed record PendingEffect(SkillCast Cast, BalanceSkillEffect Effect, int Order, int Generation);
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
        JsonObject catalog, JsonObject build, CancellationToken token)
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
        assumptions.Add($"敵は正面中心線の{distance}ブロック先、奥行き半幅{halfDepth}の固定当たり箱です。投射は遮蔽・重力なしの対応弾だけを扱います。");
        assumptions.Add($"通常攻撃: {(normalAttacks ? "使用" : "使用しない")}。開始時の自盾={shield}。自盾の自動再充填は行いません。");
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

        for (var tick = 0; tick <= maxTicks; tick++)
        {
            currentTick = tick; token.ThrowIfCancellationRequested();
            timed.RemoveAll(item => item.Until <= tick);
            foreach (var key in circles.Where(pair => pair.Value <= tick).Select(pair => pair.Key).ToArray()) circles.Remove(key);
            foreach (var key in conditions.Where(pair => pair.Value.Until <= tick).Select(pair => pair.Key).ToArray()) conditions.Remove(key);
            ProcessConditions(tick);
            if (enemyHp <= 0) { outcome = "victory"; elapsed = tick; break; }
            if (tick > 0 && tick % 20 == 0)
            {
                var stats = Stats(player, "player");
                var support = 1 + Math.Max(0, Stat(stats, "SUPPORT_POWER")) / 100;
                hp = Math.Min(Stat(stats, "MAX_HEALTH"), hp + Stat(stats, "HP_REGEN") / 5 * support * (1 + Math.Max(0, Stat(stats, "HEALING_INCREASE")) / 100));
                mp = Math.Min(Stat(stats, "MAX_MANA"), mp + Stat(stats, "MP_REGEN") / 5 * support);
                eng = Math.Min(Stat(stats, "MAX_ENERGY"), eng + Stat(stats, "ENERGY_REGEN") / 5 * support);
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
                    if (skill.CastTicks > 0) { casting = skill; castAt = tick + BalanceCombatMath.CastTimeTicks(skill.CastTicks, Stat(stats, "CAST_TIME_REDUCTION")); }
                    else Cast(skill, tick);
                    used = true; break;
                }
            if (!used && casting is null && normalAttacks && tick >= nextPlayer)
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
            if (enemyHp <= 0) { outcome = "victory"; elapsed = tick; break; }
            var blocked = conditions.ContainsKey("FROZEN") || conditions.Values.Any(condition => condition.BlockedUntil > tick);
            if (tick >= nextEnemy && !blocked && enemyInRange)
            {
                var enemyStats = Stats(enemy, "enemy");
                var hit = BalanceSkillDamage.Calculate(enemyStats, Stats(player, "player"), enemyType, [new("NONE", 1)], "NORMAL_ATTACK",
                    (conditions.ContainsKey("WEAKNESS") ? .5 : 1) * Multiplier("incoming"), false, rng).Damage;
                if (shield > 0 && hit > 0) shield -= BalanceCombatMath.ShieldDamage(hit, Stat(player, "MAX_HEALTH"), Stat(enemy, "SHIELD_BREAK"), 1, shield);
                else { var actual = Math.Min(Math.Max(0, hp), hit); hp = Math.Max(0, hp - hit); taken += actual; }
                nextEnemy = tick + enemyInterval;
                if (hp <= 0) { outcome = "death"; elapsed = tick; break; }
            }
            if (tick % 20 == 0 || tick == maxTicks) Trace(tick);
        }
        if (resources.Count == 0 || BalanceCombatData.Integer(resources[^1]?["tick"]) != elapsed) Trace(elapsed);
        var rows = new JsonArray(contributions.Select(pair => (JsonNode?)new JsonObject
            { ["id"] = pair.Key, ["name"] = pair.Value.Name, ["uses"] = pair.Value.Uses, ["damage"] = pair.Value.Damage }).ToArray());
        var result = Result(outcome, elapsed, dealt, taken, elapsed > 0 ? dealt / elapsed * 20 : dealt * 20,
            mpExhausted, engExhausted, rows, player, enemy, resources, diagnostics, assumptions.Distinct(), catalog);
        result["skillTimeline"] = timeline;
        result["sources"] = new JsonArray(Sources.Concat(skills.SelectMany(skill => skill.Profile.Sources)).Distinct()
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
        double Cost(Skill skill, Dictionary<string, double> stats) => Math.Max(0, skill.Cost * (1 - Math.Max(0, Stat(stats, skill.Resource == "MANA" ? "MANA_COST_REDUCTION" : "ENERGY_COST_REDUCTION")) / 100));
        double ManaCost(Skill skill, Dictionary<string, double> stats) => skill.Resource == "MANA" ? Cost(skill, stats)
            : Math.Max(0, skill.AdditionalManaCost * (1 - Math.Max(0, Stat(stats, "MANA_COST_REDUCTION")) / 100));
        BalanceSkillView View(SkillCast cast) => new(cast.Stats, Stats(enemy, "enemy"), hp, enemyHp, mp, eng, shield, enemyShield,
            cast.Landed, cast.Critical, cast.Killed, conditions.Keys.ToHashSet(StringComparer.Ordinal), Math.Min(3, circles.Count), cast.Damage, cast.ShieldDamage);

        void Cast(Skill skill, int tick)
        {
            normalCount = 0;
            var stats = Stats(player, "player");
            var cast = new SkillCast(skill, stats);
            var snapshot = View(cast);
            var scopes = skill.Profile.Effects.Where(effect => effect.Scope.Length > 0).Select(effect => effect.Scope).Distinct().ToArray();
            foreach (var scope in scopes) scopeGenerations[scope] = scopeGenerations.GetValueOrDefault(scope) + 1;
            var old = contributions.GetValueOrDefault(skill.Id, (skill.Name, 0, 0));
            contributions[skill.Id] = (skill.Name, old.Uses + 1, old.Damage);
            foreach (var original in skill.Profile.Effects)
            {
                token.ThrowIfCancellationRequested();
                if (original.EvaluateAtCast && original.When?.Invoke(snapshot) == false) continue;
                var effect = original.EvaluateAtCast ? original with { Value = original.Evaluate?.Invoke(snapshot) ?? original.Value,
                    Evaluate = null, When = null } : original;
                if (BalanceSkillDamage.OutOfRange(distance, halfDepth, effect)
                    && effect.Target != "player") continue;
                if (effect.Kind == "damage" && BalanceSkillDamage.OutOfRange(distance, halfDepth, effect)) continue;
                var at = tick + effect.Tick + BalanceSkillDamage.FlightTicks(distance, halfDepth, effect);
                if (!pending.TryGetValue(at, out var list)) pending[at] = list = [];
                list.Add(new(cast, effect, sequence++, effect.Scope.Length == 0 ? 0 : scopeGenerations[effect.Scope]));
            }
            // Plugin invokes executor.cast before it consumes the common resources/cooldown.
            ProcessPending(tick);
            mp = Math.Max(0, mp - ManaCost(skill, stats));
            if (skill.Resource == "ENERGY") eng = Math.Max(0, eng - Cost(skill, stats));
            ready[skill.Id] = tick + Math.Max(1, BalanceCombatMath.CooldownTicks(skill.CooldownTicks, Stat(stats, "COOLDOWN_REDUCTION")));
        }

        void ProcessPending(int tick)
        {
            if (!pending.Remove(tick, out var items)) return;
            foreach (var item in items.OrderBy(item => item.Order))
            {
                token.ThrowIfCancellationRequested();
                if (item.Effect.Scope.Length > 0 && scopeGenerations.GetValueOrDefault(item.Effect.Scope) != item.Generation) continue;
                var view = View(item.Cast); var effect = item.Effect;
                if (effect.When?.Invoke(view) == false || effect.Trigger == "landed" && !view.LastLanded
                    || effect.Trigger == "critical" && !view.LastCritical || effect.Trigger == "killed" && !view.LastKilled) continue;
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
                        break;
                    case "heal":
                        var healing = Math.Max(effect.Minimum, amount) * (1 + Math.Max(0, Stat(Stats(player, "player"), "SUPPORT_POWER")) / 100)
                            * (1 + Math.Max(0, Stat(Stats(player, "player"), "HEALING_INCREASE")) / 100);
                        hp = Math.Min(Stat(Stats(player, "player"), "MAX_HEALTH"), hp + Math.Max(0, healing)); break;
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
            ["shield"] = shield, ["enemyHp"] = enemyHp, ["enemyShield"] = enemyShield, ["circleCount"] = Math.Min(3, circles.Count) });

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
