using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

internal static class MasterReferencePrefixes
{
    public static async Task<Dictionary<string, string>?> ReadAsync(MasterDataPaths paths, MasterDataCodec codec, CancellationToken token)
    {
        const string configuration = "config.yml";
        var path = paths.Resolve(configuration);
        if (!File.Exists(path)) return null;
        var document = codec.Parse(configuration, await File.ReadAllTextAsync(path, token)).Content as JsonObject;
        if (document?["referenceResolver"] is not JsonArray resolvers) return null;
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var resolver in resolvers.OfType<JsonObject>())
        {
            var kind = MasterDataValidation.Text(resolver["database"])?.Replace('.', '_');
            if (kind is null) continue;
            if (MasterDataValidation.Text(resolver["prefix"]) is { } prefix) result[prefix.TrimEnd(':')] = kind;
            if (resolver["aliases"] is JsonArray aliases)
                foreach (var alias in aliases)
                    if (MasterDataValidation.Text(alias) is { } value) result[value.TrimEnd(':')] = kind;
        }
        return result;
    }
}
