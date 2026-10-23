using System.Globalization;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

internal static class BalanceCombatData
{
    internal sealed record Master(string Kind, string Path, JsonObject Content);

    internal static IEnumerable<Master> Masters(JsonObject catalog)
    {
        foreach (var entry in Array(catalog["masters"]).OfType<JsonObject>())
            if (entry["content"] is JsonObject content)
                yield return new Master(Text(entry["kind"]), Text(entry["path"]), content);
    }

    internal static Master? Find(IEnumerable<Master> masters, string kind, string id)
        => masters.FirstOrDefault(master => master.Kind.Equals(kind, StringComparison.OrdinalIgnoreCase)
            && Text(master.Content["id"]).Equals(id, StringComparison.OrdinalIgnoreCase));

    internal static JsonObject? Object(JsonNode? node) => node as JsonObject;
    internal static IEnumerable<JsonNode?> Array(JsonNode? node) => node is JsonArray values ? values : [];
    internal static string Text(JsonNode? node) => node?.ToString() ?? "";
    internal static double Number(JsonNode? node, double fallback = 0)
        => double.TryParse(Text(node), NumberStyles.Float, CultureInfo.InvariantCulture, out var value) && double.IsFinite(value)
            ? value : fallback;
    internal static int Integer(JsonNode? node, int fallback = 0)
        => int.TryParse(Text(node), NumberStyles.Integer, CultureInfo.InvariantCulture, out var value) ? value : fallback;
    internal static bool Boolean(JsonNode? node) => bool.TryParse(Text(node), out var value) && value;
    internal static string Id(JsonNode? node)
        => node is JsonObject obj ? Text(obj["ref"]).Split(':').Last() : Text(node).Split(':').Last();

