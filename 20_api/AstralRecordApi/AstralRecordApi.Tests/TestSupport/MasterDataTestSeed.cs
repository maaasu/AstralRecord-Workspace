using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Tests.TestSupport;

/// <summary>
/// テスト用に MasterDataDB（SQLite in-memory）へ固定 payload を投入するヘルパ。
/// 本番 Filebase へ接続せず、各テストが必要とする最小データだけを登録する。
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
    /// JSON payload を <c>master_data_entry</c> に投入する。
    /// </summary>
    public static async Task SeedInlinePayloadAsync(
        MasterDataDbContext dbContext,
        string payloadJson,
        string masterType,
        string? category)
    {
        var root = JsonNode.Parse(payloadJson)?.AsObject()
            ?? throw new InvalidOperationException("inline master payload must be a JSON object.");

        var masterId = root["id"]?.GetValue<string>()
            ?? throw new InvalidOperationException("id is required in inline master payload.");
        var schemaVersion = (int)(root["schemaVersion"]?.GetValue<long>()
            ?? throw new InvalidOperationException("schemaVersion is required in inline master payload."));
        var sourceFilePath = $"test-inline/{masterType}/{masterId}.json";

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
            SourceFilePath = sourceFilePath,
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
}
