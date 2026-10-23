using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>Captures the existing editor's parsed masters without exposing a write path.</summary>
public sealed class BalanceLabCatalog(MasterDataPaths paths, MasterDataService masters, WorkspacePaths workspace, MasterDataValidation validation)
{
    public const string ModelVersion = "astral-balance/2.0.0";
    public static JsonObject Limits => new()
    {
        ["maxTrials"] = 500, ["maxSeconds"] = 600, ["maxBuilds"] = 4,
        ["maxTickWork"] = 2_000_000, ["wallSeconds"] = 30
    };

    public async Task<JsonObject> ReadAsync(CancellationToken token)
    {
        var documents = new JsonArray();
        var diagnostics = new JsonArray();
        var manifest = new JsonArray();
        foreach (var file in paths.Enumerate().Where(MasterDataPaths.IsData)
                     .Where(file => !file.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase))
                     .Order(StringComparer.Ordinal))
        {
            token.ThrowIfCancellationRequested();
            var relative = paths.Relative(file);
            var document = await masters.ReadAsync(relative, token);
            manifest.Add(new JsonObject { ["path"] = relative, ["hash"] = document.Revision });
            if (document.Content is JsonObject content && document.Issues.Count == 0)
                documents.Add(new JsonObject
                {
                    ["path"] = relative, ["kind"] = MasterDataService.Kind(relative),
                    ["revision"] = document.Revision, ["content"] = content.DeepClone()
                });
            else diagnostics.Add(new JsonObject { ["path"] = relative, ["message"] = "解析できないマスターです。" });
        }
        // Fingerprint actual formula sources as well as the master input. Source contents stay local.
        var sources = new JsonArray();
        var sourceRoot = Path.Combine(workspace.WorkspaceRoot, "10_plugin", "AstralRecord", "src", "main");
        paths.RequireNoLinks(sourceRoot);
        if (Directory.Exists(sourceRoot))
            foreach (var file in Directory.EnumerateFiles(sourceRoot, "*", new EnumerationOptions
                     { RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint })
                         .Where(file => Path.GetExtension(file) is ".java" or ".kt" || file == workspace.PluginConfig)
                         .Order(StringComparer.Ordinal))
            {
                paths.RequireNoLinks(file);
                sources.Add(new JsonObject
                {
                    ["path"] = Path.GetRelativePath(workspace.WorkspaceRoot, file).Replace('\\', '/'),
                    ["hash"] = Hash(await File.ReadAllBytesAsync(file, token))
                });
            }
        var result = new JsonObject
        {
            ["modelVersion"] = ModelVersion,
            ["masterHash"] = Hash(Encoding.UTF8.GetBytes(manifest.ToJsonString())),
            ["sourceHash"] = Hash(Encoding.UTF8.GetBytes(sources.ToJsonString())),
            ["manifest"] = manifest, ["masters"] = documents, ["sources"] = sources,
            ["diagnostics"] = diagnostics, ["limits"] = Limits
        };
        var skills = BalanceSkills.Catalog(result);
        result["skillCatalog"] = skills;
        result["supportedSkillIds"] = new JsonArray(skills.OfType<JsonObject>()
            .Where(skill => skill["supported"]!.GetValue<bool>()).Select(skill => skill["id"]!.DeepClone()).ToArray());
        return result;
    }

    public static JsonObject ApplyProposal(JsonObject catalog, JsonArray overrides, out JsonArray changes)
    {
        var copy = catalog.DeepClone().AsObject();
        changes = [];
        var documents = copy["masters"]!.AsArray().OfType<JsonObject>()
            .ToDictionary(value => value["path"]!.ToString(), StringComparer.Ordinal);
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var value in overrides)
        {
            var path = value?["path"]?.ToString() ?? "";
            if (!seen.Add(path)) throw new ArgumentException("同じ調整案のパスを重複指定できません。");
            if (!documents.TryGetValue(path, out var original) || value?["content"] is not JsonObject content)
                throw new ArgumentException("調整案には既存マスターのパスとオブジェクトを指定してください。");
            foreach (var identity in new[] { "id", "nodeId", "structureId" })
                if (!JsonNode.DeepEquals(original["content"]?[identity], content[identity]))
                    throw new ArgumentException("調整案ではマスターのIDを変更できません。");
            Diff(path, "", original["content"], content, changes);
            original["content"] = content.DeepClone();
            original["revision"] = Hash(Encoding.UTF8.GetBytes(content.ToJsonString()));
        }
        copy["masterHash"] = Hash(Encoding.UTF8.GetBytes(copy["masters"]!.ToJsonString()));
        return copy;
    }

    public async Task ValidateProposalAsync(JsonObject catalog, JsonObject request, CancellationToken token)
    {
        if (request["overrides"] is not JsonArray { Count: > 0 } overrides) return;
        // Validate identity/path before passing an existing file's isolated JSON to the editor validator.
        _ = ApplyProposal(catalog, overrides, out _);
        foreach (var proposal in overrides.OfType<JsonObject>())
        {
            var report = await validation.ValidateAsync(proposal["path"]!.ToString(), proposal["content"]!.ToJsonString(), token);
            if (!report.IsValid)
                throw new ArgumentException("調整案がマスター定義に適合しません: " + string.Join(" / ", report.Issues.Where(issue => issue.Severity == "error").Take(5).Select(issue => issue.Path + " " + issue.Message)));
        }
    }

    private static void Diff(string path, string pointer, JsonNode? before, JsonNode? after, JsonArray changes)
    {
        if (JsonNode.DeepEquals(before, after)) return;
        if (before is JsonObject first && after is JsonObject second)
        {
            foreach (var key in first.Select(value => value.Key).Union(second.Select(value => value.Key)).Order(StringComparer.Ordinal))
                Diff(path, pointer + "/" + key.Replace("~", "~0").Replace("/", "~1"), first[key], second[key], changes);
        }
        else if (before is JsonArray firstArray && after is JsonArray secondArray && firstArray.Count == secondArray.Count)
            for (var i = 0; i < firstArray.Count; i++) Diff(path, pointer + "/" + i, firstArray[i], secondArray[i], changes);
        else
        {
            if (changes.Count >= 10_000) throw new ArgumentException("調整差分が多すぎます。対象を絞ってください。");
            changes.Add(new JsonObject { ["path"] = path, ["pointer"] = pointer, ["before"] = before?.DeepClone(), ["after"] = after?.DeepClone() });
        }
    }

    private static string Hash(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
}
