using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>
/// ゲーム進行フローの編集補助。ショップの商品、バンドルの開封報酬、装備の職業制限と、
/// 次に置くノードの候補になるマスター同士のつながりをまとめる。
/// </summary>
public sealed class GameFlowService(FilebaseSnapshotCache snapshots, ProgressionDesignService design)
{
    private volatile Latest? _latest;
    private sealed record Latest(FilebaseSnapshot Snapshot, ProgressionDesignCatalog Design, GameFlowContext Context);

    /// <summary>
    /// 入手元の種類（装備・入手経路の索引の Kind）と、その矢印に付けるラベル。
    /// ルートプールはルートテーブル経由と同じアイテムを重ねて出すだけなので候補にしない。
    /// </summary>
    private static readonly Dictionary<string, string> SourceLabels = new(StringComparer.Ordinal)
    {
        ["mob"] = "ドロップ", ["dungeon"] = "クリア報酬", ["gathering"] = "採集", ["shop"] = "購入",
        ["recipe"] = "製作", ["fishing"] = "釣果", ["lootTable"] = "ドロップ",
    };

    /// <summary>使い道の種類と、アイテムからその使い道への矢印に付けるラベル。</summary>
    private static readonly Dictionary<string, string> UsageLabels = new(StringComparer.Ordinal)
    {
        ["world"] = "入場条件",
    };

    public async Task<GameFlowContext> ReadContextAsync(CancellationToken token, bool reload = false)
    {
        // 先に走査を更新し、索引は同じ走査結果から作らせる（再読込時のずれを避ける）。
        var snapshot = await snapshots.GetAsync(token, reload: reload, includeSchemas: false);
        var catalog = await design.ReadAsync(token);
        if (_latest is { } latest && ReferenceEquals(latest.Snapshot, snapshot) && ReferenceEquals(latest.Design, catalog))
            return latest.Context;
        var context = Build(snapshot, catalog, token);
        _latest = new(snapshot, catalog, context);
        return context;
    }

