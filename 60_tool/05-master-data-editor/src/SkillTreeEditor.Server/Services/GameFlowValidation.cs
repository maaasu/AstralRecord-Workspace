using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>ゲーム進行フロー（設計用データ）の、スキーマだけでは表せない整合性を確認する。</summary>
internal static class GameFlowValidation
{
    internal const string Category = "95.design.game_flow";
    internal const string SchemaPath = Category + "/schemas/game-flow.v1.schema.json";
    private const string ClassPrefix = "class:";

    internal static bool IsFlow(string path) => path.StartsWith(Category + "/", MasterDataPaths.Comparison)
        && !path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase);

    internal static async Task ValidateAsync(MasterDataPaths paths, MasterDataCodec codec, string path,
        JsonObject flow, List<MasterDataIssue> issues, CancellationToken token)
    {
        var id = MasterDataValidation.Text(flow["id"]);
        if (path != $"{Category}/v1.{id}.yml")
            issues.Add(new("error", "GAME_FLOW_FILENAME", "/id", "保存先はカテゴリ直下の v1.<id>.yml にしてください。"));

        var classUses = new List<(string Pointer, string Value)>();
        CollectClasses(flow["classes"], "/classes", classUses);

        var nodeIds = new HashSet<string>(StringComparer.Ordinal);
        if (flow["nodes"] is JsonArray nodes)
            for (var index = 0; index < nodes.Count; index++)
            {
                if (nodes[index] is not JsonObject node) continue;
                var pointer = $"/nodes/{index}";
                if (MasterDataValidation.Text(node["id"]) is { Length: > 0 } nodeId && !nodeIds.Add(nodeId))
                    issues.Add(new("error", "GAME_FLOW_DUPLICATE_NODE", pointer + "/id", $"ノードID '{nodeId}' が重複しています。"));
                if (string.IsNullOrWhiteSpace(MasterDataValidation.Text(node["ref"]))
                    && string.IsNullOrWhiteSpace(MasterDataValidation.Text(node["label"])))
                    issues.Add(new("error", "GAME_FLOW_NODE_EMPTY", pointer, "ノードにはマスター参照か表示名を指定してください。"));
                CollectClasses(node["classes"], pointer + "/classes", classUses);
            }

        if (flow["edges"] is JsonArray edges)
        {
            var pairs = new HashSet<(string, string)>();
            for (var index = 0; index < edges.Count; index++)
            {
                if (edges[index] is not JsonObject edge) continue;
                var pointer = $"/edges/{index}";
                var from = MasterDataValidation.Text(edge["from"]);
                var to = MasterDataValidation.Text(edge["to"]);
                if (from is not null && !nodeIds.Contains(from))
                    issues.Add(new("error", "GAME_FLOW_EDGE_NODE", pointer + "/from", $"始点ノード '{from}' がありません。"));
                if (to is not null && !nodeIds.Contains(to))
                    issues.Add(new("error", "GAME_FLOW_EDGE_NODE", pointer + "/to", $"終点ノード '{to}' がありません。"));
                if (from is not null && from == to)
                    issues.Add(new("error", "GAME_FLOW_EDGE_SELF", pointer, "同じノード同士は矢印でつなげません。"));
                else if (from is not null && to is not null && !pairs.Add((from, to)))
                    issues.Add(new("error", "GAME_FLOW_EDGE_DUPLICATE", pointer, $"'{from}' から '{to}' への矢印が重複しています。"));
                CollectClasses(edge["classes"], pointer + "/classes", classUses);
            }
        }

        if (classUses.Count == 0) return;
        var classIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var file in paths.Enumerate("20.features.class").Where(MasterDataPaths.IsData))
        {
            if (file.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
            var parsed = codec.Parse(paths.Relative(file), await File.ReadAllTextAsync(file, token));
            if (parsed.Content is JsonObject master && MasterDataValidation.Text(master["id"]) is { } classId) classIds.Add(classId);
        }
        foreach (var (pointer, value) in classUses)
            if (!value.StartsWith(ClassPrefix, StringComparison.Ordinal) || !classIds.Contains(value[ClassPrefix.Length..]))
                issues.Add(new("error", "REFERENCE_NOT_FOUND", pointer, $"職業 '{value}' に対応するクラスマスタがありません。"));
    }

    private static void CollectClasses(JsonNode? node, string pointer, List<(string, string)> result)
    {
        if (node is not JsonArray array) return;
        for (var index = 0; index < array.Count; index++)
            if (MasterDataValidation.Text(array[index]) is { } value) result.Add(($"{pointer}/{index}", value));
    }
}
