using SkillTreeEditor.Server.Models;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace SkillTreeEditor.Server.Services;

/** SkillTree の表示シミュレーションに必要なクラス階層だけを filebase から読み取ります。 */
public sealed class ClassMasterCatalog(WorkspacePaths paths)
{
    private readonly IDeserializer _deserializer = new DeserializerBuilder()
        .WithNamingConvention(CamelCaseNamingConvention.Instance)
        .IgnoreUnmatchedProperties()
        .Build();

    public async Task<IReadOnlyList<ClassMasterSummary>> ReadAllAsync(CancellationToken token)
    {
        if (!Directory.Exists(paths.Classes))
            throw new DirectoryNotFoundException($"Class master directory was not found: {paths.Classes}");

        var summaries = new Dictionary<string, ClassMasterSummary>(StringComparer.OrdinalIgnoreCase);
        var files = Directory.EnumerateFiles(paths.Classes, "*.yml", SearchOption.TopDirectoryOnly)
            .Concat(Directory.EnumerateFiles(paths.Classes, "*.yaml", SearchOption.TopDirectoryOnly))
            .OrderBy(file => file, StringComparer.OrdinalIgnoreCase);
        foreach (var file in files)
        {
            token.ThrowIfCancellationRequested();
            var yaml = await File.ReadAllTextAsync(file, token);
            var master = _deserializer.Deserialize<ClassMasterYaml>(yaml);
            if (master is null || string.IsNullOrWhiteSpace(master.Id))
                continue;

            var id = master.Id.Trim().ToLowerInvariant();
            var summary = new ClassMasterSummary(
                id,
                string.IsNullOrWhiteSpace(master.Name) ? id : master.Name,
                master.UnlockClassLevel
                    .Where(requirement => !string.IsNullOrWhiteSpace(requirement.ClassId))
                    .Select(requirement => requirement.ClassId.Trim().ToLowerInvariant())
                    .Distinct(StringComparer.OrdinalIgnoreCase)
                    .ToArray());
            if (!summaries.TryAdd(id, summary))
                throw new InvalidOperationException($"Duplicate class master id '{id}'.");
        }

        return summaries.Values.OrderBy(summary => summary.Id, StringComparer.Ordinal).ToArray();
    }

    private sealed class ClassMasterYaml
    {
        public string Id { get; init; } = string.Empty;
        public string Name { get; init; } = string.Empty;
        public List<ClassRequirementYaml> UnlockClassLevel { get; init; } = [];
    }

    private sealed class ClassRequirementYaml
    {
        [YamlMember(Alias = "class")]
        public string ClassId { get; init; } = string.Empty;
    }
}
