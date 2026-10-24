using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

internal sealed class BalanceRuntimeUnsupportedException(string message) : Exception(message);

/// <summary>Loaded, unobstructed flat floor and explicit tick inputs; never integrates Bukkit velocity physics.</summary>
internal sealed class BalanceRuntimeWorld(BalanceRuntimeContext context)
{
    private readonly Dictionary<string, int> replayTargets = new(StringComparer.Ordinal);
    private readonly Dictionary<int, JsonObject[]> inputs = BalanceCombatData.Array(context.Request["inputs"]).OfType<JsonObject>()
        .GroupBy(input => BalanceCombatData.Integer(input["tick"])).ToDictionary(group => group.Key, group => group.ToArray());
    internal static BalanceVector Vector(JsonNode? value, BalanceVector fallback)
        => value is JsonArray { Count: 3 } a ? new(BalanceCombatData.Number(a[0]), BalanceCombatData.Number(a[1]), BalanceCombatData.Number(a[2])) : fallback;
    internal static JsonArray Json(BalanceVector value) => new(value.X, value.Y, value.Z);

    internal void Initialize()
    {
        var world = context.Request["world"] as JsonObject;
        context.FloorY = BalanceCombatData.Number(world?["floorY"]);
        context.PlayerPosition = Vector(world?["playerPosition"], new(0, context.FloorY, 0));
        context.EnemyPosition = Vector(world?["enemyPosition"], new(0, context.FloorY,
            BalanceCombatData.Number(context.Request["enemy"]?["distanceBlocks"], 3)));
        context.Facing = Vector(world?["facing"], new(0, 0, 1)).Normalized;
        context.EnemyHalfWidth = BalanceCombatData.Number(context.Request["enemy"]?["targetHalfDepth"], .3);
        context.EnemyHeight = BalanceCombatData.Number(context.Request["enemy"]?["targetHeight"], 1.8);
        foreach (var entry in BalanceCombatData.Array(context.Request["allies"]).OfType<JsonObject>())
        {
            var ally = new BalanceRuntimeAlly { Id = BalanceCombatData.Text(entry["id"]), Position = Vector(entry["position"], BalanceVector.Zero),
                Hp = BalanceCombatData.Number(entry["hp"]), Mp = BalanceCombatData.Number(entry["mp"]), Eng = BalanceCombatData.Number(entry["eng"]),
                Shield = BalanceCombatData.Number(entry["shield"]), ShieldEnabled = BalanceCombatData.Boolean(entry["shieldEnabled"]) };
            foreach (var (key, value) in entry["stats"] as JsonObject ?? new()) ally.BaseStats[key] = ally.Stats[key] = BalanceCombatData.Number(value);
            context.Allies.Add(ally);
        }
    }

    internal void Tick()
    {
        var frames = inputs.GetValueOrDefault(context.Tick) ?? [];
        foreach (var input in frames)
        {
            context.PlayerPosition = Vector(input["playerPosition"], context.PlayerPosition);
            context.EnemyPosition = Vector(input["enemyPosition"], context.EnemyPosition);
            context.Facing = Vector(input["facing"], context.Facing).Normalized;
            if (input["sneaking"] is JsonValue sneak) context.Sneaking = sneak.GetValue<bool>();
            if (input["onGround"] is JsonValue ground) context.OnGround = ground.GetValue<bool>();
            foreach (var state in BalanceCombatData.Array(input["allyStates"]).OfType<JsonObject>())
            {
                var ally = context.Allies.Single(ally => ally.Id == BalanceCombatData.Text(state["id"]));
                ally.Position = Vector(state["position"], ally.Position);
                if (state.ContainsKey("hp")) ally.Hp = BalanceCombatData.Number(state["hp"]);
                if (state.ContainsKey("mp")) ally.Mp = BalanceCombatData.Number(state["mp"]);
                if (state.ContainsKey("eng")) ally.Eng = BalanceCombatData.Number(state["eng"]);
                if (state.ContainsKey("shield")) ally.Shield = BalanceCombatData.Number(state["shield"]);
            }
            context.Dispatch(new("input", Input: input));
        }
        foreach (var (target, started) in replayTargets.ToArray())
        {
            if (context.Tick <= started) continue;
            var field = target == "enemy" ? "enemyPosition" : "playerPosition";
            if (!frames.Any(frame => frame[field] is JsonArray))
                throw new BalanceRuntimeUnsupportedException($"tick {context.Tick}: {target} の速度変更後の座標軌跡が欠けています。毎tickの{field}を指定してください。");
            if (frames.Any(frame => BalanceCombatData.Array(frame["motionEnd"]).Any(value => value?.ToString() == target))) replayTargets.Remove(target);
        }
    }

