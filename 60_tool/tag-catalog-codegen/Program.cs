using System.Collections;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

var options = GeneratorOptions.Parse(args);
var sourcePath = Path.Combine(options.RepositoryRoot, "40_filebase", "76.shared.tag", "v1.tags.yml");
if (!File.Exists(sourcePath))
    throw new FileNotFoundException($"Tag catalog was not found: {sourcePath}");

var deserializer = new DeserializerBuilder()
    .WithNamingConvention(CamelCaseNamingConvention.Instance)
    .Build();
var catalog = deserializer.Deserialize<TagCatalog>(await File.ReadAllTextAsync(sourcePath))
    ?? throw new InvalidDataException("Tag catalog root is empty.");
CatalogValidator.Validate(catalog);
await FilebaseTagAudit.ValidateAsync(options.RepositoryRoot, catalog, deserializer);

var targets = new Dictionary<string, string>
{
    [Path.Combine(
        options.RepositoryRoot,
        "10_plugin", "AstralRecord", "src", "main", "java", "io", "github", "maaasu", "astralRecord",
        "shared", "masterdata", "tag", "MasterTagIds.java")] = CodeTemplates.Java(catalog),
    [Path.Combine(
        options.RepositoryRoot,
        "20_api", "AstralRecordApi", "AstralRecordApi", "Models", "MasterTagIds.generated.cs")] =
        CodeTemplates.CSharp(catalog),
    [Path.Combine(
        options.RepositoryRoot,
        "60_tool", "skilltree-editor", "src", "SkillTreeEditor.Client", "src", "data", "masterTags.generated.ts")] =
        CodeTemplates.TypeScript(catalog),
};

