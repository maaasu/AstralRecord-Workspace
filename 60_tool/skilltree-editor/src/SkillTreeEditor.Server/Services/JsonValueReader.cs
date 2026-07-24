using System.Globalization;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

public static class JsonValueReader
{
    public static string? String(JsonNode? node)
    {
        if (node is null)
            return null;

        try
        {
            return node.GetValue<string>();
        }
        catch
        {
            return null;
        }
    }

    public static double? Number(JsonNode? node)
    {
        if (node is null)
            return null;

        try
        {
            return node.GetValue<double>();
        }
        catch
        {
            return double.TryParse(node.ToJsonString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var value)
                ? value
                : null;
        }
    }
}
