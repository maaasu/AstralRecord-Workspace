using SkillTreeEditor.Server.Models;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace SkillTreeEditor.Server.Services;

/** SkillTreeノードの候補と補足表示に必要なスキル情報をfilebaseから読み取ります。 */
public sealed class SkillMasterCatalog(WorkspacePaths paths)
{
    private readonly IDeserializer _deserializer = new DeserializerBuilder()
        .WithNamingConvention(CamelCaseNamingConvention.Instance)
        .IgnoreUnmatchedProperties()
        .Build();

    /// <summary>
    /// filebaseに定義された全スキルを読み取ります。
    /// </summary>
    /// <param name="token">処理のキャンセルトークン。</param>
    /// <returns>ID順のスキル概要。</returns>
    /// <exception cref="DirectoryNotFoundException">スキルマスターディレクトリが存在しない場合。</exception>
    /// <exception cref="InvalidOperationException">スキルIDが重複する場合。</exception>
    public async Task<IReadOnlyList<SkillMasterSummary>> ReadAllAsync(CancellationToken token)
    {
        if (!Directory.Exists(paths.Skills))
            throw new DirectoryNotFoundException($"Skill master directory was not found: {paths.Skills}");

        var summaries = new Dictionary<string, SkillMasterSummary>(StringComparer.OrdinalIgnoreCase);
        var files = Directory.EnumerateFiles(paths.Skills, "*.yml", SearchOption.AllDirectories)
            .Concat(Directory.EnumerateFiles(paths.Skills, "*.yaml", SearchOption.AllDirectories))
            .OrderBy(file => file, StringComparer.OrdinalIgnoreCase);
        foreach (var file in files)
        {
            token.ThrowIfCancellationRequested();
            var yaml = await File.ReadAllTextAsync(file, token);
            var master = _deserializer.Deserialize<SkillMasterYaml>(yaml);
            if (master is null || string.IsNullOrWhiteSpace(master.Id))
                continue;

            var id = master.Id.Trim().ToLowerInvariant();
            var description = string.IsNullOrWhiteSpace(master.Description)
                ? master.Lore.FirstOrDefault(line => !string.IsNullOrWhiteSpace(line)) ?? string.Empty
                : master.Description;
            var summary = new SkillMasterSummary(
                id,
                string.IsNullOrWhiteSpace(master.Name) ? id : master.Name,
                description,
                master.Type.Trim());
            if (!summaries.TryAdd(id, summary))
                throw new InvalidOperationException($"Duplicate skill master id '{id}'.");
        }

        return summaries.Values.OrderBy(summary => summary.Id, StringComparer.Ordinal).ToArray();
    }

    private sealed class SkillMasterYaml
    {
        public string Id { get; init; } = string.Empty;
        public string Name { get; init; } = string.Empty;
        public string Description { get; init; } = string.Empty;
        public string Type { get; init; } = string.Empty;
        public List<string> Lore { get; init; } = [];
    }
}
