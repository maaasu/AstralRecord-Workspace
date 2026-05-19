using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Utilities;
using Microsoft.Extensions.Options;
using System.Text;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace AstralRecordApi.Repositories;

public class ClassRepository : IClassRepository
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    private readonly IReadOnlyDictionary<string, ClassResponse> _classes;
    private readonly IReadOnlyList<ClassSummaryResponse> _classSummaries;

    public ClassRepository(IOptions<FileDatabaseOptions> options, ILogger<ClassRepository> logger)
    {
        var rootPath = options.Value.RootPath;
        if (string.IsNullOrWhiteSpace(rootPath))
            throw new InvalidOperationException("FileDatabase:RootPath is not configured.");

        logger.LogInformation("クラスデータの読み込みを開始します (RootPath: {RootPath})", rootPath);
        _classes = LoadClasses(rootPath, logger);
        _classSummaries = _classes.Values
            .OrderBy(c => c.Id, KeyComparer)
            .Select(c => new ClassSummaryResponse
            {
                Id = c.Id,
                Name = c.Name,
                Role = c.Role
            })
            .ToArray();
        logger.LogInformation("クラスデータの読み込みが完了しました (件数: {Count})", _classSummaries.Count);
    }

    public IReadOnlyList<ClassSummaryResponse> GetAllSummaries() => _classSummaries;

    public ClassResponse? GetById(string classId)
        => _classes.TryGetValue(classId, out var cls) ? cls : null;

    private static IReadOnlyDictionary<string, ClassResponse> LoadClasses(string rootPath, ILogger logger)
    {
        var resolver = FileDatabaseConfigResolver.Load(rootPath);
        if (!resolver.TryGetDatabaseDirectory("class", out var classRootPath))
            return new Dictionary<string, ClassResponse>(KeyComparer);
        if (!Directory.Exists(classRootPath))
            throw new DirectoryNotFoundException($"Class directory was not found: {classRootPath}");

        var deserializer = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build();

        var classes = new Dictionary<string, ClassResponse>(KeyComparer);

        foreach (var filePath in Directory.EnumerateFiles(classRootPath, "*.yml", SearchOption.TopDirectoryOnly))
        {
            try
            {
                var yaml = ReadYamlWithNormalizedAmpersandScalars(filePath, resolver, logger);
                var yamlClass = deserializer.Deserialize<ClassYamlDocument>(yaml)
                    ?? throw new InvalidOperationException($"Failed to deserialize class YAML (null result): {filePath}");
                var cls = yamlClass.ToResponse(filePath);
                if (!classes.TryAdd(cls.Id, cls))
                {
                    logger.LogWarning("重複するクラス ID '{ClassId}' をスキップします: {FilePath}", cls.Id, filePath);
                    continue;
                }
            }
            catch (InvalidOperationException ex)
            {
                logger.LogWarning("必須項目が不足しているためスキップします: {Message}", ex.Message);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "クラスファイルの読み込みに失敗しました。スキップします: {FilePath}", filePath);
            }
        }

        return classes;
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

    private sealed class ClassYamlDocument
    {
        public int? SchemaVersion { get; init; }

        public string? Id { get; init; }

        public string? Type { get; init; }

        public string? Name { get; init; }

        public string? Description { get; init; }

        public string? Icon { get; init; }

        public string? Role { get; init; }

        public int? UnlockLevel { get; init; }

        public List<UnlockClassLevelYamlDocument>? UnlockClassLevel { get; init; }

        public List<StatYamlDocument>? BaseStats { get; init; }

        public List<StatYamlDocument>? GrowthPerLevel { get; init; }

        public int? ExpRate { get; init; }

        public List<string>? StarterSkills { get; init; }

        public List<LevelSkillYamlDocument>? LevelSkills { get; init; }

        public List<string>? Tags { get; init; }

        public ClassResponse ToResponse(string filePath)
        {
            if (SchemaVersion is null)
                throw new InvalidOperationException($"schemaVersion is required: {filePath}");

            return SchemaVersion.Value switch
            {
                1 => ToResponseV1(filePath),
                _ => throw new InvalidOperationException($"Unsupported schemaVersion '{SchemaVersion.Value}': {filePath}")
            };
        }

        private ClassResponse ToResponseV1(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Id))
                throw new InvalidOperationException($"id is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Type))
                throw new InvalidOperationException($"type is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Name))
                throw new InvalidOperationException($"name is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Role))
                throw new InvalidOperationException($"role is required: {filePath}");
            if (BaseStats is null || BaseStats.Count == 0)
                throw new InvalidOperationException($"baseStats is required: {filePath}");

            return new ClassResponse
            {
                SchemaVersion = SchemaVersion!.Value,
                Id = Id,
                Type = Type,
                Name = Name,
                Description = Description,
                Icon = Icon,
                Role = Role,
                UnlockLevel = UnlockLevel ?? 1,
                UnlockClassLevel = UnlockClassLevel?.Select(u => u.ToResponse(filePath)).ToArray() ?? [],
                BaseStats = BaseStats.Select(s => s.ToResponse(filePath, "baseStats")).ToArray(),
                GrowthPerLevel = GrowthPerLevel?.Select(s => s.ToResponse(filePath, "growthPerLevel")).ToArray() ?? [],
                ExpRate = ExpRate ?? 100,
                StarterSkills = StarterSkills ?? [],
                LevelSkills = LevelSkills?.Select(l => l.ToResponse(filePath)).ToArray() ?? [],
                Tags = Tags ?? []
            };
        }
    }

    private sealed class UnlockClassLevelYamlDocument
    {
        [YamlDotNet.Serialization.YamlMember(Alias = "class")]
        public string? ClassName { get; init; }

        public int? Level { get; init; }

        public ClassUnlockClassLevelResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(ClassName))
                throw new InvalidOperationException($"unlockClassLevel[].class is required: {filePath}");
            if (Level is null)
                throw new InvalidOperationException($"unlockClassLevel[].level is required: {filePath}");

            return new ClassUnlockClassLevelResponse
            {
                ClassId = ClassName,
                Level = Level.Value
            };
        }
    }

    private sealed class StatYamlDocument
    {
        public string? Status { get; init; }

        public double? Value { get; init; }

        public ClassStatResponse ToResponse(string filePath, string fieldName)
        {
            if (string.IsNullOrWhiteSpace(Status))
                throw new InvalidOperationException($"{fieldName}[].status is required: {filePath}");
            if (Value is null)
                throw new InvalidOperationException($"{fieldName}[].value is required: {filePath}");

            return new ClassStatResponse
            {
                Status = Status,
                Value = Value.Value
            };
        }
    }

    private sealed class LevelSkillYamlDocument
    {
        public int? Level { get; init; }

        public string? Skill { get; init; }

        public ClassLevelSkillResponse ToResponse(string filePath)
        {
            if (Level is null)
                throw new InvalidOperationException($"levelSkills[].level is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Skill))
                throw new InvalidOperationException($"levelSkills[].skill is required: {filePath}");

            return new ClassLevelSkillResponse
            {
                Level = Level.Value,
                Skill = Skill
            };
        }
    }
}
