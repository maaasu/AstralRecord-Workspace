using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>Filebase のドロップ元とスキルタグだけを読み、一覧画面用の小さな索引を作る。</summary>
public sealed class MasterDataFilterIndexService(MasterDataPaths paths, MasterDataCodec codec, MasterTagCatalog tagCatalog)
{
    public async Task<MasterDataFilterIndex> ReadAsync(CancellationToken token)
    {
        var pools = await ReadByIdAsync("80.shared.loot/pool", token);
        var tables = await ReadByIdAsync("80.shared.loot/table", token);
        var sources = new List<MasterDataDropSource>();

        foreach (var (directory, kind) in new[] { ("40.features.mob", "mob"), ("42.features.gathering", "gathering") })
            foreach (var file in DataFiles(directory))
            {
                token.ThrowIfCancellationRequested();
                var obj = await ReadObjectAsync(file, token);
                if (obj is null || Text(obj["id"]) is not { Length: > 0 } id) continue;
                var items = new HashSet<string>(StringComparer.Ordinal);
                CollectDrops(obj["drops"], items, tables, pools);
                if (kind == "mob" && obj["levels"] is JsonArray levels)
                    foreach (var level in levels.OfType<JsonObject>())
                        CollectDrops(level["drops"], items, tables, pools);
                sources.Add(new(paths.Relative(file), id, Text(obj["name"]) ?? id, kind,
                    items.Order(StringComparer.Ordinal).ToArray()));
            }

        var skillTags = new List<MasterDataSkillTags>();
        var usedTags = new HashSet<string>(StringComparer.Ordinal);
        foreach (var file in DataFiles("30.features.skill"))
        {
            token.ThrowIfCancellationRequested();
            var obj = await ReadObjectAsync(file, token);
            if (obj is null) continue;
            var tags = obj["tags"] is JsonArray values
                ? values.Select(Text).OfType<string>().Where(value => value.Length > 0)
                    .Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal).ToArray()
                : [];
            skillTags.Add(new(paths.Relative(file), tags));
            usedTags.UnionWith(tags);
        }

        var names = (await tagCatalog.ReadAllAsync(token)).ToDictionary(tag => tag.Id, tag => tag.DisplayName, StringComparer.Ordinal);
        var tagsForFilter = usedTags.Order(StringComparer.Ordinal)
            .Select(id => new MasterDataFilterTag(id, names.GetValueOrDefault(id) ?? id)).ToArray();
        return new(sources.OrderBy(source => source.Path, StringComparer.Ordinal).ToArray(),
            skillTags.OrderBy(skill => skill.Path, StringComparer.Ordinal).ToArray(), tagsForFilter);
    }

    private IEnumerable<string> DataFiles(string directory) => paths.Enumerate(directory)
        .Where(path => MasterDataPaths.IsData(path) && !path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase))
        .Order(StringComparer.Ordinal);

    private async Task<Dictionary<string, JsonObject>> ReadByIdAsync(string directory, CancellationToken token)
    {
        var result = new Dictionary<string, JsonObject>(StringComparer.Ordinal);
        foreach (var file in DataFiles(directory))
        {
            token.ThrowIfCancellationRequested();
            var obj = await ReadObjectAsync(file, token);
            if (obj is not null && Text(obj["id"]) is { Length: > 0 } id) result[id] = obj;
        }
        return result;
    }

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