    // A master may contain two rolls (min/max), each rolled once on equipment creation.
    internal static (double Min, double Max) Range(JsonNode? node)
    {
        if (node is JsonObject obj)
        {
            var first = Range(obj["min"]);
            var last = Range(obj["max"]);
            return (first.Min, last.Max);
        }
        var raw = Text(node);
        var parts = raw.Split('~', StringSplitOptions.TrimEntries);
        if (parts.Length == 2 && double.TryParse(parts[0], NumberStyles.Float, CultureInfo.InvariantCulture, out var min)
            && double.TryParse(parts[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var max)) return (Math.Min(min, max), Math.Max(min, max));
        var value = Number(node);
        return (value, value);
    }

    internal static Dictionary<string, double> DefaultPlayerStats() => new(StringComparer.OrdinalIgnoreCase)
    {
        ["MAX_HEALTH"] = 20, ["MAX_MANA"] = 10, ["MAX_ENERGY"] = 100,
        ["STRENGTH"] = 5, ["DEXTERITY"] = 5, ["INTELLIGENCE"] = 5,
        ["VITALITY"] = 5, ["AGILITY"] = 5, ["LUCK"] = 5,
        ["ATTACK"] = 8, ["CRITICAL_RATE"] = 5, ["CRITICAL_DAMAGE"] = 150,
        ["SUPER_CRITICAL_DAMAGE"] = 30, ["FINAL_DAMAGE_MULTIPLIER"] = 100,
        ["ACCURACY"] = 95, ["ATTACK_SPEED"] = 100, ["DEFENSE"] = 5,
        ["MAGIC_DEFENSE"] = 3, ["EVASION"] = 3, ["HP_REGEN"] = 1,
        ["MP_REGEN"] = 0.5, ["ENERGY_REGEN"] = 5,
        ["DROP_RATE_INCREASE"] = 100, ["EXPERIENCE_GAIN_RATE"] = 100,
        ["NONE_RESISTANCE_CAP"] = 50, ["FIRE_RESISTANCE_CAP"] = 75,
        ["ICE_RESISTANCE_CAP"] = 75, ["LIGHTNING_RESISTANCE_CAP"] = 75
    };

    internal static Dictionary<string, double> MobStats(JsonObject mob, int requestedLevel, List<string> assumptions)
    {
        var stats = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        var profiles = Array(mob["levels"]).OfType<JsonObject>().ToArray();
        var selected = profiles.FirstOrDefault(profile => Integer(profile["level"]) == requestedLevel);
        if (profiles.Length > 0 && selected is null)
        {
            selected = profiles[0];
            assumptions.Add($"Mob Lv.{requestedLevel} の定義がないため、Pluginと同じく Lv.{Integer(selected["level"])} を選びます。");
        }
        var source = selected?["baseStats"] is JsonArray ? selected : mob;
        foreach (var entry in Array(source["baseStats"]).OfType<JsonObject>())
        {
            var status = Text(entry["status"]);
            if (!string.IsNullOrWhiteSpace(status)) stats[status] = Number(entry["value"]);
        }
        if (!stats.ContainsKey("MAX_HEALTH")) stats["MAX_HEALTH"] = 1;
        return stats;
    }

    internal static JsonObject ResolveMob(JsonObject mob, int requestedLevel, List<string> assumptions)
    {
        var profiles = Array(mob["levels"]).OfType<JsonObject>().ToArray();
        var selected = profiles.FirstOrDefault(profile => Integer(profile["level"]) == requestedLevel);
        if (profiles.Length > 0 && selected is null)
        {
            selected = profiles[0];
            assumptions.Add($"敵Lv.{requestedLevel}は未定義です。本体と同じく先頭のLv.{Integer(selected["level"])}を採用します。");
        }
        var copy = mob.DeepClone().AsObject();
        if (selected is not null) Merge(copy, selected);
        copy.Remove("levels");
        return copy;
        static void Merge(JsonObject target, JsonObject source)
        {
            foreach (var (key, value) in source)
                if (target[key] is JsonObject nested && value is JsonObject additions) Merge(nested, additions);
                else target[key] = value?.DeepClone();
        }
    }

    internal static double Stat(IReadOnlyDictionary<string, double> stats, string key, double fallback = 0)
        => stats.TryGetValue(key, out var value) ? value : fallback;

    internal static void Add(IDictionary<string, double> stats, string key, double value)
    {
        if (string.IsNullOrWhiteSpace(key)) return;
        stats[key] = (stats.TryGetValue(key, out var current) ? current : 0) + value;
    }

    internal static void AddClassStats(IDictionary<string, double> stats, JsonObject definition, int level)
    {
        var maxLevel = Math.Max(1, Integer(definition["maxLevel"], 100));
        var effectiveLevel = Math.Clamp(level, 1, maxLevel);
        foreach (var entry in Array(definition["baseStats"]).OfType<JsonObject>())
            Add(stats, Text(entry["status"]), Number(entry["value"]));
        foreach (var entry in Array(definition["growthPerLevel"]).OfType<JsonObject>())
            Add(stats, Text(entry["status"]), Number(entry["value"]) * (effectiveLevel - 1));
    }

    internal static void AddPrimaryDerived(IDictionary<string, double> stats)
    {
        Add(stats, "MAX_MANA", Stat((IReadOnlyDictionary<string, double>)stats, "INTELLIGENCE"));
        Add(stats, "MP_REGEN", Stat((IReadOnlyDictionary<string, double>)stats, "INTELLIGENCE") * 0.1);
        var vitality = Stat((IReadOnlyDictionary<string, double>)stats, "VITALITY");
        Add(stats, "MAX_HEALTH", vitality * 2);
        Add(stats, "DEFENSE", vitality);
        Add(stats, "MAGIC_DEFENSE", vitality);
        Add(stats, "HP_REGEN", vitality * 0.1);
        var agility = Stat((IReadOnlyDictionary<string, double>)stats, "AGILITY");
        Add(stats, "ATTACK_SPEED", agility);
        Add(stats, "EVASION", agility * 0.1);
        Add(stats, "CRITICAL_RATE", Stat((IReadOnlyDictionary<string, double>)stats, "LUCK") * 0.1);
    }

    internal static double EquipmentValue(JsonObject definition, bool strict, List<string> assumptions, List<string> unsupported)
    {
        var (min, max) = Range(definition["value"] ?? definition["min"]);
        if (definition["value"] is null && definition["max"] is not null) max = Range(definition["max"]).Max;
        if (min == max) return min;
        if (strict) unsupported.Add("装備能力値の乱数が未確定です。個体 statRolls を指定してください。");
        else assumptions.Add($"装備能力値 {Text(definition["status"])} の範囲 {min}..{max} は中点を使います。");
        return (min + max) / 2;
    }

    internal static void ApplyEquipmentItem(IDictionary<string, double> allStats, JsonObject equipment, JsonObject selected,
        IEnumerable<Master> masters,
        bool strict, List<string> assumptions, List<string> unsupported)
    {
        var flats = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        var scalars = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        var rolls = Array(selected["statRolls"]).OfType<JsonObject>().ToArray();
        if (rolls.Length > 0)
        {
            foreach (var roll in rolls)
            {
                var status = Text(roll["status"]);
                var definition = Array(equipment["stats"]).OfType<JsonObject>()
                    .FirstOrDefault(stat => Text(stat["status"]).Equals(status, StringComparison.OrdinalIgnoreCase));
                var type = Text(definition?["type"]);
                var min = Range(roll["min"]).Min;
                var max = Range(roll["max"]).Max;
                var value = min == max ? min : (min + max) / 2;
                if (min != max)
                {
                    if (strict) unsupported.Add($"装備個体 {status} の戦闘時再抽選範囲は未対応です。");
                    else assumptions.Add($"装備個体 {status} の戦闘時再抽選範囲は中点を使います。");
                }
                if (type.Equals("SCALAR", StringComparison.OrdinalIgnoreCase)) scalars[status] = value;
                else Add(flats, status, value);
            }
        }
        else
        {
            foreach (var stat in Array(equipment["stats"]).OfType<JsonObject>())
            {
                var status = Text(stat["status"]);
                var value = EquipmentValue(stat, strict, assumptions, unsupported);
                if (Text(stat["type"]).Equals("SCALAR", StringComparison.OrdinalIgnoreCase)) scalars[status] = value;
                else Add(flats, status, value);
            }
        }
        var enhanceLevel = Math.Max(0, Integer(selected["enhanceLevel"]));
        var enhancementScalars = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        foreach (var level in Array(Object(equipment["enhance"])?["levels"]).OfType<JsonObject>())
        {
            if (Integer(level["level"]) > enhanceLevel) continue;
            foreach (var increase in Array(level["statIncrease"]).OfType<JsonObject>())
            {
                var status = Text(increase["status"]);
                var value = EquipmentValue(increase, strict, assumptions, unsupported);
                if (Text(increase["type"]).Equals("SCALAR", StringComparison.OrdinalIgnoreCase)) Add(enhancementScalars, status, value);
                else Add(flats, status, value);
            }
        }
        foreach (var (status, value) in enhancementScalars) scalars[status] = value;
        foreach (var enchant in Array(selected["enchants"]).OfType<JsonObject>())
        {
            var status = Text(enchant["status"]);
            var value = Number(enchant["value"]);
            if (Text(enchant["type"]).Equals("SCALAR", StringComparison.OrdinalIgnoreCase)) scalars[status] = value;
            else Add(flats, status, value);
        }
        foreach (var runeRef in Array(selected["runes"]))
        {
            var runeId = Id(runeRef is JsonObject runeEntry ? runeEntry["itemId"] : runeRef);
            var rune = Find(masters, "item", runeId);
            if (rune is null) { unsupported.Add($"ルーン {runeId} の定義がありません。"); continue; }
            foreach (var stat in Array(Object(rune.Content["rune"])?["stats"]).OfType<JsonObject>())
            {
                var status = Text(stat["status"]);
                var value = EquipmentValue(stat, strict, assumptions, unsupported);
                if (Text(stat["type"]).Equals("SCALAR", StringComparison.OrdinalIgnoreCase)) scalars[status] = value;
                else Add(flats, status, value);
            }
        }
        if (Integer(selected["transcendRank"]) > 0)
        {
            var rank = Integer(selected["transcendRank"]);
            var stage = Array(equipment["transcendence"]).OfType<JsonObject>()
                .FirstOrDefault(candidate => Integer(candidate["rank"]) == rank);
            if (stage is null) unsupported.Add($"超越ランク {rank} の装備定義がありません。");
            else if (Object(stage["overrides"])?["stats"] is not null)
                unsupported.Add($"超越ランク {rank} の能力値上書きは未対応です。");
        }
        // Plugin's per-item calculator applies the last SCALAR to this item's FLAT sum.
        foreach (var (status, flat) in flats)
            Add(allStats, status, flat * (scalars.TryGetValue(status, out var scalar) ? scalar : 1));
    }
}
