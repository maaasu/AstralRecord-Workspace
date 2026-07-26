using SkillTreeEditor.Server.Models;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace SkillTreeEditor.Server.Services;

/** マスターデータで使用できる共有タグ定義を読み取ります。 */
public sealed class MasterTagCatalog(WorkspacePaths paths)
{
    private readonly IDeserializer _deserializer = new DeserializerBuilder()
        .WithNamingConvention(CamelCaseNamingConvention.Instance)
        .IgnoreUnmatchedProperties()
        .Build();

    /// <summary>共有タグをIDの大文字・小文字を維持して読み取ります。</summary>
    public async Task<IReadOnlyList<MasterTagSummary>> ReadAllAsync(CancellationToken token)
    {
        if (!File.Exists(paths.TagCatalog))
            throw new FileNotFoundException($"Master tag catalog was not found: {paths.TagCatalog}", paths.TagCatalog);

        var yaml = await File.ReadAllTextAsync(paths.TagCatalog, token);
        var catalog = _deserializer.Deserialize<TagCatalogYaml>(yaml)
                      ?? throw new InvalidOperationException("Master tag catalog is empty.");
        if (catalog.SchemaVersion != 1)
            throw new InvalidOperationException($"Unsupported master tag catalog schemaVersion '{catalog.SchemaVersion}'.");

        var categories = RequireUniqueIds(catalog.Categories.Select(value => value.Id), "category");
        var targets = RequireUniqueIds(catalog.Targets.Select(value => value.Id), "target");
        var summaries = new Dictionary<string, MasterTagSummary>(StringComparer.Ordinal);
        foreach (var tag in catalog.Tags)
        {
            RequireExactId(tag.Id, "tag");
            if (string.IsNullOrWhiteSpace(tag.DisplayName))
                throw new InvalidOperationException($"Master tag '{tag.Id}' has no displayName.");
            if (string.IsNullOrWhiteSpace(tag.Description))
                throw new InvalidOperationException($"Master tag '{tag.Id}' has no description.");
            if (!categories.Contains(tag.Category))
                throw new InvalidOperationException($"Master tag '{tag.Id}' references unknown category '{tag.Category}'.");
            if (tag.AppliesTo.Count == 0)
                throw new InvalidOperationException($"Master tag '{tag.Id}' has no appliesTo target.");
            if (tag.AppliesTo.Distinct(StringComparer.Ordinal).Count() != tag.AppliesTo.Count)
                throw new InvalidOperationException($"Master tag '{tag.Id}' has duplicate appliesTo targets.");
            var unknownTarget = tag.AppliesTo.FirstOrDefault(target => !targets.Contains(target));
            if (unknownTarget is not null)
                throw new InvalidOperationException($"Master tag '{tag.Id}' references unknown target '{unknownTarget}'.");

            var summary = new MasterTagSummary(
                tag.Id,
                tag.DisplayName.Trim(),
                tag.Description.Trim(),
                tag.Category,
                tag.AppliesTo.ToArray());
            if (!summaries.TryAdd(tag.Id, summary))
                throw new InvalidOperationException($"Duplicate master tag id '{tag.Id}'.");
        }

        return summaries.Values.OrderBy(value => value.Id, StringComparer.Ordinal).ToArray();
    }

    private static HashSet<string> RequireUniqueIds(IEnumerable<string> values, string kind)
    {
        var result = new HashSet<string>(StringComparer.Ordinal);
        foreach (var value in values)
        {
            RequireExactId(value, kind);
            if (!result.Add(value))
                throw new InvalidOperationException($"Duplicate master tag {kind} id '{value}'.");
        }
        return result;
    }

    private static void RequireExactId(string value, string kind)
    {
        if (string.IsNullOrWhiteSpace(value) || !string.Equals(value, value.Trim(), StringComparison.Ordinal))
            throw new InvalidOperationException($"Master tag {kind} id must be a non-empty string without surrounding whitespace.");
    }

    private sealed class TagCatalogYaml
    {
        public int SchemaVersion { get; init; }
        public List<NamedDefinitionYaml> Categories { get; init; } = [];
        public List<NamedDefinitionYaml> Targets { get; init; } = [];
        public List<TagDefinitionYaml> Tags { get; init; } = [];
    }

    private sealed class NamedDefinitionYaml
    {
        public string Id { get; init; } = string.Empty;
    }

    private sealed class TagDefinitionYaml
    {
        public string Id { get; init; } = string.Empty;
        public string DisplayName { get; init; } = string.Empty;
        public string Description { get; init; } = string.Empty;
        public string Category { get; init; } = string.Empty;
        public List<string> AppliesTo { get; init; } = [];
    }
}
