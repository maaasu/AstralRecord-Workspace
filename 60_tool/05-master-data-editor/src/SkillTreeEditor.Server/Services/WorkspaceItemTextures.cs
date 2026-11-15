using System.Text.Json;
using System.Text.RegularExpressions;

namespace SkillTreeEditor.Server.Services;

public sealed partial class WorkspaceItemTextures(WorkspacePaths paths, MasterDataPaths safePaths)
{
    private readonly string pack = Path.Combine(paths.WorkspaceRoot, "50_resourcepack");
    private readonly object gate = new();
    private (DateTime Modified, long Length)? registryVersion;
    private Dictionary<(string Material, int ModelData), string> models = [];

    public string? FindPath(string material, int modelData)
    {
        var key = ("minecraft:" + MinecraftIconService.NormalizeMaterial(material), modelData);
        var registry = new FileInfo(Path.Combine(pack, "item-models.json"));
        safePaths.RequireNoLinks(registry.FullName);
        if (!registry.Exists) return null;

        string? id;
        lock (gate)
        {
            var version = (registry.LastWriteTimeUtc, registry.Length);
            if (version != registryVersion)
            {
                models = ReadModels(registry.FullName);
                registryVersion = version;
            }
            id = models.GetValueOrDefault(key);
        }
        if (id is null) return null;
        var texture = Path.Combine(pack, "bedrock", "textures", "items", id + ".png");
        safePaths.RequireNoLinks(texture);
        return File.Exists(texture) ? texture : null;
    }

    private static Dictionary<(string, int), string> ReadModels(string registry)
    {
        try
        {
            using var stream = File.OpenRead(registry);
            using var document = JsonDocument.Parse(stream);
            if (document.RootElement.ValueKind != JsonValueKind.Object
                || !document.RootElement.TryGetProperty("items", out var items)
                || items.ValueKind != JsonValueKind.Array) return [];

            var result = new Dictionary<(string, int), string>();
            foreach (var item in items.EnumerateArray())
            {
                if (item.ValueKind != JsonValueKind.Object
                    || !item.TryGetProperty("base_item", out var material) || material.ValueKind != JsonValueKind.String
                    || !item.TryGetProperty("custom_model_data", out var model) || model.ValueKind != JsonValueKind.Number
                    || !item.TryGetProperty("id", out var name) || name.ValueKind != JsonValueKind.String
                    || !model.TryGetInt32(out var code) || name.GetString() is not { } id || !AssetId().IsMatch(id)) continue;

                result.TryAdd((material.GetString()!, code), id);
            }
            return result;
        }
        catch (JsonException) { return []; }
    }

    [GeneratedRegex("^[a-z0-9_]+$", RegexOptions.CultureInvariant)]
    private static partial Regex AssetId();
}
