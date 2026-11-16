using System.Text.Json;
using System.Text.RegularExpressions;

namespace SkillTreeEditor.Server.Services;

public sealed partial class WorkspaceItemTextures(MasterDataPaths safePaths)
{
    private readonly string pack = safePaths.ResourcePackRoot;
    private readonly object gate = new();
    private (DateTime Modified, long Length)? registryVersion;
    private Dictionary<(string Material, int ModelData), string> models = [];

    private (DateTime Modified, long Length)? guiRegistryVersion;
    private IReadOnlyList<string>? mobHeadRoles;

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

    internal IReadOnlyList<string>? GetMobHeadRoles()
    {
        var registry = new FileInfo(Path.Combine(pack, "gui-icons.json"));
        lock (gate)
        {
            try
            {
                safePaths.RequireNoLinks(registry.FullName);
                (DateTime, long)? version = registry.Exists ? (registry.LastWriteTimeUtc, registry.Length) : null;
                if (version != guiRegistryVersion)
                {
                    mobHeadRoles = version is null ? null : ReadMobHeadRoles(registry.FullName);
                    guiRegistryVersion = mobHeadRoles is null ? null : version;
                }
                return mobHeadRoles;
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
            {
                // Filebase editing also works in a workspace without an accessible resource pack.
                guiRegistryVersion = null;
                mobHeadRoles = null;
                return null;
            }
        }
    }

    public string? FindMobHeadPath(string role)
    {
        if (GetMobHeadRoles()?.Contains(role, StringComparer.Ordinal) != true) return null;

        // The pack builder uses this PNG for both Java and Bedrock.
        var texture = Path.Combine(pack, "assets", "astralrecord", "textures", "item", "gui", "gui_" + role + ".png");
        safePaths.RequireNoLinks(texture);
        return File.Exists(texture) ? texture : null;
    }

    internal static bool IsMobHeadRole(string role) => MobHeadRole().IsMatch(role);

    private static IReadOnlyList<string>? ReadMobHeadRoles(string registry)
    {
        using var stream = File.OpenRead(registry);
        using var document = JsonDocument.Parse(stream);
        if (document.RootElement.ValueKind != JsonValueKind.Object
            || !document.RootElement.TryGetProperty("icons", out var icons)
            || icons.ValueKind != JsonValueKind.Array) return null;

        var roles = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var icon in icons.EnumerateArray())
        {
            if (icon.ValueKind == JsonValueKind.Object
                && icon.TryGetProperty("role", out var role) && role.ValueKind == JsonValueKind.String
                && role.GetString() is { } name && IsMobHeadRole(name)
                && icon.TryGetProperty("base_item", out var material) && material.ValueKind == JsonValueKind.String
                && material.GetString() == "minecraft:paper")
                roles.Add(name);
        }
        return roles.ToArray();
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

    [GeneratedRegex(@"\Amob_head_[a-z0-9]+(?:_[a-z0-9]+)*\z", RegexOptions.CultureInvariant)]
    private static partial Regex MobHeadRole();

    [GeneratedRegex("^[a-z0-9_]+$", RegexOptions.CultureInvariant)]
    private static partial Regex AssetId();
}