    internal bool Velocity(string target, BalanceVector velocity)
    {
        var field = target == "enemy" ? "enemyPosition" : "playerPosition";
        if (BalanceCombatData.Text(context.Request["world"]?["velocityMode"]) != "replay"
            || !inputs.TryGetValue(context.Tick + 1, out var next) || !next.Any(frame => frame[field] is JsonArray))
            throw new BalanceRuntimeUnsupportedException($"{target} の速度変更後の座標軌跡がありません。world.velocityMode=replay と、その後の各tickの {field} を指定してください。Bukkitの速度積分は未再現です。");
        context.State["velocity:" + target] = velocity;
        replayTargets[target] = context.Tick;
        return true;
    }
    internal bool AddVelocity(string target, BalanceVector velocity)
    {
        var stats = target == "enemy" ? context.EnemyStats : context.PlayerStats;
        var scale = 1 - Math.Clamp(stats.GetValueOrDefault("KNOCKBACK_RESISTANCE"), 0, 100) / 100;
        if (scale <= 0 || velocity.LengthSquared <= 1e-12) return false;
        if (context.State.GetValueOrDefault("knockback.until:" + target) is int until && context.Tick < until) return false;
        context.State["knockback.until:" + target] = context.Tick + 4;
        return Velocity(target, velocity * scale);
    }

    internal BalanceVector Move(BalanceVector start, BalanceVector direction, double distance)
    {
        // Under the declared loaded flat-floor assumptions every .25 search sample is clear and safe.
        var end = start + direction.Normalized * distance;
        context.PlayerPosition = end;
        return end;
    }

