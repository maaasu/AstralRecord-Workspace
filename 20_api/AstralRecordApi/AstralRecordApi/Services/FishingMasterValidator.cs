using System.Text.Json.Nodes;

namespace AstralRecordApi.Services;

/// <summary>Seeder と Filebase 書込時に共有する釣りマスタ契約。</summary>
public static class FishingMasterValidator
{
    public static void Validate(IEnumerable<FishingMasterEntry> entries)
    {
        var all = entries.ToArray();
        var fish = all.Where(e => e.Type == "fishing_fish").ToArray();
        var areas = all.Where(e => e.Type == "fishing_area").ToArray();
        var rules = all.Where(e => e.Type == "fishing_rules").ToArray();
        if (fish.Length + areas.Length + rules.Length == 0) return;

        var ids = all.GroupBy(e => e.Type).ToDictionary(g => g.Key,
            g => g.Select(e => e.Id).ToHashSet(StringComparer.Ordinal), StringComparer.Ordinal);
        bool Exists(string type, string id) => ids.TryGetValue(type, out var values) && values.Contains(id);
        var itemById = all.Where(e => e.Type == "item").ToDictionary(e => e.Id, StringComparer.Ordinal);

        foreach (var entry in fish)
        {
            var root = Root(entry);
            Header(root, entry, "FISH");
            Text(root, "name", entry);
            var entity = Text(root, "entityType", entry);
            Require(new[] { "COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH" }.Contains(entity), entry, "entityType");
            Positive(root, entry, "maxHealth", 100000);
            foreach (var key in new[] { "swimSpeed", "fleeSpeed", "landFleeSpeed", "hopSpeed" }) Positive(root, entry, key, 1);
            Nonnegative(root, entry, "pullPerTick", 100);
            Positive(root, entry, "biteChancePercent", 100);
            var caughtId = Ref(root, entry, "caughtItemId", "item");
            var amount = Integer(root, entry, "caughtAmount", 1, 64);
            Require(itemById.TryGetValue(caughtId, out var item)
                && item.Category is not ("equipment" or "pet" or "pet_egg"), entry,
                "caughtItemId must reference a stackable item");
            if (item is not null)
            {
                var itemRoot = Root(item);
                var maxStack = itemRoot.ContainsKey("maxStack") ? Integer(itemRoot, item, "maxStack", 1, 64) : 64;
                Require(amount <= maxStack, entry, "caughtAmount exceeds item maxStack");
            }
            if (root.ContainsKey("lootTableId"))
                Require(Exists("loot.table", Ref(root, entry, "lootTableId", "loot_table")), entry, "lootTableId");
        }

        foreach (var entry in rules)
        {
            var root = Root(entry);
            Header(root, entry, "FISHING_RULES");
            Positive(root, entry, "searchRadius", 32);
            var min = Integer(root, entry, "fishCountMin", 1, 32);
            var max = Integer(root, entry, "fishCountMax", 1, 32);
            Require(min <= max, entry, "fishCountMin/fishCountMax");
            foreach (var key in new[] { "waitingTicks", "turnTicks", "landingTicks", "hangingTicks",
                "escapeTicks", "nibbleTicks", "reelCooldownTicks" }) Integer(root, entry, key, 1, 72000);
            var attack = Integer(root, entry, "attackTicks", 2, 72000);
            Require(attack <= Integer(root, entry, "turnTicks", 1, 72000), entry, "attackTicks/turnTicks");
            var nibbleCycle = Integer(root, entry, "nibbleCycleTicks", 1, 72000);
            Require(nibbleCycle <= Integer(root, entry, "nibbleTicks", 1, 72000), entry, "nibbleCycleTicks/nibbleTicks");
            Positive(root, entry, "reelMultiplier", 100);
            Nonnegative(root, entry, "slackDistance", 100);
            Nonnegative(root, entry, "nibbleDistance", 1);
        }

        var enabledWorlds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var entry in areas)
        {
            var root = Root(entry);
            Header(root, entry, "FISHING_AREA");
            Text(root, "name", entry);
            var worldId = Ref(root, entry, "worldId", "world");
            Require(Exists("world", worldId), entry, "worldId");
            Require(Exists("fishing_rules", Ref(root, entry, "rulesId", "fishing_rules")), entry, "rulesId");
            if (root["enabled"] is not JsonValue enabledValue || !enabledValue.TryGetValue<bool>(out var enabled))
                throw new InvalidOperationException($"Invalid fishing master {entry.Type}:{entry.Id}: enabled must be boolean");
            if (enabled && !enabledWorlds.Add(worldId)) Fail(entry, "multiple enabled areas for world " + worldId);
            var ranges = Array(root, entry, "levelRanges");
            var boundaries = new List<(int Min, int Max)>();
            foreach (var rangeNode in ranges)
            {
                if (rangeNode is not JsonObject range) throw new InvalidOperationException($"Invalid fishing master {entry.Type}:{entry.Id}: levelRanges[] must be an object");
                var low = Integer(range, entry, "minLevel", 0, int.MaxValue);
                var high = Integer(range, entry, "maxLevel", low, int.MaxValue);
                if (boundaries.Any(b => low <= b.Max && high >= b.Min)) Fail(entry, "overlapping levelRanges");
                boundaries.Add((low, high));
                var choices = Array(range, entry, "fish");
                var seenFish = new HashSet<string>(StringComparer.Ordinal);
                var total = 0d;
                foreach (var choiceNode in choices)
                {
                    if (choiceNode is not JsonObject choice) throw new InvalidOperationException($"Invalid fishing master {entry.Type}:{entry.Id}: fish[] must be an object");
                    var fishId = Ref(choice, entry, "fishId", "fishing_fish");
                    Require(Exists("fishing_fish", fishId), entry, "fishId");
                    Require(seenFish.Add(fishId), entry, "duplicate fishId in level band");
                    total += Positive(choice, entry, "chancePercent", 100);
                }
                Require(Math.Abs(total - 100) <= 1e-6, entry, "chancePercent sum must equal 100");
            }
        }
    }

    private static JsonObject Root(FishingMasterEntry entry)
        => JsonNode.Parse(entry.PayloadJson) as JsonObject
            ?? throw new InvalidOperationException($"{entry.Type}:{entry.Id} payload must be an object");

    private static void Header(JsonObject root, FishingMasterEntry entry, string type)
    {
        Require(Integer(root, entry, "schemaVersion", 1, 1) == 1, entry, "schemaVersion");
        Require(Text(root, "id", entry) == entry.Id, entry, "id");
        Require(Text(root, "type", entry) == type, entry, "type");
    }

    private static JsonArray Array(JsonObject root, FishingMasterEntry entry, string key)
    {
        if (root[key] is JsonArray array && array.Count > 0) return array;
        return Fail<JsonArray>(entry, key + " must be a nonempty array");
    }

    private static string Text(JsonObject root, string key, FishingMasterEntry entry)
    {
        if (root[key] is JsonValue value && value.TryGetValue<string>(out var text)
            && !string.IsNullOrWhiteSpace(text)) return text;
        return Fail<string>(entry, key + " must be text");
    }

    private static string Ref(JsonObject root, FishingMasterEntry entry, string key, string prefix)
    {
        if (root[key] is JsonObject map && map["ref"] is JsonValue value
            && value.TryGetValue<string>(out var text) && text is not null
            && text.StartsWith(prefix + ":", StringComparison.Ordinal)
            && text.Length > prefix.Length + 1 && !text[(prefix.Length + 1)..].Contains(':'))
            return text[(prefix.Length + 1)..];
        return Fail<string>(entry, key + " must be {ref: " + prefix + ":<id>}");
    }

    private static int Integer(JsonObject root, FishingMasterEntry entry, string key, int min, int max)
    {
        if (root[key] is JsonValue value && value.TryGetValue<long>(out var number)
            && number >= min && number <= max) return (int)number;
        return Fail<int>(entry, $"{key} must be an integer from {min} to {max}");
    }

    private static double Positive(JsonObject root, FishingMasterEntry entry, string key, double max)
    {
        if (root[key] is JsonValue value && double.TryParse(value.ToString(),
            System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var number)
            && double.IsFinite(number) && number > 0 && number <= max) return number;
        return Fail<double>(entry, $"{key} must be finite and > 0 through {max}");
    }

    private static double Nonnegative(JsonObject root, FishingMasterEntry entry, string key, double max)
    {
        if (root[key] is JsonValue value && double.TryParse(value.ToString(),
            System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var number)
            && double.IsFinite(number) && number >= 0 && number <= max) return number;
        return Fail<double>(entry, $"{key} must be finite and 0 through {max}");
    }

    private static void Require(bool value, FishingMasterEntry entry, string detail)
    {
        if (!value) Fail(entry, detail);
    }

    private static void Fail(FishingMasterEntry entry, string detail)
        => throw new InvalidOperationException($"Invalid fishing master {entry.Type}:{entry.Id}: {detail}");

    private static T Fail<T>(FishingMasterEntry entry, string detail)
        => throw new InvalidOperationException($"Invalid fishing master {entry.Type}:{entry.Id}: {detail}");
}

public sealed record FishingMasterEntry(string Type, string Id, string? Category, string PayloadJson);
