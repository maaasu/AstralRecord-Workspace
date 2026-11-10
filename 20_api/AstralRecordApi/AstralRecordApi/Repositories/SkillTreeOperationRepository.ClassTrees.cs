using System.Collections.Concurrent;
using System.Globalization;
using System.Text.Json;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// 公開済み定義から、就職中以外のクラスを閲覧するためのクラス別ツリーを作ります。
/// Pluginの5秒周期のviewは増やさず、Webがクラスを切り替えたときだけ取得されます。
/// </summary>
public sealed partial class SkillTreeOperationRepository
{
    private const int MaxCachedClassTreeGenerations = 8;
    private static readonly ConcurrentDictionary<string, ClassTreeDefinition> ClassTreeCache = new(StringComparer.Ordinal);

    /// <summary>
    /// 所有アカウントについて、指定世代のクラス一覧と、指定クラスの閲覧用ノードを返します。
    /// </summary>
    /// <param name="accountId">対象アカウント</param>
    /// <param name="actorUserId">認証済みの所有者UUID。所有者でなければ null を返します。</param>
    /// <param name="generationId">Webが表示中の定義世代</param>
    /// <param name="classId">ノードを取得するクラス。null ならクラス一覧だけを返します。</param>
    /// <returns>所有者でない、世代が未登録、または定義を解釈できない場合は null</returns>
    public async Task<SkillTreeClassTreesResponse?> GetClassTreesAsync(Guid accountId, Guid actorUserId, string generationId, string? classId)
    {
        var owned = await dbContext.Accounts.AsNoTracking().AnyAsync(x => x.Uuid == accountId && !x.IsDeleted && x.UserId == actorUserId);
        if (!owned) return null;
        var definition = await LoadClassTreeDefinitionAsync(generationId);
        if (definition is null) return null;
        var unlocked = (await dbContext.AccountSkillTreeUnlockedNodes.AsNoTracking()
                .Where(x => x.State!.AccountId == accountId && !x.State.IsDeleted)
                .Select(x => x.NodeId).ToListAsync())
            .ToHashSet(StringComparer.Ordinal);
        var classes = definition.Classes.Select(entry => new SkillTreeClassSummaryResponse
        {
            ClassId = entry.ClassId, ClassName = entry.ClassName, NodeCount = entry.NodeIds.Count,
            UnlockedCount = entry.NodeIds.Count(unlocked.Contains),
        }).ToList();
        var normalized = classId?.Trim().ToLowerInvariant();
        var selected = normalized is null ? null : definition.Classes.FirstOrDefault(entry => entry.ClassId == normalized);
        if (selected is null)
            return new SkillTreeClassTreesResponse { GenerationId = generationId, Classes = classes };
        var ids = selected.NodeIds.ToHashSet(StringComparer.Ordinal);
        return new SkillTreeClassTreesResponse
        {
            GenerationId = generationId, Classes = classes, ClassId = selected.ClassId,
            Nodes = selected.NodeIds.Select(id => definition.Nodes[id]).Select(node => node.ToResponse(unlocked.Contains(node.NodeId))).ToList(),
            Edges = definition.Edges.Where(edge => ids.Contains(edge.SourceNodeId) || ids.Contains(edge.TargetNodeId)).ToList(),
        };
    }

