using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>Filebase の一覧用ファセット、ドロップ元、タグを一度の解析で索引化する。</summary>
public sealed class MasterDataFilterIndexService(MasterDataPaths paths, MasterDataCodec codec, MasterTagCatalog tagCatalog)
{
    public async Task<MasterDataFilterIndex> ReadAsync(CancellationToken token)
    {
        var documents = new List<(string Path, JsonObject Content)>();
        var pools = new Dictionary<string, JsonObject>(StringComparer.Ordinal);
        var tables = new Dictionary<string, JsonObject>(StringComparer.Ordinal);
        foreach (var file in DataFiles())
        {
            token.ThrowIfCancellationRequested();
            var obj = await ReadObjectAsync(file, token);
            if (obj is null) continue;
            var path = paths.Relative(file);
            documents.Add((path, obj));
            if (Text(obj["id"]) is not { Length: > 0 } id) continue;
            if (path.StartsWith("80.shared.loot/pool/", StringComparison.Ordinal)) pools[id] = obj;
            else if (path.StartsWith("80.shared.loot/table/", StringComparison.Ordinal)) tables[id] = obj;
        }

        var sources = new List<MasterDataDropSource>();
        var skillTags = new List<MasterDataSkillTags>();
        var entries = new List<MasterDataFilterEntry>();
        var usedTags = new HashSet<string>(StringComparer.Ordinal);
        foreach (var (path, obj) in documents)
        {
            var tags = StringArray(obj["tags"]);
            usedTags.UnionWith(tags);
            if (path.StartsWith("30.features.skill/", StringComparison.Ordinal))
                skillTags.Add(new(path, tags));

            var facets = new Dictionary<string, IReadOnlyList<string>>(StringComparer.Ordinal);
            var numbers = new Dictionary<string, IReadOnlyList<double>>(StringComparer.Ordinal);
            AddScalar(facets, "category", obj["category"]);
            AddScalar(facets, "type", obj["type"]);
            AddValues(facets, "tags", tags);
            AddScalar(facets, "rarity", obj["rarity"]);
            AddScalar(facets, "slot", (obj["equipment"] as JsonObject)?["slot"]);
            AddScalar(facets, "entityType", obj["entityType"]);
            AddScalar(facets, "role", obj["role"]);
            AddScalar(facets, "worldType", obj["worldType"]);
            AddValues(facets, "requiredToolTags", StringArray(obj["requiredToolTags"]));
            var world = ReferenceId((obj["entry"] as JsonObject)?["worldRef"], "world", "wo")
                ?? ReferenceId(obj["worldRef"], "world", "wo")
                ?? ReferenceId(obj["worldId"], "world", "wo")
                ?? ReferenceId(obj["world"], "world", "wo");
            if (world is not null) facets["world"] = [world];
            if (obj["enabled"] is JsonValue enabled && enabled.GetValueKind() is System.Text.Json.JsonValueKind.True or System.Text.Json.JsonValueKind.False)
                facets["enabled"] = [enabled.GetValue<bool>() ? "true" : "false"];
            if (path.StartsWith("30.features.skill/", StringComparison.Ordinal))
                facets["passiveConfigured"] = [obj["passive"] is JsonObject ? "true" : "false"];
            if (path.StartsWith("40.features.mob/", StringComparison.Ordinal))
            {
                var levels = new List<double>();
                if (Number(obj["level"]) is { } level) levels.Add(level);
                if (obj["levels"] is JsonArray profiles)
                    levels.AddRange(profiles.OfType<JsonObject>().Select(profile => Number(profile["level"]))
                        .OfType<double>());
                if (levels.Count > 0) numbers["mobLevel"] = levels.Distinct().Order().ToArray();
            }
            if (path.StartsWith("10.features.item/", StringComparison.Ordinal)
                && !path.StartsWith("10.features.item/20.equipment/set_effect/", StringComparison.Ordinal))
                AddItemFilters(obj, facets, numbers);
            entries.Add(new(path, facets, numbers));

            var kind = path.StartsWith("40.features.mob/", StringComparison.Ordinal) ? "mob"
                : path.StartsWith("42.features.gathering/", StringComparison.Ordinal) ? "gathering" : null;
            if (kind is not null && Text(obj["id"]) is { Length: > 0 } id)
            {
                var items = new HashSet<string>(StringComparer.Ordinal);
                CollectDrops(obj["drops"], items, tables, pools);
                if (kind == "mob" && obj["levels"] is JsonArray levels)
                    foreach (var level in levels.OfType<JsonObject>())
                        CollectDrops(level["drops"], items, tables, pools);
                sources.Add(new(path, id, Text(obj["name"]) ?? id, kind,
                    items.Order(StringComparer.Ordinal).ToArray()));
            }
        }

        var names = (await tagCatalog.ReadAllAsync(token)).ToDictionary(tag => tag.Id, tag => tag.DisplayName, StringComparer.Ordinal);
        var tagsForFilter = usedTags.Order(StringComparer.Ordinal)
            .Select(id => new MasterDataFilterTag(id, names.GetValueOrDefault(id) ?? id)).ToArray();
        return new(sources.OrderBy(source => source.Path, StringComparer.Ordinal).ToArray(),
            skillTags.OrderBy(skill => skill.Path, StringComparer.Ordinal).ToArray(), tagsForFilter,
            entries.OrderBy(entry => entry.Path, StringComparer.Ordinal).ToArray());
    }

