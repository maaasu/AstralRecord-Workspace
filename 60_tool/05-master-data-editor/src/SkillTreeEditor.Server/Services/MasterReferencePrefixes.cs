using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

internal static class MasterReferencePrefixes
{
    public static async Task<Dictionary<string, string>?> ReadAsync(MasterDataPaths paths, MasterDataCodec codec,
        CancellationToken token, bool includeGameFlowReferences = false)
    {
        const string configuration = "config.yml";
        var path = paths.Resolve(configuration);
        var document = File.Exists(path)
            ? codec.Parse(configuration, await File.ReadAllTextAsync(path, token)).Content as JsonObject
            : null;
        var resolvers = document?["referenceResolver"] as JsonArray;
        if (resolvers is null && !includeGameFlowReferences) return null;
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var resolver in resolvers?.OfType<JsonObject>() ?? [])
        {
            var kind = MasterDataValidation.Text(resolver["database"])?.Replace('.', '_');
            if (kind is null) continue;
            if (MasterDataValidation.Text(resolver["prefix"]) is { } prefix) result[prefix.TrimEnd(':')] = kind;
            if (resolver["aliases"] is JsonArray aliases)
                foreach (var alias in aliases)
                    if (MasterDataValidation.Text(alias) is { } value) result[value.TrimEnd(':')] = kind;
        }
        // 設計用の参照は、ゲーム側の参照設定と独立してエディタが解決する。
        if (includeGameFlowReferences)
        {
            result["game_flow"] = "game_flow";
            result["progression_preset"] = "progression_preset";
        }
        return result;
    }
}
