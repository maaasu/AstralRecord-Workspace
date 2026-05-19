using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Utilities;
using Microsoft.Extensions.Options;
using System.Text;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace AstralRecordApi.Repositories;

public class SetEffectRepository : ISetEffectRepository
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    private readonly IReadOnlyDictionary<string, SetEffectResponse> _setEffects;
    private readonly IReadOnlyList<SetEffectResponse> _allSetEffects;

    public SetEffectRepository(IOptions<FileDatabaseOptions> options, ILogger<SetEffectRepository> logger)
    {
        var rootPath = options.Value.RootPath;
        if (string.IsNullOrWhiteSpace(rootPath))
            throw new InvalidOperationException("FileDatabase:RootPath is not configured.");

        logger.LogInformation("セット効果データの読み込みを開始します (RootPath: {RootPath})", rootPath);
        _setEffects = LoadSetEffects(rootPath, logger);
        _allSetEffects = _setEffects.Values.OrderBy(s => s.Id, KeyComparer).ToArray();
        logger.LogInformation("セット効果データの読み込みが完了しました (件数: {Count})", _allSetEffects.Count);
    }

    public IReadOnlyList<SetEffectResponse> GetAll() => _allSetEffects;

    public SetEffectResponse? GetById(string setId)
        => _setEffects.TryGetValue(setId, out var setEffect) ? setEffect : null;

    private static IReadOnlyDictionary<string, SetEffectResponse> LoadSetEffects(string rootPath, ILogger logger)
    {
        var resolver = FileDatabaseConfigResolver.Load(rootPath);
        if (!resolver.TryGetDatabaseDirectory("set_effect", out var setEffectRootPath))
            return new Dictionary<string, SetEffectResponse>(KeyComparer);
        if (!Directory.Exists(setEffectRootPath))
            throw new DirectoryNotFoundException($"SetEffect directory was not found: {setEffectRootPath}");

        var deserializer = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build();

        var setEffects = new Dictionary<string, SetEffectResponse>(KeyComparer);

        foreach (var filePath in Directory.EnumerateFiles(setEffectRootPath, "*.yml", SearchOption.TopDirectoryOnly))
        {
            try
            {
                var yaml = ReadYaml(filePath);
                var yamlSetEffect = deserializer.Deserialize<SetEffectYamlDocument>(yaml)
                    ?? throw new InvalidOperationException($"Failed to deserialize set effect YAML (null result): {filePath}");
                var setEffect = yamlSetEffect.ToResponse(filePath);
                if (!setEffects.TryAdd(setEffect.Id, setEffect))
                {
                    logger.LogWarning("重複するセット効果 ID '{SetId}' をスキップします: {FilePath}", setEffect.Id, filePath);
                    continue;
                }
            }
            catch (InvalidOperationException ex)
            {
                logger.LogWarning("必須項目が不足しているためスキップします: {Message}", ex.Message);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "セット効果ファイルの読み込みに失敗しました。スキップします: {FilePath}", filePath);
            }
        }

        return setEffects;
    }

    private static string ReadYaml(string filePath)
    {
        var lines = File.ReadAllLines(filePath);
        var builder = new StringBuilder();
        foreach (var line in lines)
            builder.AppendLine(NormalizeAmpersandScalar(line));
        return builder.ToString();
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

        return line;
    }

    private sealed class SetEffectYamlDocument
    {
        public string? Id { get; init; }

        public string? Name { get; init; }

        public List<SetEffectPieceYamlDocument>? Pieces { get; init; }

        public SetEffectResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Id))
                throw new InvalidOperationException($"set_effect.id is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Name))
                throw new InvalidOperationException($"set_effect.name is required: {filePath}");
            if (Pieces is null || Pieces.Count == 0)
                throw new InvalidOperationException($"set_effect.pieces is required: {filePath}");

            return new SetEffectResponse
            {
                Id = Id,
                Name = Name,
                Pieces = Pieces.Select(p => p.ToResponse(filePath)).ToArray()
            };
        }
    }

    private sealed class SetEffectPieceYamlDocument
    {
        public int? Count { get; init; }

        public List<SetEffectStatYamlDocument>? Stats { get; init; }

        public List<string>? Skills { get; init; }

        public SetEffectPieceResponse ToResponse(string filePath)
        {
            if (Count is null)
                throw new InvalidOperationException($"set_effect.pieces[].count is required: {filePath}");

            return new SetEffectPieceResponse
            {
                Count = Count.Value,
                Stats = Stats?.Select(s => s.ToResponse(filePath)).ToArray() ?? [],
                Skills = Skills ?? []
            };
        }
    }

    private sealed class SetEffectStatYamlDocument
    {
        public string? Status { get; init; }

        public string? Type { get; init; }

        public string? Value { get; init; }

        public SetEffectStatResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Status))
                throw new InvalidOperationException($"set_effect.pieces[].stats[].status is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Type))
                throw new InvalidOperationException($"set_effect.pieces[].stats[].type is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Value))
                throw new InvalidOperationException($"set_effect.pieces[].stats[].value is required: {filePath}");

            return new SetEffectStatResponse
            {
                Status = Status,
                Type = Type,
                Value = Value
            };
        }
    }
}