    private IEnumerable<string> DataFiles() => paths.Enumerate()
        .Where(path => MasterDataPaths.IsData(path) && !path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase))
        .Order(StringComparer.Ordinal);

    private static string[] StringArray(JsonNode? node)
    {
        if (node is not JsonArray values || values.Any(value => Text(value) is null)) return [];
        return values.Select(Text).OfType<string>().Where(value => value.Length > 0)
            .Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal).ToArray();
    }

    private static void AddScalar(Dictionary<string, IReadOnlyList<string>> facets, string key, JsonNode? node)
    {
        if (Text(node) is { Length: > 0 } value) facets[key] = [value];
    }

    private static void AddValues(Dictionary<string, IReadOnlyList<string>> facets, string key, string[] values)
    {
        if (values.Length > 0) facets[key] = values;
    }

    private static void AddItemFilters(JsonObject obj, Dictionary<string, IReadOnlyList<string>> facets,
        Dictionary<string, IReadOnlyList<double>> numbers)
    {
        // Defaults mirror ItemResponse / ItemRepository; these are master flags, not instance transfer checks.
        AddBooleanDefault(facets, "unTradeable", obj["unTradeable"], false);
        AddBooleanDefault(facets, "unSellable", obj["unSellable"], false);
        AddNumber(numbers, "saleValue", obj["saleValue"], 0);
        AddNumber(numbers, "maxStack", obj["maxStack"], 64);
        if (obj["equipment"] is not JsonObject equipment) return;
        AddNumber(numbers, "requiredLevel", equipment["requiredLevel"], 0);
        AddScalar(facets, "equipmentTag", equipment["tag"]);
        AddScalar(facets, "setId", equipment["setId"]);
        // Hand type has meaning only for held equipment.
        if (Text(equipment["slot"]) is "WEAPON" or "TOOL")
            facets["handType"] = [Text(equipment["handType"]) ?? "ONE"];
        var classes = equipment["requiredClasses"] is JsonArray requirements
            ? requirements.Select(value => ReferenceId(value is JsonObject requirement ? requirement["classId"] : value, "class", "cl"))
                .OfType<string>().Where(value => value.Length > 0).Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal).ToArray()
            : [];
        AddValues(facets, "requiredClasses", classes);
        AddBoolean(facets, "classRestricted", classes.Length > 0);
        var enhance = equipment["enhance"] as JsonObject;
        var enchant = equipment["enchant"] as JsonObject;
        // Limits describe the initial equipment state, before transcendence overrides.
        AddNumber(numbers, "enhanceMaxLevel", enhance?["maxLevel"], 0);
        AddNumber(numbers, "enchantMaxSlots", enchant?["maxSlots"], enchant is null ? 0 : 1);
        if (numbers.TryGetValue("enhanceMaxLevel", out var enhanceMax))
            AddBoolean(facets, "enhanceEnabled", enhanceMax[0] > 0);
        if (numbers.TryGetValue("enchantMaxSlots", out var enchantMax))
            AddBoolean(facets, "enchantEnabled", enchantMax[0] > 0);
        if (equipment["durability"] is JsonObject durability)
            AddNumber(numbers, "durabilityMax", durability["max"]);
    }

    private static void AddBooleanDefault(Dictionary<string, IReadOnlyList<string>> facets, string key,
        JsonNode? node, bool fallback)
    {
        if (node is null) AddBoolean(facets, key, fallback);
        else if (node is JsonValue value && value.TryGetValue<bool>(out var boolean)) AddBoolean(facets, key, boolean);
    }

    private static void AddBoolean(Dictionary<string, IReadOnlyList<string>> facets, string key, bool value) =>
        facets[key] = [value ? "true" : "false"];

    private static void AddNumber(Dictionary<string, IReadOnlyList<double>> numbers, string key,
        JsonNode? node, double? fallback = null)
    {
        if ((Number(node) ?? (node is null ? fallback : null)) is { } number) numbers[key] = [number];
    }

    private static double? Number(JsonNode? node) => node is JsonValue value
        && value.GetValueKind() == System.Text.Json.JsonValueKind.Number
        && double.TryParse(value.ToJsonString(), System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out var number) && double.IsFinite(number) ? number : null;

    private async Task<JsonObject?> ReadObjectAsync(string path, CancellationToken token)
    {
        var raw = await File.ReadAllTextAsync(path, token);
        return codec.Parse(path, raw).Content as JsonObject;
    }

    private static void CollectDrops(JsonNode? node, HashSet<string> items,
        IReadOnlyDictionary<string, JsonObject> tables, IReadOnlyDictionary<string, JsonObject> pools)
    {
        if (node is not JsonObject drops) return;
        CollectItems(drops["items"], items);
        var tableId = ReferenceId(drops["lootTable"], "loot_table", "lt");
        if (tableId is null || !tables.TryGetValue(tableId, out var table) || table["pools"] is not JsonArray references) return;
        foreach (var reference in references)
        {
            var poolId = ReferenceId(reference, "loot_pool", "lp");
            if (poolId is not null && pools.TryGetValue(poolId, out var pool))
                CollectItems(pool["contents"], items);
        }
    }

    private static void CollectItems(JsonNode? node, HashSet<string> items)
    {
        if (node is not JsonArray contents) return;
        foreach (var content in contents.OfType<JsonObject>())
            if (ReferenceId(content["itemId"], "item", "im") is { Length: > 0 } id)
                items.Add(id);
    }

    private static string? ReferenceId(JsonNode? node, string prefix, string alias)
    {
        var value = Text(node is JsonObject obj ? obj["ref"] : node);
        if (string.IsNullOrWhiteSpace(value)) return null;
        var colon = value.IndexOf(':');
        if (colon < 0) return value;
        var kind = value[..colon];
        return kind == prefix || kind == alias ? value[(colon + 1)..] : null;
    }

    private static string? Text(JsonNode? node) => node is JsonValue value && value.TryGetValue<string>(out var text)
        ? text : null;
}
