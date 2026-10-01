using System.Text.Json;
using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Json.Schema;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

public sealed class MasterDataValidation(MasterDataPaths paths, MasterDataCodec codec, MasterDataCatalog catalog,
    ValidationService skillTreeValidation)
{
    public async Task<MasterDataReport> ValidateAsync(string path, string raw, CancellationToken token)
    {
        var full = paths.Resolve(path);
        var parsed = codec.Parse(path, raw);
        var issues = parsed.Issues.ToList();
        if (issues.Count > 0) return new(false, issues);
        var content = parsed.Content;
        if (path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase))
        {
            await ValidateSchemaAsync(path, content, new("", "", "", 0, [], [], [], [], null), issues, token);
            return new(issues.All(issue => issue.Severity != "error"), issues);
        }
        if (content is not JsonObject)
            return new(false, [new("error", "ROOT_SHAPE", "", "マスタ文書のルートはオブジェクトで記載してください。")]);
        if (path.StartsWith("35.features.skilltree/nodes/", MasterDataPaths.Comparison) && content is JsonObject node)
        {
            var report = await skillTreeValidation.ValidateNodeDocumentAsync(node, Path.GetFileName(path), token);
            issues.AddRange(report.Issues.Select(issue => new MasterDataIssue(issue.Severity, issue.Code, issue.Path ?? "", issue.Message)));
        }
        else if (path.StartsWith("35.features.skilltree/structures/", MasterDataPaths.Comparison) && content is JsonObject structure)
        {
            var report = await skillTreeValidation.ValidateStructureDocumentAsync(structure, Path.GetFileName(path), token);
            issues.AddRange(report.Issues.Select(issue => new MasterDataIssue(issue.Severity, issue.Code, issue.Path ?? "", issue.Message)));
        }
        else
        {
            var category = paths.Relative(Path.GetDirectoryName(full)!);
            var metadata = await catalog.GetAsync(category, token);
            foreach (var field in metadata.Fields)
            {
                var segments = field.Path.TrimStart('/').Split('/').Select(Unescape).ToArray();
                ValidateField(content, segments, 0, "", field, issues);
            }
            await ValidateSchemaAsync(path, content, metadata, issues, token);
            if (path != "config.yml")
                await ValidateReferencesAsync(content, issues, token);
        }
        if (content is JsonObject obj)
        {
            var id = Text(obj["id"]);
            if (id is { Length: > 0 })
            {
                var family = path.Split('/')[0];
                foreach (var other in paths.Enumerate(family).Where(MasterDataPaths.IsData))
                {
                    if (other.Equals(full, MasterDataPaths.Comparison) || other.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
                    var otherContent = codec.Parse(other, await File.ReadAllTextAsync(other, token)).Content;
                    if (otherContent is JsonObject otherObject && Text(otherObject["id"]) == id)
                        issues.Add(new("error", "DUPLICATE_ID", "/id", $"ID '{id}' は {paths.Relative(other)} で使用されています。"));
                }
            }
            var code = MasterDataCatalog.ItemCode(paths.Relative(Path.GetDirectoryName(full)!));
            if (code is not null)
            {
                if (id is null || !Regex.IsMatch(id, "^" + code + "[a-z][0-9]{5}$") || id.EndsWith("00000", StringComparison.Ordinal))
                    issues.Add(new("error", "ITEM_ID_FORMAT", "/id", $"ID は {code} + a～z + 00001～99999 の形式です。z はデバッグ専用です。"));
                var logical = Path.GetFileName(Path.GetDirectoryName(full)!).Split('.', 2)[1].ToLowerInvariant();
                if (Text(obj["category"]) != logical)
                    issues.Add(new("error", "ITEM_CATEGORY", "/category", $"category はフォルダに合わせ '{logical}' を指定してください。"));
                var version = Text(obj["schemaVersion"]);
                if (id is not null && version is not null && !Regex.IsMatch(Path.GetFileName(full), "^v" + Regex.Escape(version) + "\\." + Regex.Escape(id) + "\\.[A-Za-z0-9_-]+\\.ya?ml$"))
                    issues.Add(new("error", "ITEM_FILENAME", "/id", "ファイル名は v<schemaVersion>.<id>.<管理用slug>.yml の形式です。"));
            }
        }
        if (path.StartsWith("30.features.skill/", MasterDataPaths.Comparison) && content is JsonObject skill && skill.ContainsKey("params"))
            issues.Add(new("warning", "IMPLEMENTATION_PARAMS_UNVERIFIED", "/params", "実装固有の params の意味・動作は Plugin の implementationId に対応する処理で確認してください。"));
        return new(issues.All(issue => issue.Severity != "error"), issues.Distinct().ToArray());
    }

    private async Task ValidateReferencesAsync(JsonNode? content, List<MasterDataIssue> issues, CancellationToken token)
    {
        var references = ReferenceValues(content).ToArray();
        if (references.Length == 0) return;
        var configurationPath = paths.Resolve("config.yml");
        if (!File.Exists(configurationPath)) return;
        var config = codec.Parse("config.yml", await File.ReadAllTextAsync(configurationPath, token)).Content as JsonObject;
        if (config?["referenceResolver"] is not JsonArray resolvers) return;
        var prefixKinds = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var resolver in resolvers.OfType<JsonObject>())
        {
            var kind = Text(resolver["database"])?.Replace('.', '_');
            if (kind is null) continue;
            if (Text(resolver["prefix"]) is { } prefix) prefixKinds[prefix.TrimEnd(':')] = kind;
            if (resolver["aliases"] is JsonArray aliases)
                foreach (var alias in aliases)
                    if (Text(alias) is { } value) prefixKinds[value.TrimEnd(':')] = kind;
        }
        var wantedKinds = references.Select(reference => reference.Value.Split(':', 2)[0])
            .Where(prefixKinds.ContainsKey).Select(prefix => prefixKinds[prefix]).ToHashSet(StringComparer.Ordinal);
        var identifiers = new HashSet<string>(StringComparer.Ordinal);
        foreach (var file in paths.Enumerate().Where(MasterDataPaths.IsData))
        {
            var kind = MasterDataService.Kind(paths.Relative(file));
            if (!wantedKinds.Contains(kind) || file.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
            var parsed = codec.Parse(file, await File.ReadAllTextAsync(file, token));
            if (parsed.Content is JsonObject obj && Text(obj["id"]) is { } id) identifiers.Add(kind + ":" + id);
        }
        foreach (var reference in references)
        {
            var parts = reference.Value.Split(':', 2);
            if (parts.Length != 2) continue;
            if (prefixKinds.TryGetValue(parts[0], out var kind))
            {
                if (!identifiers.Contains(kind + ":" + parts[1]))
                    issues.Add(new("error", "REFERENCE_NOT_FOUND", reference.Pointer, $"参照 '{reference.Value}' に対応するマスタがありません。"));
            }
            else if (reference.IsRefKey)
                issues.Add(new("warning", "REFERENCE_PREFIX_UNVERIFIED", reference.Pointer, $"参照接頭辞 '{parts[0]}' は config.yml の referenceResolver で確認できません。"));
        }
    }

    private static IEnumerable<(string Pointer, string Value, bool IsRefKey)> ReferenceValues(JsonNode? node, string pointer = "", string key = "")
    {
        if (node is JsonObject obj)
            foreach (var pair in obj)
                foreach (var child in ReferenceValues(pair.Value, pointer + "/" + Escape(pair.Key), pair.Key)) yield return child;
        else if (node is JsonArray array)
            for (var index = 0; index < array.Count; index++)
                foreach (var child in ReferenceValues(array[index], pointer + "/" + index, key)) yield return child;
        else if (node?.GetValueKind() == JsonValueKind.String && Text(node) is { } value
                 && value.Contains(':') && (key == "ref" || key.EndsWith("Id", StringComparison.Ordinal) || key is "usableSkills" or "allowedSigilIds"))
            yield return (pointer, value, key == "ref");
    }

    private async Task ValidateSchemaAsync(string path, JsonNode? content, MasterDataCategory category,
        List<MasterDataIssue> issues, CancellationToken token)
    {
        try
        {
            JsonNode? schemaNode = null;
            if (path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) schemaNode = content;
            else if (content is JsonObject obj && obj["$schema"] is JsonValue reference && reference.GetValueKind() == JsonValueKind.String)
            {
                var value = reference.GetValue<string>();
                if (Uri.TryCreate(value, UriKind.Absolute, out _))
                    issues.Add(new("warning", "REMOTE_SCHEMA", "/$schema", "外部 JSON Schema は取得しません。ローカル定義書を確認してください。"));
                else
                {
                    var relative = paths.Relative(Path.GetFullPath(Path.Combine(Path.GetDirectoryName(paths.Resolve(path))!, value)));
                    var fullSchema = paths.Resolve(relative);
                    schemaNode = JsonNode.Parse(await File.ReadAllTextAsync(fullSchema, token));
                }
            }
            else if (category.JsonSchemas.Count == 1) schemaNode = category.JsonSchemas[0].Schema;
            if (schemaNode is null) return;
            using var schemaDocument = JsonDocument.Parse(schemaNode.ToJsonString());
            var schema = JsonSchema.Build(schemaDocument.RootElement, new BuildOptions { SchemaRegistry = new SchemaRegistry() });
            if (path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) return;
            using var instance = JsonDocument.Parse(content?.ToJsonString() ?? "null");
            var evaluated = schema.Evaluate(instance.RootElement, new EvaluationOptions { OutputFormat = OutputFormat.List });
            if (!evaluated.IsValid)
                foreach (var result in evaluated.Details ?? [])
                    foreach (var error in result.Errors ?? [])
                        issues.Add(new("error", "JSON_SCHEMA", result.InstanceLocation.ToString(), error.Value));
        }
        catch (Exception exception) when (exception is JsonException or JsonSchemaException or IOException or ArgumentException or InvalidOperationException or UnauthorizedAccessException)
        {
            issues.Add(new("error", "SCHEMA_INVALID", "/$schema", exception.Message));
        }
    }

    private static void ValidateField(JsonNode? current, string[] segments, int index, string pointer,
        MasterDataField field, List<MasterDataIssue> issues)
    {
        if (index == segments.Length)
        {
            if (current is null)
            {
                if (field.Required) issues.Add(new("error", "FIELD_REQUIRED", pointer, $"{field.Label} ({field.Key}) は null にできません。"));
                return;
            }
            if (!MatchesType(current, field))
                issues.Add(new("error", "FIELD_TYPE", pointer, $"{field.Label} ({field.Key}) は {field.Type} で記載してください。"));
            return;
        }
        var segment = segments[index];
        if (segment == "*")
        {
            if (current is JsonArray array)
                for (var child = 0; child < array.Count; child++) ValidateField(array[child], segments, index + 1, pointer + "/" + child, field, issues);
            else if (current is JsonObject map)
                foreach (var pair in map) ValidateField(pair.Value, segments, index + 1, pointer + "/" + Escape(pair.Key), field, issues);
            return;
        }
        if (current is not JsonObject obj) return;
        if (obj.TryGetPropertyValue(segment, out var childNode))
            ValidateField(childNode, segments, index + 1, pointer + "/" + Escape(segment), field, issues);
        else if (index == segments.Length - 1 && field.Required)
            issues.Add(new("error", "FIELD_REQUIRED", pointer + "/" + Escape(segment), $"{field.Label} ({field.Key}) は必須です。"));
    }

    private static bool MatchesType(JsonNode node, MasterDataField field)
    {
        var type = field.Type;
        var description = field.Description;
        var alternatives = type.Split('/');
        return alternatives.Any(part => part.Trim().ToLowerInvariant() switch
        {
            "string" => node.GetValueKind() == JsonValueKind.String
                || (description.Contains("固定値", StringComparison.Ordinal) || field.Key.EndsWith("amount", StringComparison.Ordinal)) && node.GetValueKind() == JsonValueKind.Number
                || node is JsonObject reference && reference.Count == 1 && reference["ref"]?.GetValueKind() == JsonValueKind.String
                    && (description.Contains("参照", StringComparison.Ordinal) || field.Key.EndsWith("Id", StringComparison.Ordinal)),
            "integer" or "int" or "long" => node.GetValueKind() == JsonValueKind.Number && decimal.TryParse(node.ToJsonString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var value) && value == decimal.Truncate(value),
            "float" or "double" or "number" or "decimal" => node.GetValueKind() == JsonValueKind.Number,
            "boolean" or "bool" => node.GetValueKind() is JsonValueKind.True or JsonValueKind.False,
            "object" or "map" => node is JsonObject,
            "array" => node is JsonArray,
            "ref" => node.GetValueKind() == JsonValueKind.String || node is JsonObject obj && obj["ref"]?.GetValueKind() == JsonValueKind.String,
            var value when value.EndsWith("[]", StringComparison.Ordinal) => node is JsonArray,
            var value when value.StartsWith("list", StringComparison.Ordinal) => node is JsonArray,
            var value when value.StartsWith("map", StringComparison.Ordinal) => node is JsonObject,
            _ => true // undocumented or union/custom types stay editable
        });
    }

    public static string? Text(JsonNode? node) => node is JsonValue ? node.ToString() : null;
    public static string Escape(string key) => key.Replace("~", "~0", StringComparison.Ordinal).Replace("/", "~1", StringComparison.Ordinal);
    private static string Unescape(string key) => key.Replace("~1", "/", StringComparison.Ordinal).Replace("~0", "~", StringComparison.Ordinal);
}
