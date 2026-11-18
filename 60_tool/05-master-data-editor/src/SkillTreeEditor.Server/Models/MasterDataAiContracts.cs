namespace SkillTreeEditor.Server.Models;

public sealed record MasterDataAiSettings(bool Configured, string Model, string KeySource, string? Reason);
public sealed record MasterDataAiSettingsRequest(string? ApiKey, string Model, bool ClearApiKey = false);
public sealed record MasterDataAiRequest(string Mode, string Instruction, string? CategoryId = null,
    string? SourcePath = null, string? Raw = null, string? FieldPointer = null, IReadOnlyList<string>? Paths = null,
    string? CompletionStyle = null);
public sealed record MasterDataAiProposal(string Path, string? SourcePath, string Raw, string? BeforeRaw,
    string? Revision, string Summary, IReadOnlyList<MasterDataIssue> Issues, bool IsNew);
public sealed record MasterDataAiResult(string Mode, string Model, IReadOnlyList<string> Suggestions,
    IReadOnlyList<MasterDataAiProposal> Proposals, IReadOnlyList<string> Warnings);

public sealed class MasterDataAiException(int statusCode, string message) : Exception(message)
{
    public int StatusCode { get; } = statusCode;
}