    internal void Launch(BalanceRuntimeCast cast, BalanceRuntimeProjectile projectile)
    {
        var position = projectile.Origin;
        var velocity = projectile.Direction.Normalized * projectile.Speed;
        var travelled = 0d; var age = 0; var hitOnce = false;
        var maxTicks = projectile.MaxTicks > 0 ? projectile.MaxTicks
            : Math.Max(1, (int)Math.Ceiling(projectile.Range / Math.Max(.1, projectile.Speed))) + 2;
        Step();
        void Finish(BalanceVector location, BalanceVector effectLocation, string reason)
        {
            projectile.OnEnd?.Invoke(location, reason);
            projectile.OnTermination?.Invoke(location, effectLocation, reason);
        }
        void Step()
        {
            if (++age > maxTicks || travelled >= projectile.Range || !context.EnemyAlive) return;
            if (projectile.HomingStrength > 0 && context.InSphere(position, projectile.HomingRange))
            {
                var desired = (context.EnemyPosition + new BalanceVector(0, .8, 0) - position).Normalized * projectile.Speed;
                velocity = (velocity * Math.Max(0, 1 - projectile.HomingStrength) + desired * projectile.HomingStrength).Normalized * projectile.Speed;
            }
            var distance = projectile.PointCollision ? velocity.Length : Math.Min(velocity.Length, projectile.Range - travelled);
            if (distance <= 1e-8) { Finish(position, position, "RANGE"); return; }
            var end = position + velocity.Normalized * distance;
            var floorFraction = end.Y <= context.FloorY && velocity.Y < 0
                ? Math.Clamp((context.FloorY - position.Y) / (end.Y - position.Y), 0, 1) : double.PositiveInfinity;
            var collisionEnd = double.IsFinite(floorFraction) ? position + (end - position) * floorFraction : end;
            if (projectile.Interceptable && context.Intercept?.Invoke(cast,
                projectile.InterceptionComponents is { } metadata ? projectile with { Components = metadata } : projectile, position, collisionEnd) == true) return;
            var intersects = BalanceRuntimeGeometry.SegmentHitsBox(projectile.PointCollision ? end : position, end,
                context.EnemyPosition, context.EnemyHalfWidth, context.EnemyHeight, projectile.Radius, out var fraction);
            if (!hitOnce && context.EnemyAlive && intersects && (projectile.PointCollision
                ? !double.IsFinite(floorFraction) : !double.IsFinite(floorFraction) || fraction + 1e-6 < floorFraction))
            {
                var impact = projectile.PointCollision ? end : position + (end - position) * fraction;
                hitOnce = true;
                var components = projectile.EvaluateComponents?.Invoke() ?? projectile.Components;
                if (components.Count > 0)
                {
                    var hit = context.Hit(cast, components, projectile.AttackType,
                        cast.Id == "normal_attack" ? "NORMAL_ATTACK" : "SKILL", secondary: projectile.Secondary);
                    projectile.OnHit?.Invoke(hit);
                }
                projectile.OnCollision?.Invoke(impact);
                if (!projectile.Piercing) { Finish(impact, impact, "ENTITY"); return; }
            }
            travelled += distance;
            position = end;
            if (double.IsFinite(floorFraction)) { Finish(collisionEnd, collisionEnd - velocity.Normalized * Math.Min(.1, distance * floorFraction), "BLOCK"); return; }
            if (age >= maxTicks || travelled + 1e-6 >= projectile.Range) { Finish(position, position, "RANGE"); return; }
            velocity += new BalanceVector(0, -projectile.Gravity, 0);
            context.Schedule(cast, 1, Step);
        }
    }

