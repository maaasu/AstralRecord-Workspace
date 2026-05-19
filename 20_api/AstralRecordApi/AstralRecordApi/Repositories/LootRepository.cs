using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Utilities;
using Microsoft.Extensions.Options;
using System.Text;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace AstralRecordApi.Repositories;

public class LootRepository : ILootRepository
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    private readonly IReadOnlyDictionary<string, LootPoolResponse> _pools;
    private readonly IReadOnlyList<LootPoolResponse> _poolList;

    private readonly IReadOnlyDictionary<string, LootTableResponse> _tables;
    private readonly IReadOnlyList<LootTableResponse> _tableList;

    public LootRepository(IOptions<FileDatabaseOptions> options, ILogger<LootRepository> logger)
    {
        var rootPath = options.Value.RootPath;
        if (string.IsNullOrWhiteSpace(rootPath))
            throw new InvalidOperationException("FileDatabase:RootPath is not configured.");

        logger.LogInformation("ルートデータの読み込みを開始します (RootPath: {RootPath})", rootPath);
        _pools = LoadPools(rootPath, logger);
        _poolList = _pools.Values.OrderBy(p => p.Id, KeyComparer).ToArray();

        _tables = LoadTables(rootPath, logger);
        _tableList = _tables.Values.OrderBy(t => t.Id, KeyComparer).ToArray();
        logger.LogInformation("ルートデータの読み込みが完了しました (プール: {PoolCount}, テーブル: {TableCount})", _poolList.Count, _tableList.Count);
    }

    public IReadOnlyList<LootPoolResponse> GetAllPools() => _poolList;

    public LootPoolResponse? GetPoolById(string poolId)
        => _pools.TryGetValue(poolId, out var pool) ? pool : null;

    public IReadOnlyList<LootTableResponse> GetAllTables() => _tableList;

    public LootTableResponse? GetTableById(string tableId)
        => _tables.TryGetValue(tableId, out var table) ? table : null;

    private static IReadOnlyDictionary<string, LootPoolResponse> LoadPools(string rootPath, ILogger logger)
    {
        var resolver = FileDatabaseConfigResolver.Load(rootPath);
        if (!resolver.TryGetDatabaseDirectory("loot", out var lootRootPath))
            return new Dictionary<string, LootPoolResponse>(KeyComparer);

        var poolPath = Path.Combine(lootRootPath, "pool");
        if (!Directory.Exists(poolPath))
            throw new DirectoryNotFoundException($"Loot pool directory was not found: {poolPath}");

        var deserializer = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build();

        var pools = new Dictionary<string, LootPoolResponse>(KeyComparer);

        foreach (var filePath in Directory.EnumerateFiles(poolPath, "*.yml", SearchOption.TopDirectoryOnly))
        {
            try
            {
                var yaml = ReadPoolYaml(filePath, resolver, logger);
                var yamlPool = deserializer.Deserialize<LootPoolYamlDocument>(yaml)
                    ?? throw new InvalidOperationException($"Failed to deserialize loot pool YAML (null result): {filePath}");
                var pool = yamlPool.ToResponse(filePath);
                if (!pools.TryAdd(pool.Id, pool))
                {
                    logger.LogWarning("重複するルートプール ID '{PoolId}' をスキップします: {FilePath}", pool.Id, filePath);
                    continue;
                }
            }
            catch (InvalidOperationException ex)
            {
                logger.LogWarning("必須項目が不足しているためスキップします: {Message}", ex.Message);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "ルートプールファイルの読み込みに失敗しました。スキップします: {FilePath}", filePath);
            }
        }

        return pools;
    }

    private static IReadOnlyDictionary<string, LootTableResponse> LoadTables(string rootPath, ILogger logger)
    {
        var resolver = FileDatabaseConfigResolver.Load(rootPath);
        if (!resolver.TryGetDatabaseDirectory("loot", out var lootRootPath))
            return new Dictionary<string, LootTableResponse>(KeyComparer);

        var tablePath = Path.Combine(lootRootPath, "table");
        if (!Directory.Exists(tablePath))
            throw new DirectoryNotFoundException($"Loot table directory was not found: {tablePath}");

        var deserializer = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build();

        var tables = new Dictionary<string, LootTableResponse>(KeyComparer);

        foreach (var filePath in Directory.EnumerateFiles(tablePath, "*.yml", SearchOption.TopDirectoryOnly))
        {
            try
            {
                var yaml = ReadTableYaml(filePath, resolver, logger);
                var yamlTable = deserializer.Deserialize<LootTableYamlDocument>(yaml)
                    ?? throw new InvalidOperationException($"Failed to deserialize loot table YAML (null result): {filePath}");
                var table = yamlTable.ToResponse(filePath);
                if (!tables.TryAdd(table.Id, table))
                {
                    logger.LogWarning("重複するルートテーブル ID '{TableId}' をスキップします: {FilePath}", table.Id, filePath);
                    continue;
                }
            }
            catch (InvalidOperationException ex)
            {
                logger.LogWarning("必須項目が不足しているためスキップします: {Message}", ex.Message);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "ルートテーブルファイルの読み込みに失敗しました。スキップします: {FilePath}", filePath);
            }
        }

        return tables;
    }

    private static string ReadPoolYaml(string filePath, FileDatabaseConfigResolver resolver, ILogger logger)
    {
        var rawLines = File.ReadAllLines(filePath);
        var lines = NormalizeRefObjects(rawLines, resolver, filePath, logger);
        lines = YamlRangeNormalizer.NormalizeMinMaxObjects(lines);
        var builder = new StringBuilder();
        foreach (var line in lines)
            builder.AppendLine(NormalizeAmpersandScalar(line));
        return builder.ToString();
    }

    private static string ReadTableYaml(string filePath, FileDatabaseConfigResolver resolver, ILogger logger)
    {
        var rawLines = File.ReadAllLines(filePath);
        var lines = NormalizeListRefs(rawLines, resolver, filePath, logger);
        lines = YamlRangeNormalizer.NormalizeMinMaxObjects(lines);
        var builder = new StringBuilder();
        foreach (var line in lines)
            builder.AppendLine(NormalizeAmpersandScalar(line));
        return builder.ToString();
    }

    /// <summary>
    /// YAML の参照オブジェクト形式（ref: type:id）をフラットなスカラーに正規化する。
    /// 例:
    ///   itemId:          itemId: iron_ingot
    ///     ref: item:iron_ingot
    ///
    /// リストアイテム内の参照も正規化する。
    /// 例:
    ///   - itemId:        - itemId: iron_ingot
    ///       ref: item:iron_ingot
    /// </summary>
    private static string[] NormalizeRefObjects(string[] lines, FileDatabaseConfigResolver resolver, string filePath, ILogger logger)
    {
        var result = new List<string>(lines.Length);
        for (int i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            var trimmed = line.TrimStart();

            // "key:" 形式（リストアイテム外）
            if (trimmed.EndsWith(':') && !trimmed.StartsWith('-') && !trimmed.StartsWith('#'))
            {
                if (TryNormalizeRef(lines, ref i, line, trimmed, resolver, result, filePath, logger))
                    continue;
            }

            // "- key:" 形式（リストアイテム内）
            if (trimmed.StartsWith('-') && trimmed.EndsWith(':') && !trimmed.StartsWith('#'))
            {
                if (TryNormalizeRef(lines, ref i, line, trimmed, resolver, result, filePath, logger))
                    continue;
            }

            result.Add(line);
        }
        return result.ToArray();
    }

    private static bool TryNormalizeRef(string[] lines, ref int i, string line, string trimmed,
        FileDatabaseConfigResolver resolver, List<string> result, string filePath, ILogger logger)
    {
        if (i + 1 >= lines.Length)
            return false;

        var nextTrimmed = lines[i + 1].TrimStart();
        if (!nextTrimmed.StartsWith("ref: ", StringComparison.OrdinalIgnoreCase))
            return false;

        var refValue = nextTrimmed["ref: ".Length..].Trim();
        if (!resolver.TryResolveReferenceId(refValue, out var id))
        {
            logger.LogWarning("ref の解決に失敗しました: ref={RefValue} (ファイル: {FilePath})", refValue, filePath);
            return false;
        }

        var indent = line[..(line.Length - trimmed.Length)];
        var key = trimmed.TrimEnd(':');
        result.Add($"{indent}{key}: {id}");
        i++;
        return true;
    }

    /// <summary>
    /// YAML リストの参照形式（- ref: id）をスカラーに正規化する。
    /// 例:
    ///   - ref: pool_id   →   - pool_id
    ///   - ref: loot_table:pool_id   →   - pool_id
    /// </summary>
    private static string[] NormalizeListRefs(string[] lines, FileDatabaseConfigResolver resolver, string filePath, ILogger logger)
    {
        var result = new List<string>(lines.Length);
        foreach (var line in lines)
        {
            var trimmed = line.TrimStart();
            if (trimmed.StartsWith("- ref: ", StringComparison.OrdinalIgnoreCase))
            {
                var refValue = trimmed["- ref: ".Length..].Trim();
                if (!resolver.TryResolveReferenceId(refValue, out var id))
                {
                    logger.LogWarning("ref の解決に失敗しました: ref={RefValue} (ファイル: {FilePath})", refValue, filePath);
                    result.Add(line);
                    continue;
                }
                var indent = line[..(line.Length - trimmed.Length)];
                result.Add($"{indent}- {id}");
            }
            else
            {
                result.Add(line);
            }
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

    private sealed class LootPoolYamlDocument
    {
        public int? SchemaVersion { get; init; }

        public string? Id { get; init; }

        public string? Type { get; init; }

        public string? Pick { get; init; }

        public List<LootPoolContentYamlDocument>? Contents { get; init; }

        public LootPoolResponse ToResponse(string filePath)
        {
            if (SchemaVersion is null)
                throw new InvalidOperationException($"schemaVersion is required: {filePath}");

            return SchemaVersion.Value switch
            {
                1 => ToResponseV1(filePath),
                _ => throw new InvalidOperationException($"Unsupported schemaVersion '{SchemaVersion.Value}': {filePath}")
            };
        }

        private LootPoolResponse ToResponseV1(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Id))
                throw new InvalidOperationException($"id is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Type))
                throw new InvalidOperationException($"type is required: {filePath}");
            if (Contents is null || Contents.Count == 0)
                throw new InvalidOperationException($"contents is required: {filePath}");

            return new LootPoolResponse
            {
                SchemaVersion = SchemaVersion!.Value,
                Id = Id,
                Type = Type,
                Pick = Pick,
                Contents = Contents.Select(c => c.ToResponse(filePath)).ToArray()
            };
        }
    }

    private sealed class LootPoolContentYamlDocument
    {
        public string? ItemId { get; init; }

        public double? Rate { get; init; }

        public string? Amount { get; init; }

        public LootPoolContentResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(ItemId))
                throw new InvalidOperationException($"contents[].itemId is required: {filePath}");
            if (Rate is null)
                throw new InvalidOperationException($"contents[].rate is required: {filePath}");

            return new LootPoolContentResponse
            {
                ItemId = ItemId,
                Rate = Rate.Value,
                Amount = Amount
            };
        }
    }

    private sealed class LootTableYamlDocument
    {
        public int? SchemaVersion { get; init; }

        public string? Id { get; init; }

        public string? Type { get; init; }

        public string? Rolls { get; init; }

        public List<string>? Pools { get; init; }

        public LootTableResponse ToResponse(string filePath)
        {
            if (SchemaVersion is null)
                throw new InvalidOperationException($"schemaVersion is required: {filePath}");

            return SchemaVersion.Value switch
            {
                1 => ToResponseV1(filePath),
                _ => throw new InvalidOperationException($"Unsupported schemaVersion '{SchemaVersion.Value}': {filePath}")
            };
        }

        private LootTableResponse ToResponseV1(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Id))
                throw new InvalidOperationException($"id is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Type))
                throw new InvalidOperationException($"type is required: {filePath}");
            if (Pools is null || Pools.Count == 0)
                throw new InvalidOperationException($"pools is required: {filePath}");

            return new LootTableResponse
            {
                SchemaVersion = SchemaVersion!.Value,
                Id = Id,
                Type = Type,
                Rolls = Rolls,
                Pools = Pools.AsReadOnly()
            };
        }
    }
}