    private async Task<ClassTreeDefinition?> LoadClassTreeDefinitionAsync(string generationId)
    {
        if (ClassTreeCache.TryGetValue(generationId, out var cached)) return cached;
        var json = await GetDefinitionAsync(generationId);
        if (json is null) return null;
        ClassTreeDefinition? parsed;
        try { parsed = ClassTreeDefinition.Parse(json); }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or KeyNotFoundException or FormatException or ArgumentException or OverflowException) { parsed = null; }
        if (parsed is null) return null;
        // 定義世代は不変なので、少数だけ保持して再解析を省く。
        if (ClassTreeCache.Count >= MaxCachedClassTreeGenerations) ClassTreeCache.Clear();
        ClassTreeCache[generationId] = parsed;
        return parsed;
    }

    private sealed record ClassTreeClass(string ClassId, string ClassName, List<string> NodeIds);

    private sealed record ClassTreeNode(string NodeId, string Name, string? Icon, string PointType, int PointCost, string CostText,
        double X, double Y, double Z, JsonElement UnlockCondition, string RequirementText, IReadOnlyList<string> DisplayEffects)
    {
        public SkillTreeClassNodeResponse ToResponse(bool isUnlocked) => new()
        {
            NodeId = NodeId, Name = Name, Icon = Icon, PointType = PointType, PointCost = PointCost, CostText = CostText,
            X = X, Y = Y, Z = Z, UnlockCondition = UnlockCondition, RequirementText = RequirementText,
            DisplayEffects = DisplayEffects, IsUnlocked = isUnlocked,
        };
    }

    private sealed record ClassTreeDefinition(IReadOnlyList<ClassTreeClass> Classes, IReadOnlyDictionary<string, ClassTreeNode> Nodes, IReadOnlyList<WebSkillTreeEdgeResponse> Edges)
    {
        /// <summary>Pluginの`createDefinitionGeneration`が作る正規化JSONを解釈します。</summary>
        public static ClassTreeDefinition? Parse(string json)
        {
            using var document = JsonDocument.Parse(json);
            var root = document.RootElement;
            if (!root.TryGetProperty("nodes", out var nodeArray) || nodeArray.ValueKind != JsonValueKind.Array) return null;
            var positions = new Dictionary<string, (double X, double Y, double Z)>(StringComparer.Ordinal);
            if (root.TryGetProperty("positions", out var positionArray) && positionArray.ValueKind == JsonValueKind.Array)
                foreach (var position in positionArray.EnumerateArray())
                    positions[position.GetProperty("nodeId").GetString()!] =
                        (position.GetProperty("x").GetDouble(), position.GetProperty("y").GetDouble(), position.GetProperty("z").GetDouble());
            var nodes = new Dictionary<string, ClassTreeNode>(StringComparer.Ordinal);
            var classes = new Dictionary<string, ClassTreeClass>(StringComparer.Ordinal);
            foreach (var node in nodeArray.EnumerateArray())
            {
                var nodeId = node.GetProperty("nodeId").GetString()!;
                if (!positions.TryGetValue(nodeId, out var at)) continue;
                var condition = node.GetProperty("unlockCondition");
                var rawClassId = condition.TryGetProperty("classId", out var classValue) && classValue.ValueKind == JsonValueKind.String ? classValue.GetString() : null;
                if (string.IsNullOrWhiteSpace(rawClassId)) continue;
                var classId = rawClassId.Trim().ToLowerInvariant();
                var className = condition.TryGetProperty("classDisplayName", out var nameValue) && nameValue.ValueKind == JsonValueKind.String
                    && !string.IsNullOrWhiteSpace(nameValue.GetString()) ? nameValue.GetString()! : classId;
                var classPoint = node.GetProperty("pointType").GetString() == "CLASS_POINT";
                var cost = node.GetProperty("pointCost").GetInt32();
                var requirements = new List<string>();
                if (condition.TryGetProperty("playerLevel", out var level) && level.ValueKind == JsonValueKind.Number && level.GetInt32() > 0)
                    requirements.Add($"必要レベル: {level.GetInt32()}");
                requirements.Add($"必要クラス: {className}");
                nodes[nodeId] = new ClassTreeNode(
                    nodeId, node.GetProperty("name").GetString() ?? nodeId,
                    node.TryGetProperty("icon", out var icon) && icon.ValueKind == JsonValueKind.String ? icon.GetString() : null,
                    classPoint ? "CP" : "PP", cost, classPoint ? $"{className} · {cost} CP" : $"{cost} PP",
                    at.X, at.Y, at.Z, condition.Clone(), string.Join("\n", requirements), DisplayEffects(node));
                if (!classes.TryGetValue(classId, out var entry)) classes[classId] = entry = new ClassTreeClass(classId, className, []);
                entry.NodeIds.Add(nodeId);
            }
            var edges = new List<WebSkillTreeEdgeResponse>();
            if (root.TryGetProperty("edges", out var edgeArray) && edgeArray.ValueKind == JsonValueKind.Array)
                foreach (var edge in edgeArray.EnumerateArray())
                {
                    var parts = (edge.GetString() ?? string.Empty).Split("->", 2);
                    if (parts.Length == 2 && parts[0].Length > 0 && parts[1].Length > 0)
                        edges.Add(new WebSkillTreeEdgeResponse { SourceNodeId = parts[0], TargetNodeId = parts[1] });
                }
            return new ClassTreeDefinition(classes.Values.ToList(), nodes, edges);
        }

        /// <summary>Javaの`%.Nf`と同じく、10進表現を四捨五入（0.5は0から遠い側）で丸めます。</summary>
        private static decimal RoundLikeJava(double value, int decimals) =>
            Math.Round((decimal)value, Math.Clamp(decimals, 0, 10), MidpointRounding.AwayFromZero);

        /// <summary>Pluginの`createRuntimePlayerView`と同じ書式で効果を表示文字列にします。</summary>
        private static IReadOnlyList<string> DisplayEffects(JsonElement node)
        {
            var statuses = new List<string>();
            var skills = new List<string>();
            if (!node.TryGetProperty("effects", out var effects) || effects.ValueKind != JsonValueKind.Array) return statuses;
            foreach (var effect in effects.EnumerateArray())
            {
                var type = effect.TryGetProperty("type", out var typeValue) ? typeValue.GetString() : null;
                var name = effect.TryGetProperty("displayName", out var nameValue) ? nameValue.GetString() ?? string.Empty : string.Empty;
                if (type == "skill") { skills.Add($"- {name}"); continue; }
                if (type != "status") continue;
                var value = effect.GetProperty("value").GetDouble();
                if (effect.TryGetProperty("modifierType", out var modifier) && modifier.GetString() == "SCALAR")
                {
                    var percent = value * 100d;
                    var text = percent == Math.Round(percent)
                        ? percent.ToString("0", CultureInfo.InvariantCulture) : RoundLikeJava(percent, 2).ToString("0.00", CultureInfo.InvariantCulture);
                    statuses.Add($"{name} {(percent > 0 ? "+" : string.Empty)}{text}%");
                    continue;
                }
                var decimals = effect.TryGetProperty("decimalPlaces", out var places) && places.ValueKind == JsonValueKind.Number ? places.GetInt32() : 0;
                var suffix = effect.TryGetProperty("suffix", out var suffixValue) ? suffixValue.GetString() ?? string.Empty : string.Empty;
                statuses.Add($"{name} {(value > 0 ? "+" : string.Empty)}{RoundLikeJava(value, decimals).ToString("N" + decimals, CultureInfo.InvariantCulture)}{suffix}");
            }
            // Pluginはステータス効果の後にスキル付与の行を並べる。
            statuses.AddRange(skills);
            return statuses;
        }
    }
}