    internal static GameFlowContext Build(FilebaseSnapshot snapshot, ProgressionDesignCatalog? catalog, CancellationToken token)
    {
        var shops = new List<GameFlowShop>();
        var itemClasses = new SortedDictionary<string, IReadOnlyList<string>>(StringComparer.Ordinal);
        var refs = new Dictionary<string, string>(StringComparer.Ordinal);
        var itemRefs = new HashSet<string>(StringComparer.Ordinal);
        var bundles = new List<(string Ref, JsonObject Rewards)>();
        var links = new List<GameFlowLink>();
        var seen = new HashSet<(string, string)>();
        void Link(string? from, string? to, string label)
        {
            if (from is null || to is null || from == to || !seen.Add((from, to))) return;
            links.Add(new(from, to, label));
        }

        foreach (var document in snapshot.Documents)
        {
            token.ThrowIfCancellationRequested();
            if (document.PathError is not null || document.DecodeError is not null || document.Content is not JsonObject root) continue;
            var kind = MasterDataService.Kind(document.Path);
            if (MasterDataValidation.Text(root["id"]) is not { Length: > 0 } id) continue;
            var self = kind + ":" + id;
            refs[document.Path] = self;
            switch (kind)
            {
                case "shop":
                    var items = new List<string>();
                    foreach (var entry in (root["items"] as JsonArray ?? []).OfType<JsonObject>())
                        if (ReferenceId(entry["itemId"]) is { } itemId && !items.Contains(itemId, StringComparer.Ordinal))
                            items.Add(itemId);
                    shops.Add(new(id, document.Path, items));
                    break;
                case "item":
                    itemRefs.Add(self);
                    if (root["bundle"] is JsonObject bundle)
                        bundles.Add((self, bundle));
                    if (root["equipment"] is JsonObject equipment && equipment["requiredClasses"] is JsonArray requirements)
                    {
                        var classes = requirements.OfType<JsonObject>().Select(requirement => MasterDataValidation.Text(requirement["classId"]))
                            .OfType<string>().Where(value => value.Length > 0).Distinct(StringComparer.Ordinal).ToArray();
                        if (classes.Length > 0) itemClasses[id] = classes;
                    }
                    break;
                case "dungeon":
                    if (root["entry"] is JsonObject dungeonEntry) Link(Reference("world", dungeonEntry["worldRef"]), self, "入場");
                    if (root["encounter"] is JsonObject encounter)
                    {
                        foreach (var row in (encounter["normalMobPool"] as JsonArray ?? []).OfType<JsonObject>())
                            Link(self, Reference("mob", row["mobId"]), "出現");
                        Link(self, Reference("mob", encounter["bossMobId"]), "ボス");
                    }
                    break;
                case "mob_spawner":
                    foreach (var row in (root["spawnMobs"] as JsonArray ?? []).OfType<JsonObject>())
                        Link(self, Reference("mob", row["mobId"]), "出現");
                    break;
                case "gathering_spawner":
                    foreach (var row in (root["spawnGatherings"] as JsonArray ?? []).OfType<JsonObject>())
                        Link(self, Reference("gathering", row["gatheringId"]), "出現");
                    break;
                case "mob" when root["interactions"] is JsonObject interactions:
                    foreach (var side in new[] { "leftClick", "rightClick" })
                        foreach (var action in (interactions[side] as JsonArray ?? []).OfType<JsonObject>())
                            if (MasterDataValidation.Text(action["id"]) == "gui" && action["params"] is JsonObject parameters
                                && MasterDataValidation.Text(parameters["type"]) == "SHOP")
                                Link(self, Reference("shop", parameters["shopId"]), "ショップ");
                    break;
            }
        }

        // 装備・入手経路が展開した抽選先を使う。確定報酬を先に登録し、両方から出る場合も1本にする。
        var lootByTable = catalog?.Sources
            .Where(source => source.Kind == "lootTable"
                && ProgressionDesignService.HasPossibleLoot(source.Rates)
                && ProgressionDesignService.HasPossibleLoot(source.Quantity))
            .ToLookup(source => refs.GetValueOrDefault(source.Path), source => "item:" + source.ItemId);
        foreach (var (bundleRef, rewards) in bundles)
        {
            foreach (var row in (rewards["items"] as JsonArray ?? []).OfType<JsonObject>())
                if (Reference("item", row["itemId"]) is { } itemRef && itemRefs.Contains(itemRef))
                    Link(bundleRef, itemRef, "開封");

            if (Reference("loot_table", rewards["lootTableId"]) is { } tableRef && lootByTable is not null)
                foreach (var itemRef in lootByTable[tableRef])
                    if (itemRefs.Contains(itemRef))
                        Link(bundleRef, itemRef, "開封（抽選）");
        }

        if (catalog is not null)
        {
            foreach (var source in catalog.Sources)
            {
                if (SourceLabels.TryGetValue(source.Kind, out var label))
                    Link(refs.GetValueOrDefault(source.Path), "item:" + source.ItemId, label);
                // 素材は入手元（ショップ・レシピ）単位でつなぐ。レシピを使うショップの素材もショップへ向ける。
                if (source.Kind is "shop" or "recipe")
                    foreach (var ingredient in source.Ingredients)
                        Link("item:" + ingredient.ItemId, refs.GetValueOrDefault(source.Path), "素材");
            }
            foreach (var usage in catalog.Usages)
                if (UsageLabels.TryGetValue(usage.Kind, out var label))
                    Link("item:" + usage.ItemId, refs.GetValueOrDefault(usage.Path), label);
        }
        return new(shops, itemClasses, links);
    }

    /// <summary>`item:&lt;id&gt;`、別名、`{ ref: ... }` のいずれからも接頭辞を除いたIDを得る。</summary>
    private static string? ReferenceId(JsonNode? node)
    {
        var value = node is JsonObject reference ? MasterDataValidation.Text(reference["ref"]) : MasterDataValidation.Text(node);
        if (string.IsNullOrWhiteSpace(value)) return null;
        var separator = value.IndexOf(':');
        var id = (separator < 0 ? value : value[(separator + 1)..]).Trim();
        return id.Length > 0 ? id : null;
    }

    private static string? Reference(string kind, JsonNode? node) => ReferenceId(node) is { } id ? kind + ":" + id : null;
}
