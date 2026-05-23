using System.Globalization;
using System.Text;
using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;
using YamlDotNet.Core;
using YamlDotNet.RepresentationModel;

namespace AstralRecordApi.Tests.TestSupport;

/// <summary>
/// テスト用に MasterDataDB（SQLite in-memory）へ filebase YAML を投入するヘルパ。
/// 本番 Seeder（<c>AstralRecordApi.Services.MasterDataSeeder</c>）を簡略化したもの。
/// </summary>
internal static class MasterDataTestSeed
{
    /// <summary>
    /// MasterDataDbContext 用の最小スキーマ（<c>master_data_entry</c> のみ）を作成する。
    /// </summary>
    public static async Task CreateSchemaAsync(MasterDataDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE master_data_entry (
                entry_id TEXT NOT NULL PRIMARY KEY,
                source_id TEXT NOT NULL,
                master_type TEXT NOT NULL,
                master_id TEXT NOT NULL,
                category TEXT NULL,
                type TEXT NULL,
                schema_version INTEGER NOT NULL,
                display_name TEXT NULL,
                source_file_path TEXT NOT NULL,
                source_file_hash TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_version INTEGER NOT NULL,
                effective_from TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );");
    }

    /// <summary>
    /// 指定 YAML ファイルを読み込み、payload_json を <c>master_data_entry</c> に投入する。
    /// </summary>
    public static async Task SeedEntryAsync(
        MasterDataDbContext dbContext,
        string filePath,
        string masterType,
        string? category)
    {
        var rawText = await File.ReadAllTextAsync(filePath);
        var root = ParseYamlObject(rawText, filePath);

        var masterId = root["id"]?.GetValue<string>()
            ?? throw new InvalidOperationException($"id is required: {filePath}");
        var schemaVersion = (int)(root["schemaVersion"]?.GetValue<long>()
            ?? throw new InvalidOperationException($"schemaVersion is required: {filePath}"));

        dbContext.Entries.Add(new MasterDataEntryEntity
        {
            EntryId = Guid.NewGuid(),
            SourceId = Guid.NewGuid(),
            MasterType = masterType,
            MasterId = masterId,
            Category = category,
            Type = root["type"] is JsonValue typeValue ? typeValue.ToString() : null,
            SchemaVersion = schemaVersion,
            DisplayName = root["name"] is JsonValue nameValue ? nameValue.ToString() : null,
            SourceFilePath = filePath,
            SourceFileHash = new string('0', 64),
            PayloadJson = root.ToJsonString(),
            PayloadVersion = 1,
            EffectiveFrom = DateTime.UtcNow,
            CreatedAt = DateTime.UtcNow,
            UpdatedAt = DateTime.UtcNow,
            CreatedBy = Guid.Empty,
            UpdatedBy = Guid.Empty,
            IsDeleted = false,
        });
        await dbContext.SaveChangesAsync();
    }

    private static JsonObject ParseYamlObject(string rawText, string filePath)
    {
        var normalized = NormalizeAmpersandScalars(rawText.TrimStart('﻿'));
        var yamlStream = new YamlStream();
        yamlStream.Load(new StringReader(normalized));

        if (yamlStream.Documents.Count == 0)
            throw new InvalidOperationException($"YAML が空です: {filePath}");

        if (ConvertYamlNode(yamlStream.Documents[0].RootNode) is not JsonObject root)
            throw new InvalidOperationException($"YAML のルートがマッピングではありません: {filePath}");

        return root;
    }

    private static JsonNode? ConvertYamlNode(YamlNode node)
    {
        switch (node)
        {
            case YamlMappingNode mapping:
                var jsonObject = new JsonObject();
                foreach (var pair in mapping.Children)
                {
                    var keyName = ((YamlScalarNode)pair.Key).Value ?? string.Empty;
                    jsonObject[keyName] = ConvertYamlNode(pair.Value);
                }
                return jsonObject;

            case YamlSequenceNode sequence:
                var jsonArray = new JsonArray();
                foreach (var item in sequence.Children)
                    jsonArray.Add(ConvertYamlNode(item));
                return jsonArray;

            case YamlScalarNode scalar:
                return ConvertYamlScalar(scalar);

            default:
                return null;
        }
    }

    private static JsonNode? ConvertYamlScalar(YamlScalarNode scalar)
    {
        var value = scalar.Value ?? string.Empty;

        if (scalar.Style is ScalarStyle.SingleQuoted or ScalarStyle.DoubleQuoted)
            return JsonValue.Create(value);

        if (value.Length == 0 || value is "~" or "null" or "Null" or "NULL")
            return null;

        if (value is "true" or "True" or "TRUE")
            return JsonValue.Create(true);

        if (value is "false" or "False" or "FALSE")
            return JsonValue.Create(false);

        if (long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var longValue))
            return JsonValue.Create(longValue);

        if (double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var doubleValue))
            return JsonValue.Create(doubleValue);

        return JsonValue.Create(value);
    }

    private static string NormalizeAmpersandScalars(string rawText)
    {
        var builder = new StringBuilder();
        foreach (var line in rawText.Split('\n'))
            builder.Append(NormalizeAmpersandScalarLine(line.TrimEnd('\r'))).Append('\n');
        return builder.ToString();
    }

    private static string NormalizeAmpersandScalarLine(string line)
    {
        var commentIndex = line.IndexOf(" #", StringComparison.Ordinal);
        var content = commentIndex >= 0 ? line[..commentIndex] : line;
        var comment = commentIndex >= 0 ? line[commentIndex..] : string.Empty;

        var colonIndex = content.IndexOf(':');
        if (colonIndex >= 0)
        {
            var valuePart = content[(colonIndex + 1)..];
            var trimmedValue = valuePart.TrimStart();
            if (trimmedValue.StartsWith('&') && !trimmedValue.StartsWith('"') && !trimmedValue.StartsWith('\''))
            {
                var leadingWhitespace = valuePart[..(valuePart.Length - trimmedValue.Length)];
                var escaped = trimmedValue.Replace("\\", "\\\\").Replace("\"", "\\\"");
                return $"{content[..(colonIndex + 1)]}{leadingWhitespace}\"{escaped}\"{comment}";
            }
        }

        return line;
    }
}
