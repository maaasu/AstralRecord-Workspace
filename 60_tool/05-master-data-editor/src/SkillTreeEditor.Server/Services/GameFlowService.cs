using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>ゲーム進行フローの編集補助に使う、ショップの商品と装備の職業制限の索引。</summary>
public sealed class GameFlowService(FilebaseSnapshotCache snapshots)
{
    private volatile Latest? _latest;
    private sealed record Latest(FilebaseSnapshot Snapshot, GameFlowContext Context);

    public async Task<GameFlowContext> ReadContextAsync(CancellationToken token, bool reload = false)
    {
        var snapshot = await snapshots.GetAsync(token, reload: reload, includeSchemas: false);
        if (_latest is { } latest && ReferenceEquals(latest.Snapshot, snapshot)) return latest.Context;
        var context = Build(snapshot, token);
        _latest = new(snapshot, context);
        return context;
    }

    internal static GameFlowContext Build(FilebaseSnapshot snapshot, CancellationToken token)
    {
        var shops = new List<GameFlowShop>();
        var itemClasses = new SortedDictionary<string, IReadOnlyList<string>>(StringComparer.Ordinal);
        foreach (var document in snapshot.Documents)
        {
            token.ThrowIfCancellationRequested();
            if (document.PathError is not null || document.DecodeError is not null || document.Content is not JsonObject root) continue;
            var kind = MasterDataService.Kind(document.Path);
            if (MasterDataValidation.Text(root["id"]) is not { Length: > 0 } id) continue;
            if (kind == "shop")
            {
                var items = new List<string>();
                foreach (var entry in (root["items"] as JsonArray ?? []).OfType<JsonObject>())
                    if (ItemReference(entry["itemId"]) is { } itemId && !items.Contains(itemId, StringComparer.Ordinal))
                        items.Add(itemId);
                shops.Add(new(id, document.Path, items));
            }
            else if (kind == "item" && root["equipment"] is JsonObject equipment && equipment["requiredClasses"] is JsonArray requirements)
            {
                var classes = requirements.OfType<JsonObject>().Select(requirement => MasterDataValidation.Text(requirement["classId"]))
                    .OfType<string>().Where(value => value.Length > 0).Distinct(StringComparer.Ordinal).ToArray();
                if (classes.Length > 0) itemClasses[id] = classes;
            }
        }
        return new(shops, itemClasses);
    }

    /// <summary>`item:&lt;id&gt;`、別名、`{ ref: ... }` のいずれからも接頭辞を除いたアイテムIDを得る。</summary>
    private static string? ItemReference(JsonNode? node)
    {
        var value = node is JsonObject reference ? MasterDataValidation.Text(reference["ref"]) : MasterDataValidation.Text(node);
        if (string.IsNullOrWhiteSpace(value)) return null;
        var separator = value.IndexOf(':');
        return separator < 0 ? value : value[(separator + 1)..];
    }
}
