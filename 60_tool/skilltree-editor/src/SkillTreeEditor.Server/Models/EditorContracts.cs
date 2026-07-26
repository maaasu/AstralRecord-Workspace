using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Models;

public sealed record StoredDocument(string FileName, string FullPath, JsonObject Content);

public sealed record SchemaSummary(
    string FileName,
    string? Id,
    string? Title,
    string EntityKind,
    int? Version,
    bool IsDefault = false);

public sealed record ValidationIssue(
    string Code,
    string Message,
    string Severity = "error",
    string? File = null,
    string? Path = null);

public sealed class ValidationReport
{
    public List<ValidationIssue> Issues { get; } = [];

    public bool IsValid => Issues.All(issue => !string.Equals(issue.Severity, "error", StringComparison.OrdinalIgnoreCase));

    public void AddError(string code, string message, string? file = null, string? path = null)
        => Issues.Add(new ValidationIssue(code, message, "error", file, path));

    public void AddWarning(string code, string message, string? file = null, string? path = null)
        => Issues.Add(new ValidationIssue(code, message, "warning", file, path));

    public void Merge(IEnumerable<ValidationIssue> issues) => Issues.AddRange(issues);
}

public sealed record PluginSkillTreeSettings(
    string WorldName,
    string StructureId,
    int CenterX,
    int CenterY,
    int CenterZ);

public sealed record ClassMasterSummary(
    string Id,
    string Name,
    IReadOnlyList<string> ParentClassIds);

public sealed record SkillMasterSummary(
    string Id,
    string Name,
    string Description,
    string Type);

public sealed record EditorMetadata(
    string WorkspaceRoot,
    string NodesPath,
    string StructuresPath,
    string SchemasPath,
    string NodeIdSequencePath,
    string SkillsPath,
    string StatusCatalogPath,
    string PluginConfigPath,
    string BackupPath,
    string MinecraftIconCachePath);
