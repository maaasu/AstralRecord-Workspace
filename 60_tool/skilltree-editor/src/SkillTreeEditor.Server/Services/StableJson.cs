using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

public static class StableJson
{
    private static readonly string[] NodeOrder =
        ["$schema", "schemaVersion", "nodeId", "name", "icon", "lore", "tags", "pointType", "pointCost", "effects"];
    private static readonly string[] StructureOrder =
        ["$schema", "schemaVersion", "structureId", "name", "rootNodeId", "nodes", "edges"];
    private static readonly string[] SequenceOrder = ["$schema", "schemaVersion", "lastIssuedNodeId"];
    private static readonly string[] PlacementOrder = ["nodeId", "x", "y", "z"];
    private static readonly string[] EdgeOrder = ["sourceNodeId", "targetNodeId"];
    private static readonly string[] SkillEffectOrder = ["type", "skillId"];
    private static readonly string[] StatusEffectOrder = ["type", "status", "modifierType", "value"];
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    public static string Serialize(JsonNode node)
    {
        var normalized = Sort(node);
        return normalized.ToJsonString(SerializerOptions).Replace("\r\n", "\n", StringComparison.Ordinal) + "\n";
    }

    public static JsonNode Sort(JsonNode node)
    {
        return node switch
        {
            JsonObject obj => SortObject(obj),
            JsonArray array => new JsonArray(array
                .Select(item => item is null ? null : Sort(item))
                .ToArray()),
            _ => node.DeepClone()
        };
    }

    private static JsonObject SortObject(JsonObject value)
    {
        var preferredOrder = GetPreferredOrder(value);
        var orderIndex = preferredOrder
            .Select((key, index) => (key, index))
            .ToDictionary(item => item.key, item => item.index, StringComparer.Ordinal);
        return new JsonObject(value
            .OrderBy(
                property => orderIndex.TryGetValue(property.Key, out var index) ? index : int.MaxValue)
            .ThenBy(
                property => orderIndex.ContainsKey(property.Key) ? string.Empty : property.Key,
                StringComparer.Ordinal)
            .Select(property => KeyValuePair.Create(
                property.Key,
                property.Value is null ? null : Sort(property.Value))));
    }

    private static IReadOnlyList<string> GetPreferredOrder(JsonObject value)
    {
        if (value.ContainsKey("structureId"))
            return StructureOrder;
        if (value.ContainsKey("lastIssuedNodeId"))
            return SequenceOrder;
        if (value.ContainsKey("schemaVersion") && value.ContainsKey("nodeId"))
            return NodeOrder;
        if (value.ContainsKey("sourceNodeId") || value.ContainsKey("targetNodeId"))
            return EdgeOrder;
        if (value.ContainsKey("nodeId") && (value.ContainsKey("x") || value.ContainsKey("y") || value.ContainsKey("z")))
            return PlacementOrder;
        if (string.Equals(JsonValueReader.String(value["type"]), "skill", StringComparison.Ordinal))
            return SkillEffectOrder;
        if (string.Equals(JsonValueReader.String(value["type"]), "status", StringComparison.Ordinal))
            return StatusEffectOrder;
        return [];
    }
}
