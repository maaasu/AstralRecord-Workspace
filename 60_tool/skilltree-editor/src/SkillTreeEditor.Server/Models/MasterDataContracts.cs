using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Models;

public sealed record MasterDataIssue(string Severity, string Code, string Path, string Message);
public sealed record MasterDataReport(bool IsValid, IReadOnlyList<MasterDataIssue> Issues);
public sealed record MasterDataDocument(string Path, string Format, string Raw, string Revision,
    JsonNode? Content, IReadOnlyList<MasterDataIssue> Issues, bool ReadOnly = false);
public sealed record MasterDataFile(string Path, string Category, string Format, string? Id, string? Name,
    long Size, DateTimeOffset ModifiedUtc, string Revision, string? ParseError, bool ReadOnly);
public sealed record MasterDataField(string Path, string Key, string Label, string Type, bool Required,
    string Description, string? Default, string Source, JsonArray? Enum = null, string? Reference = null);
public sealed record MasterDataSource(string Path, string Title);
public sealed record MasterDataSchema(string Path, string Title, JsonNode Schema);
public sealed record MasterDataTemplate(string Path, string Raw);
public sealed record MasterDataCategory(string Id, string Label, string Directory, int FileCount,
    IReadOnlyList<MasterDataSource> Documents, IReadOnlyList<MasterDataField> Fields,
    IReadOnlyList<MasterDataSchema> JsonSchemas, IReadOnlyList<MasterDataTemplate> Templates,
    string? ItemCategoryCode);
public sealed record MasterDataCatalogResult(string Root, string Backups, IReadOnlyList<MasterDataCategory> Categories);
public sealed record MasterDataParseRequest(string Path, string Raw);
public sealed record MasterDataParseResult(JsonNode? Content, IReadOnlyList<MasterDataIssue> Issues);
public sealed record MasterDataRenderRequest(string Path, JsonNode? Content, string? OriginalRaw);
public sealed record MasterDataRenderResult(string Raw, bool CommentsPreserved, IReadOnlyList<string> Warnings);
public sealed record MasterDataSaveRequest(string Path, string Raw, string Revision);
public sealed record MasterDataCreateRequest(string Path, string Raw, bool AutoItemId = false,
    string? Slug = null, string? Group = null, bool AutoName = false);
public sealed record MasterDataCopyRequest(string SourcePath, string Revision, string TargetPath,
    bool AutoItemId = false, string? Slug = null, string? Group = null, bool AutoName = false, string? NewId = null);
public sealed record MasterDataReference(string Path, string Pointer, string Value, string? Label, string? Kind);

public sealed class MasterDataValidationException(MasterDataReport report) : Exception("マスタの検証に失敗しました。")
{
    public MasterDataReport Report { get; } = report;
}