var staleTargets = new List<string>();
foreach (var (target, generated) in targets)
{
    var normalized = generated.Replace("\r\n", "\n", StringComparison.Ordinal);
    if (!normalized.EndsWith('\n'))
        normalized += "\n";

    var existing = File.Exists(target)
        ? (await File.ReadAllTextAsync(target)).Replace("\r\n", "\n", StringComparison.Ordinal)
        : null;
    if (string.Equals(existing, normalized, StringComparison.Ordinal))
        continue;

    if (options.CheckOnly)
    {
        staleTargets.Add(Path.GetRelativePath(options.RepositoryRoot, target));
        continue;
    }

    Directory.CreateDirectory(Path.GetDirectoryName(target)!);
    await File.WriteAllTextAsync(target, normalized, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
    Console.WriteLine($"generated: {Path.GetRelativePath(options.RepositoryRoot, target)}");
}

if (staleTargets.Count > 0)
{
    Console.Error.WriteLine("Tag type generated files are stale:");
    foreach (var target in staleTargets)
        Console.Error.WriteLine($"  - {target}");
    Console.Error.WriteLine("Run .\\60_tool\\generate-tag-types.ps1 and commit the generated files.");
    Environment.ExitCode = 2;
}
else if (options.CheckOnly)
{
    Console.WriteLine("Tag catalog, filebase usages, and generated files are up to date.");
}

internal sealed record GeneratorOptions(string RepositoryRoot, bool CheckOnly)
{
    public static GeneratorOptions Parse(string[] arguments)
    {
        string? repositoryRoot = null;
        var checkOnly = false;
        for (var index = 0; index < arguments.Length; index++)
        {
            switch (arguments[index])
            {
                case "--repo-root" when index + 1 < arguments.Length:
                    repositoryRoot = arguments[++index];
                    break;
                case "--check":
                    checkOnly = true;
                    break;
                default:
                    throw new ArgumentException($"Unknown or incomplete argument: {arguments[index]}");
            }
        }

        if (string.IsNullOrWhiteSpace(repositoryRoot))
            throw new ArgumentException("--repo-root is required.");
        return new GeneratorOptions(Path.GetFullPath(repositoryRoot), checkOnly);
    }
}

internal sealed class TagCatalog
{
    public int SchemaVersion { get; init; }
    public List<NamedDefinition> Categories { get; init; } = [];
    public List<NamedDefinition> Targets { get; init; } = [];
    public List<TagDefinition> Tags { get; init; } = [];
}

internal sealed class NamedDefinition
{
    public string Id { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
    public string Description { get; init; } = string.Empty;
}

internal sealed class TagDefinition
{
    public string Id { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
    public string Description { get; init; } = string.Empty;
    public string Category { get; init; } = string.Empty;
    public List<string> AppliesTo { get; init; } = [];
}

internal static partial class CatalogValidator
{
    [GeneratedRegex("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$", RegexOptions.CultureInvariant)]
    private static partial Regex DefinitionIdentifierPattern();

    [GeneratedRegex("^[A-Za-z][A-Za-z0-9_-]*$", RegexOptions.CultureInvariant)]
    private static partial Regex TagIdentifierPattern();

    public static void Validate(TagCatalog catalog)
    {
        if (catalog.SchemaVersion != 1)
            throw new InvalidDataException($"Unsupported schemaVersion: {catalog.SchemaVersion}");
        var categoryIds = ValidateNamedDefinitions(catalog.Categories, "category");
        var targetIds = ValidateNamedDefinitions(catalog.Targets, "target");
        if (catalog.Tags.Count == 0)
            throw new InvalidDataException("tags must contain at least one definition.");

        var tagIds = new HashSet<string>(StringComparer.Ordinal);
        var generatedNames = new HashSet<string>(StringComparer.Ordinal);
        foreach (var tag in catalog.Tags)
        {
            if (!TagIdentifierPattern().IsMatch(tag.Id))
                throw new InvalidDataException($"Invalid tag id '{tag.Id}'.");
            if (!tagIds.Add(tag.Id))
                throw new InvalidDataException($"Duplicate tag id: {tag.Id}");
            RequireText(tag.DisplayName, $"Tag '{tag.Id}' requires displayName.");
            RequireText(tag.Description, $"Tag '{tag.Id}' requires description.");
            if (!categoryIds.Contains(tag.Category))
                throw new InvalidDataException($"Tag '{tag.Id}' references unknown category '{tag.Category}'.");
            if (tag.AppliesTo.Count == 0)
                throw new InvalidDataException($"Tag '{tag.Id}' requires at least one appliesTo target.");

            var appliesTo = new HashSet<string>(StringComparer.Ordinal);
            foreach (var target in tag.AppliesTo)
            {
                if (!targetIds.Contains(target))
                    throw new InvalidDataException($"Tag '{tag.Id}' references unknown target '{target}'.");
                if (!appliesTo.Add(target))
                    throw new InvalidDataException($"Tag '{tag.Id}' contains duplicate target '{target}'.");
            }

            var generatedName = $"{tag.Category}.{CodeName(tag.Id)}";
            if (!generatedNames.Add(generatedName))
                throw new InvalidDataException($"Generated constant name collides: {generatedName}");
        }
    }

    public static string CodeName(string id)
    {
        var name = Regex.Replace(id.ToUpperInvariant(), "[^A-Z0-9]+", "_").Trim('_');
        if (name.Length == 0 || char.IsDigit(name[0]))
            name = $"TAG_{name}";
        return name;
    }

    public static string TypeName(string id) => string.Concat(
        id.Split('_', StringSplitOptions.RemoveEmptyEntries)
            .Select(part => char.ToUpperInvariant(part[0]) + part[1..].ToLowerInvariant()));

    private static HashSet<string> ValidateNamedDefinitions(List<NamedDefinition> definitions, string kind)
    {
        if (definitions.Count == 0)
            throw new InvalidDataException($"{kind} definitions must not be empty.");
        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var definition in definitions)
        {
            if (!DefinitionIdentifierPattern().IsMatch(definition.Id))
                throw new InvalidDataException($"Invalid {kind} id '{definition.Id}'.");
            if (!ids.Add(definition.Id))
                throw new InvalidDataException($"Duplicate {kind} id: {definition.Id}");
            RequireText(definition.DisplayName, $"{kind} '{definition.Id}' requires displayName.");
            RequireText(definition.Description, $"{kind} '{definition.Id}' requires description.");
        }
        return ids;
    }

    private static void RequireText(string value, string message)
    {
        if (string.IsNullOrWhiteSpace(value))
            throw new InvalidDataException(message);
    }
}

internal static class FilebaseTagAudit
{
    private static readonly IReadOnlyDictionary<string, string> DirectoryTargets =
        new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["10.features.item"] = "EQUIPMENT",
            ["20.features.class"] = "CLASS",
            ["30.features.skill"] = "SKILL",
            ["35.features.skilltree"] = "SKILLTREE_NODE",
            ["40.features.mob"] = "MOB",
            ["42.features.gathering"] = "GATHERING_REQUIRED_TOOL",
            ["85.shared.recipe"] = "RECIPE",
        };

    public static async Task ValidateAsync(string repositoryRoot, TagCatalog catalog, IDeserializer deserializer)
    {
        var filebaseRoot = Path.Combine(repositoryRoot, "40_filebase");
        var definitions = catalog.Tags.ToDictionary(tag => tag.Id, StringComparer.Ordinal);
        var issues = new List<string>();
        foreach (var file in Directory.EnumerateFiles(filebaseRoot, "*", SearchOption.AllDirectories)
                     .Where(IsMasterDataFile)
                     .OrderBy(path => path, StringComparer.OrdinalIgnoreCase))
        {
            var relative = Path.GetRelativePath(filebaseRoot, file);
            var topDirectory = relative.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)[0];
            if (!DirectoryTargets.TryGetValue(topDirectory, out var target))
                continue;

            var text = await File.ReadAllTextAsync(file);
            if (Path.GetExtension(file).Equals(".json", StringComparison.OrdinalIgnoreCase))
            {
                var node = JsonNode.Parse(text);
                VisitJson(node, null, (field, value) => ValidateUsage(relative, field, value, target, definitions, issues));
            }
            else
            {
                var value = deserializer.Deserialize<object>(text);
                VisitYaml(value, null, (field, tag) => ValidateUsage(relative, field, tag, target, definitions, issues));
            }
        }

        if (issues.Count == 0)
            return;
        throw new InvalidDataException("Filebase tag catalog audit failed:\n  - " + string.Join("\n  - ", issues));
    }

    private static bool IsMasterDataFile(string path)
    {
        var extension = Path.GetExtension(path);
        if (!extension.Equals(".json", StringComparison.OrdinalIgnoreCase)
            && !extension.Equals(".yml", StringComparison.OrdinalIgnoreCase)
            && !extension.Equals(".yaml", StringComparison.OrdinalIgnoreCase))
            return false;
        var normalized = path.Replace('\\', '/');
        return !normalized.Contains("/schemas/", StringComparison.OrdinalIgnoreCase)
            && !normalized.Contains("/76.shared.tag/", StringComparison.OrdinalIgnoreCase);
    }

    private static void ValidateUsage(
        string file,
        string field,
        string id,
        string target,
        IReadOnlyDictionary<string, TagDefinition> definitions,
        List<string> issues)
    {
        if (!definitions.TryGetValue(id, out var definition))
        {
            issues.Add($"{file}: {field} uses unknown tag '{id}'.");
            return;
        }
        if (!definition.AppliesTo.Contains(target, StringComparer.Ordinal))
            issues.Add($"{file}: tag '{id}' is not applicable to {target}.");
    }

    private static void VisitJson(JsonNode? node, string? field, Action<string, string> visitor)
    {
        switch (node)
        {
            case JsonObject objectValue:
                foreach (var (key, value) in objectValue)
                {
                    if (IsTagField(key))
                        VisitJson(value, key, visitor);
                    else
                        VisitJson(value, null, visitor);
                }
                break;
            case JsonArray arrayValue:
                foreach (var value in arrayValue)
                    VisitJson(value, field, visitor);
                break;
            case JsonValue value when field is not null && value.TryGetValue<string>(out var tag):
                visitor(field, tag);
                break;
        }
    }

    private static void VisitYaml(object? value, string? field, Action<string, string> visitor)
    {
        if (value is string text)
        {
            if (field is not null)
                visitor(field, text);
            return;
        }
        if (value is IDictionary dictionary)
        {
            foreach (DictionaryEntry entry in dictionary)
            {
                var key = Convert.ToString(entry.Key) ?? string.Empty;
                VisitYaml(entry.Value, IsTagField(key) ? key : null, visitor);
            }
            return;
        }
        if (value is IEnumerable values)
        {
            foreach (var entry in values)
                VisitYaml(entry, field, visitor);
        }
    }

    private static bool IsTagField(string name) =>
        name.Equals("tag", StringComparison.OrdinalIgnoreCase)
        || name.Equals("tags", StringComparison.OrdinalIgnoreCase)
        || name.Equals("requiredToolTags", StringComparison.OrdinalIgnoreCase)
        || name.Equals("targetTags", StringComparison.OrdinalIgnoreCase);
}