    internal static IEnumerable<string> Validate(JsonObject request)
    {
        var maxTick = (int)Math.Ceiling(BalanceCombatData.Number(request["maxSeconds"], 60) * 20);
        if (request["enemy"]?["targetHeight"] is { } height && (height.GetValueKind() != System.Text.Json.JsonValueKind.Number || BalanceCombatData.Number(height) is <= 0 or > 32)) yield return "敵の当たり箱の高さは0超32以下です。";
        if (request["world"] is not null and not JsonObject) yield return "world はオブジェクトです。";
        if (request["world"] is JsonObject world)
        {
            foreach (var key in world.Select(pair => pair.Key).Except(["floorY", "playerPosition", "enemyPosition", "facing", "velocityMode"], StringComparer.Ordinal))
                yield return $"world.{key} は未対応です。";
            if (world.ContainsKey("floorY") && (world["floorY"]?.GetValueKind() != System.Text.Json.JsonValueKind.Number || !double.TryParse(world["floorY"]?.ToString(), out var floor) || !double.IsFinite(floor))) yield return "floorYは有限の数値です。";
            foreach (var key in new[] { "playerPosition", "enemyPosition", "facing" })
                if (world.ContainsKey(key) && !ValidVector(world[key])) yield return $"world.{key} は有限の3要素配列です。";
            if (world.ContainsKey("facing") && Vector(world["facing"], BalanceVector.Zero).LengthSquared <= 1e-8) yield return "視線ベクトルを0にできません。";
            if (world.ContainsKey("velocityMode") && BalanceCombatData.Text(world["velocityMode"]) != "replay") yield return "速度変更は座標軌跡を渡すreplayだけに対応します。";
        }
        if (request["inputs"] is not null and not JsonArray) yield return "inputs はtick入力の配列です。";
        var frames = BalanceCombatData.Array(request["inputs"]).ToArray();
        if (frames.Length > 72_001) yield return "tick入力は72001件以下です。";
        foreach (var node in frames)
        {
            if (node is not JsonObject frame) { yield return "各tick入力はオブジェクトです。"; continue; }
            if (frame["tick"]?.GetValueKind() != System.Text.Json.JsonValueKind.Number || !int.TryParse(frame["tick"]?.ToString(), out var tick) || tick < 0 || tick > maxTick)
                yield return "入力tickは戦闘時間内の0以上の整数です。";
            foreach (var key in frame.Select(pair => pair.Key).Except(["tick", "kind", "playerPosition", "enemyPosition", "facing", "sneaking", "onGround", "allyStates", "motionEnd", "forward", "backward", "left", "right"], StringComparer.Ordinal))
                yield return $"tick入力 {key} は未対応です。";
            foreach (var key in new[] { "playerPosition", "enemyPosition", "facing" })
                if (frame.ContainsKey(key) && !ValidVector(frame[key])) yield return $"tick入力 {key} は有限の3要素配列です。";
            foreach (var key in new[] { "sneaking", "onGround", "forward", "backward", "left", "right" })
                if (frame.ContainsKey(key) && frame[key]?.GetValueKind() is not (System.Text.Json.JsonValueKind.True or System.Text.Json.JsonValueKind.False))
                    yield return $"tick入力 {key} は真偽値です。";
            if (frame.ContainsKey("kind") && BalanceCombatData.Text(frame["kind"]) is not ("dodge" or "air_shift" or "normal_attack")) yield return "未対応の入力kindです。";
            if (frame.ContainsKey("motionEnd") && (frame["motionEnd"] is not JsonArray || BalanceCombatData.Array(frame["motionEnd"]).Any(value => value?.ToString() is not ("player" or "enemy")))) yield return "motionEndはplayer/enemyの配列です。";
        }
        var allies = BalanceCombatData.Array(request["allies"]).OfType<JsonObject>().ToArray();
        if (request["allies"] is not null and not JsonArray || allies.Length > 8) yield return "味方入力は最大8人の配列です。";
        if (allies.Select(ally => BalanceCombatData.Text(ally["id"])).Distinct(StringComparer.Ordinal).Count() != allies.Length) yield return "味方IDは重複できません。";
        foreach (var ally in allies)
        {
            if (!Guid.TryParse(BalanceCombatData.Text(ally["id"]), out _) || !ValidVector(ally["position"]) || ally["stats"] is not JsonObject) yield return "味方はUUIDのid、3要素position、派生済みstatsが必要です。";
            foreach (var key in ally.Select(pair => pair.Key).Except(["id", "position", "stats", "hp", "mp", "eng", "shield", "shieldEnabled"], StringComparer.Ordinal)) yield return $"味方の追加項目 {key} は未対応です。";
            foreach (var key in new[] { "hp", "mp", "eng", "shield" })
                if (ally.ContainsKey(key) && (ally[key]?.GetValueKind() != System.Text.Json.JsonValueKind.Number || BalanceCombatData.Number(ally[key], -1) < 0)) yield return $"味方{key}は0以上の有限数です。";
            if (BalanceCombatData.Number(ally["stats"]?["MAX_HEALTH"]) <= 0) yield return "味方MAX_HEALTHは正数です。";
        }
        foreach (var state in frames.OfType<JsonObject>().SelectMany(frame => BalanceCombatData.Array(frame["allyStates"]).OfType<JsonObject>()))
            if (!allies.Any(ally => BalanceCombatData.Text(ally["id"]) == BalanceCombatData.Text(state["id"]))) yield return "allyStatesの味方IDを初期alliesに定義してください。";
        if (request["enemy"]?["targetAllyId"] is { } target && !allies.Any(ally => BalanceCombatData.Text(ally["id"]) == target.ToString())) yield return "敵のtargetAllyIdが味方を参照できません。";
        static bool ValidVector(JsonNode? node) => node is JsonArray { Count: 3 } values && values.All(value => value?.GetValueKind() == System.Text.Json.JsonValueKind.Number
            && double.TryParse(value.ToString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var number) && double.IsFinite(number) && Math.Abs(number) <= 1_000_000);
    }
}
