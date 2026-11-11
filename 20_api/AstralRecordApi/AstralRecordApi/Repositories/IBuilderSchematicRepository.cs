using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IBuilderSchematicRepository
{
    bool IsEnabled { get; }
    string? IssueTicket(Guid userUuid);
    (BuilderSchematicEnqueueResult Result, Guid Id) Enqueue(Guid userUuid, string token, string name, byte[] content);
    BuilderSchematicResponse? GetUpload(Guid id, Guid userUuid);
    IReadOnlyList<BuilderSchematicResponse> GetUploads(Guid userUuid);
    bool DeleteUpload(Guid id, Guid userUuid);
    IReadOnlyList<BuilderSchematicResponse> GetPending();
    IReadOnlyList<BuilderSchematicResponse> GetRegistered(Guid userUuid);
    byte[]? GetFile(Guid id, Guid? userUuid);
    BuilderSchematicDecisionResult Decide(Guid id, bool accepted, string? error);
}

public enum BuilderSchematicEnqueueResult
{
    Accepted,
    InvalidTicket,
    QuotaExceeded
}

public enum BuilderSchematicDecisionResult
{
    Applied,
    Missing,
    Conflict
}