internal static class CodeTemplates
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    public static string Java(TagCatalog catalog)
    {
        var builder = Header("java");
        builder.AppendLine("package io.github.maaasu.astralRecord.shared.masterdata.tag;");
        builder.AppendLine();
        builder.AppendLine("import java.util.List;");
        builder.AppendLine("import java.util.Map;");
        builder.AppendLine();
        builder.AppendLine("/** 共有タグカタログから生成されたタグID定数です。 */");
        builder.AppendLine("public final class MasterTagIds {");
        builder.AppendLine("    private MasterTagIds() {");
        builder.AppendLine("    }");
        foreach (var category in catalog.Categories)
        {
            var tags = catalog.Tags.Where(tag => tag.Category == category.Id).ToArray();
            if (tags.Length == 0)
                continue;
            builder.AppendLine();
            builder.Append("    /** ").Append(category.DisplayName).AppendLine("タグです。 */");
            builder.Append("    public static final class ").Append(CatalogValidator.TypeName(category.Id)).AppendLine(" {");
            builder.Append("        private ").Append(CatalogValidator.TypeName(category.Id)).AppendLine("() {");
            builder.AppendLine("        }");
            foreach (var tag in tags)
            {
                builder.AppendLine();
                builder.Append("        /** ").Append(tag.DisplayName).Append(": ").Append(tag.Description).AppendLine(" */");
                builder.Append("        public static final String ").Append(CatalogValidator.CodeName(tag.Id))
                    .Append(" = ").Append(Quote(tag.Id)).AppendLine(";");
            }
            builder.AppendLine("    }");
        }
        builder.AppendLine();
        builder.AppendLine("    /** 共有タグカタログ由来の表示・適用先情報です。 */");
        builder.AppendLine("    public record Definition(");
        builder.AppendLine("        String id,");
        builder.AppendLine("        String displayName,");
        builder.AppendLine("        String description,");
        builder.AppendLine("        String category,");
        builder.AppendLine("        List<String> appliesTo");
        builder.AppendLine("    ) {");
        builder.AppendLine("    }");
        builder.AppendLine();
        builder.AppendLine("    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(");
        for (var index = 0; index < catalog.Tags.Count; index++)
        {
            var tag = catalog.Tags[index];
            builder.Append("        Map.entry(").Append(Quote(tag.Id)).Append(", new Definition(")
                .Append(Quote(tag.Id)).Append(", ").Append(Quote(tag.DisplayName)).Append(", ")
                .Append(Quote(tag.Description)).Append(", ").Append(Quote(tag.Category)).Append(", List.of(")
                .Append(string.Join(", ", tag.AppliesTo.Select(Quote)))
                .AppendLine(index + 1 < catalog.Tags.Count ? ")))," : ")))");
        }
        builder.AppendLine("    );");
        builder.AppendLine();
        builder.AppendLine("    /** 不変IDに対応する共有タグ定義を取得します。 */");
        builder.AppendLine("    public static Definition find(String id) {");
        builder.AppendLine("        return DEFINITIONS.get(id);");
        builder.AppendLine("    }");
        builder.AppendLine("}");
        return builder.ToString();
    }

    public static string CSharp(TagCatalog catalog)
    {
        var builder = Header("csharp");
        builder.AppendLine("#nullable enable");
        builder.AppendLine();
        builder.AppendLine("using System.Collections.ObjectModel;");
        builder.AppendLine();
        builder.AppendLine("namespace AstralRecordApi.Models;");
        builder.AppendLine();
        builder.AppendLine("/// <summary>共有タグID定数。</summary>");
        builder.AppendLine("public static class MasterTagIds");
        builder.AppendLine("{");
        foreach (var category in catalog.Categories)
        {
            var tags = catalog.Tags.Where(tag => tag.Category == category.Id).ToArray();
            if (tags.Length == 0)
                continue;
            builder.Append("    /// <summary>").Append(category.DisplayName).AppendLine("タグ。</summary>");
            builder.Append("    public static class ").Append(CatalogValidator.TypeName(category.Id)).AppendLine();
            builder.AppendLine("    {");
            foreach (var tag in tags)
            {
                builder.Append("        /// <summary>").Append(tag.DisplayName).Append("。</summary>").AppendLine();
                builder.Append("        public const string ").Append(CatalogValidator.CodeName(tag.Id))
                    .Append(" = ").Append(Quote(tag.Id)).AppendLine(";");
            }
            builder.AppendLine("    }");
        }
        builder.AppendLine("}");
        builder.AppendLine();
        builder.AppendLine("/// <summary>共有タグ定義。</summary>");
        builder.AppendLine("public sealed record MasterTagDefinition(");
        builder.AppendLine("    string Id,");
        builder.AppendLine("    string DisplayName,");
        builder.AppendLine("    string Description,");
        builder.AppendLine("    string Category,");
        builder.AppendLine("    IReadOnlyList<string> AppliesTo);");
        builder.AppendLine();
        builder.AppendLine("/// <summary>共有タグカタログ。</summary>");
        builder.AppendLine("public static class MasterTags");
        builder.AppendLine("{");
        builder.AppendLine("    private static readonly IReadOnlyDictionary<string, MasterTagDefinition> Definitions =");
        builder.AppendLine("        new ReadOnlyDictionary<string, MasterTagDefinition>(");
        builder.AppendLine("            new Dictionary<string, MasterTagDefinition>(StringComparer.Ordinal)");
        builder.AppendLine("            {");
        foreach (var tag in catalog.Tags)
        {
            builder.Append("                [").Append(Quote(tag.Id)).Append("] = new(")
                .Append(Quote(tag.Id)).Append(", ").Append(Quote(tag.DisplayName)).Append(", ")
                .Append(Quote(tag.Description)).Append(", ").Append(Quote(tag.Category)).Append(", [")
                .Append(string.Join(", ", tag.AppliesTo.Select(Quote))).AppendLine("]),");
        }
        builder.AppendLine("            });");
        builder.AppendLine();
        builder.AppendLine("    /// <summary>IDをキーとする全タグ定義。</summary>");
        builder.AppendLine("    public static IReadOnlyDictionary<string, MasterTagDefinition> All => Definitions;");
        builder.AppendLine();
        builder.AppendLine("    /// <summary>不変IDに対応するタグ定義を取得します。</summary>");
        builder.AppendLine("    public static bool TryGet(string id, out MasterTagDefinition? definition) =>");
        builder.AppendLine("        Definitions.TryGetValue(id, out definition);");
        builder.AppendLine("}");
        return builder.ToString();
    }

    public static string TypeScript(TagCatalog catalog)
    {
        var builder = Header("typescript");
        builder.Append("export type MasterTagCategoryId = ")
            .Append(string.Join(" | ", catalog.Categories.Select(category => Quote(category.Id))))
            .AppendLine();
        builder.Append("export type MasterTagTargetId = ")
            .Append(string.Join(" | ", catalog.Targets.Select(target => Quote(target.Id))))
            .AppendLine();
        builder.AppendLine();
        builder.AppendLine("export interface MasterTagDefinition {");
        builder.AppendLine("  readonly id: string");
        builder.AppendLine("  readonly displayName: string");
        builder.AppendLine("  readonly description: string");
        builder.AppendLine("  readonly category: MasterTagCategoryId");
        builder.AppendLine("  readonly appliesTo: readonly MasterTagTargetId[]");
        builder.AppendLine("}");
        builder.AppendLine();
        builder.AppendLine("export const MASTER_TAGS = [");
        foreach (var tag in catalog.Tags)
        {
            builder.Append("  { id: ").Append(Quote(tag.Id))
                .Append(", displayName: ").Append(Quote(tag.DisplayName))
                .Append(", description: ").Append(Quote(tag.Description))
                .Append(", category: ").Append(Quote(tag.Category))
                .Append(", appliesTo: [").Append(string.Join(", ", tag.AppliesTo.Select(Quote))).AppendLine("] },");
        }
        builder.AppendLine("] as const satisfies readonly MasterTagDefinition[]");
        builder.AppendLine();
        builder.AppendLine("export type MasterTagId = typeof MASTER_TAGS[number]['id']");
        builder.AppendLine();
        builder.AppendLine("export const MASTER_TAG_BY_ID = new Map<string, MasterTagDefinition>(");
        builder.AppendLine("  MASTER_TAGS.map((tag) => [tag.id, tag]),");
        builder.AppendLine(")");
        builder.AppendLine();
        builder.AppendLine("export const SKILLTREE_NODE_TAGS = MASTER_TAGS.filter(");
        builder.AppendLine("  (tag) => tag.appliesTo.includes('SKILLTREE_NODE' as never),");
        builder.AppendLine(")");
        return builder.ToString();
    }

    private static StringBuilder Header(string language)
    {
        var builder = new StringBuilder();
        var prefix = language == "typescript" ? "//" : "//";
        builder.Append(prefix).AppendLine(" <auto-generated>");
        builder.Append(prefix).AppendLine(" 40_filebase/76.shared.tag/v1.tags.yml から生成されます。直接編集しないでください。");
        builder.Append(prefix).AppendLine(" </auto-generated>");
        builder.AppendLine();
        return builder;
    }

    private static string Quote(string value) => JsonSerializer.Serialize(value, JsonOptions);
}
