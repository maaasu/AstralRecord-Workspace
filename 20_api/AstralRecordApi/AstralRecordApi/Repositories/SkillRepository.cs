using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Utilities;
using Microsoft.Extensions.Options;
using System.Text;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace AstralRecordApi.Repositories;

public class SkillRepository : ISkillRepository
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    private readonly IReadOnlyDictionary<string, SkillResponse> _skills;
    private readonly IReadOnlyList<SkillSummaryResponse> _skillSummaries;

    public SkillRepository(IOptions<FileDatabaseOptions> options, ILogger<SkillRepository> logger)
    {
        var rootPath = options.Value.RootPath;
        if (string.IsNullOrWhiteSpace(rootPath))
            throw new InvalidOperationException("FileDatabase:RootPath is not configured.");

        logger.LogInformation("スキルデータの読み込みを開始します (RootPath: {RootPath})", rootPath);
        _skills = LoadSkills(rootPath, logger);
        _skillSummaries = _skills.Values
            .OrderBy(s => s.Id, KeyComparer)
            .Select(s => new SkillSummaryResponse
            {
                Id = s.Id,
                Name = s.Name,
                ImplementationId = s.ImplementationId
            })
            .ToArray();
        logger.LogInformation("スキルデータの読み込みが完了しました (件数: {Count})", _skillSummaries.Count);
    }

    public IReadOnlyList<SkillSummaryResponse> GetAllSummaries() => _skillSummaries;

    public SkillResponse? GetById(string skillId)
        => _skills.TryGetValue(skillId, out var skill) ? skill : null;

    private static IReadOnlyDictionary<string, SkillResponse> LoadSkills(string rootPath, ILogger logger)
    {
        var resolver = FileDatabaseConfigResolver.Load(rootPath);
        if (!resolver.TryGetDatabaseDirectory("skill", out var skillRootPath))
            return new Dictionary<string, SkillResponse>(KeyComparer);
        if (!Directory.Exists(skillRootPath))
            throw new DirectoryNotFoundException($"Skill directory was not found: {skillRootPath}");

        var deserializer = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build();

        var skills = new Dictionary<string, SkillResponse>(KeyComparer);

        foreach (var filePath in Directory.EnumerateFiles(skillRootPath, "*.yml", SearchOption.TopDirectoryOnly))
        {
            try
            {
                var yaml = ReadYamlWithNormalizedAmpersandScalars(filePath, resolver, logger);
                var yamlSkill = deserializer.Deserialize<SkillYamlDocument>(yaml)
                    ?? throw new InvalidOperationException($"Failed to deserialize skill YAML (null result): {filePath}");
                var skill = yamlSkill.ToResponse(filePath);
                if (!skills.TryAdd(skill.Id, skill))
                {
                    logger.LogWarning("重複するスキル ID '{SkillId}' をスキップします: {FilePath}", skill.Id, filePath);
                    continue;
                }
            }
            catch (InvalidOperationException ex)
            {
                logger.LogWarning("必須項目が不足しているためスキップします: {Message}", ex.Message);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "スキルファイルの読み込みに失敗しました。スキップします: {FilePath}", filePath);
            }
        }

        return skills;
    }

    private static string ReadYamlWithNormalizedAmpersandScalars(string filePath, FileDatabaseConfigResolver resolver, ILogger logger)
    {
        var rawLines = File.ReadAllLines(filePath);
        var lines = NormalizeRefObjects(rawLines, resolver, filePath, logger);
        var builder = new StringBuilder();

        foreach (var line in lines)
        {
            builder.AppendLine(NormalizeAmpersandScalar(line));
        }

        return builder.ToString();
    }

    private static string[] NormalizeRefObjects(string[] lines, FileDatabaseConfigResolver resolver, string filePath, ILogger logger)
    {
        var result = new List<string>(lines.Length);
        for (int i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            var trimmed = line.TrimStart();

            if (trimmed.EndsWith(':') && !trimmed.StartsWith('-') && !trimmed.StartsWith('#'))
            {
                if (i + 1 < lines.Length)
                {
                    var nextTrimmed = lines[i + 1].TrimStart();
                    if (nextTrimmed.StartsWith("ref: ", StringComparison.OrdinalIgnoreCase))
                    {
                        var refValue = nextTrimmed["ref: ".Length..].Trim();
                        if (!resolver.TryResolveReferenceId(refValue, out var id))
                        {
                            logger.LogWarning("ref の解決に失敗しました: ref={RefValue} (ファイル: {FilePath})", refValue, filePath);
                            result.Add(line);
                            continue;
                        }

                        var indent = line[..(line.Length - trimmed.Length)];
                        var key = trimmed.TrimEnd(':');
                        result.Add($"{indent}{key}: {id}");
                        i++;
                        continue;
                    }
                }
            }

            result.Add(line);
        }
        return result.ToArray();
    }

    private static string NormalizeAmpersandScalar(string line)
    {
        var commentIndex = line.IndexOf(" #", StringComparison.Ordinal);
        var content = commentIndex >= 0 ? line[..commentIndex] : line;
        var comment = commentIndex >= 0 ? line[commentIndex..] : string.Empty;

        var colonIndex = content.IndexOf(':');
        if (colonIndex >= 0)
        {
            var valuePart = content[(colonIndex + 1)..];
            var trimmedValue = valuePart.TrimStart();
            if (trimmedValue.StartsWith('&') && !trimmedValue.StartsWith("\"") && !trimmedValue.StartsWith("'"))
            {
                var leadingWhitespaceLength = valuePart.Length - trimmedValue.Length;
                var leadingWhitespace = valuePart[..leadingWhitespaceLength];
                var escaped = trimmedValue.Replace("\\", "\\\\").Replace("\"", "\\\"");
                return $"{content[..(colonIndex + 1)]}{leadingWhitespace}\"{escaped}\"{comment}";
            }
        }

        var trimmedContent = content.TrimStart();
        if (trimmedContent.StartsWith("- "))
        {
            var itemValue = trimmedContent[2..].TrimStart();
            if (itemValue.StartsWith('&') && !itemValue.StartsWith("\"") && !itemValue.StartsWith("'"))
            {
                var indentLength = content.Length - trimmedContent.Length;
                var indent = content[..indentLength];
                var afterDash = trimmedContent[2..];
                var leadingWhitespaceLength = afterDash.Length - itemValue.Length;
                var leadingWhitespace = afterDash[..leadingWhitespaceLength];
                var escaped = itemValue.Replace("\\", "\\\\").Replace("\"", "\\\"");
                return $"{indent}- {leadingWhitespace}\"{escaped}\"{comment}";
            }
        }

        return line;
    }

    private sealed class SkillYamlDocument
    {
        public int? SchemaVersion { get; init; }

        public string? Id { get; init; }

        public string? Type { get; init; }

        public string? ImplementationId { get; init; }

        public string? Name { get; init; }

        public string? Description { get; init; }

        public string? Icon { get; init; }

        public List<string>? Lore { get; init; }

        public long? CooldownTicks { get; init; }

        public double? ManaCost { get; init; }

        public long? CastTimeTicks { get; init; }

        public int? RequiredLevel { get; init; }

        public SkillOnCastYamlDocument? OnCast { get; init; }

        public Dictionary<string, object?>? Params { get; init; }

        public List<string>? Tags { get; init; }

        public SkillResponse ToResponse(string filePath)
        {
            if (SchemaVersion is null)
                throw new InvalidOperationException($"schemaVersion is required: {filePath}");

            return SchemaVersion.Value switch
            {
                1 => ToResponseV1(filePath),
                _ => throw new InvalidOperationException($"Unsupported schemaVersion '{SchemaVersion.Value}': {filePath}")
            };
        }

        private SkillResponse ToResponseV1(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Id))
                throw new InvalidOperationException($"id is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Type))
                throw new InvalidOperationException($"type is required: {filePath}");
            if (string.IsNullOrWhiteSpace(ImplementationId))
                throw new InvalidOperationException($"implementationId is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Name))
                throw new InvalidOperationException($"name is required: {filePath}");

            return new SkillResponse
            {
                SchemaVersion = SchemaVersion!.Value,
                Id = Id,
                Type = Type,
                ImplementationId = ImplementationId,
                Name = Name,
                Description = Description,
                Icon = Icon,
                Lore = Lore ?? [],
                CooldownTicks = CooldownTicks ?? 0,
                ManaCost = ManaCost ?? 0,
                CastTimeTicks = CastTimeTicks ?? 0,
                RequiredLevel = RequiredLevel ?? 1,
                OnCast = OnCast?.ToResponse(),
                Params = Params ?? new Dictionary<string, object?>(),
                Tags = Tags ?? []
            };
        }
    }

    private sealed class SkillOnCastYamlDocument
    {
        public string? Sound { get; init; }

        public SkillOnCastResponse ToResponse()
        {
            return new SkillOnCastResponse
            {
                Sound = Sound
            };
        }
    }
}
