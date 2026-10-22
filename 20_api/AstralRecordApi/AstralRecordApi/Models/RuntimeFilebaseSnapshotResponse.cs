namespace AstralRecordApi.Models;

public sealed record RuntimeFilebaseSnapshotResponse(
    int SchemaVersion,
    IReadOnlyList<RuntimeFilebaseFileResponse> Files);

public sealed record RuntimeFilebaseFileResponse(string Path, string Content);
