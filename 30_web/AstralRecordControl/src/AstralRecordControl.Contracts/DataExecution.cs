namespace AstralRecordControl.Contracts;

public static class DataExecution
{
    public static Guid ChildOperationId(Guid parentId, OperationKind kind)
    {
        var hash = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(parentId.ToString("D") + ":" + kind));
        return new Guid(hash.AsSpan(0, 16));
    }
}

public sealed record VerifyDataOperationRequest(Guid OperationId);
public sealed record MarkDataReadyForRestartRequest(Guid OperationId);
public sealed record FinishDataOperationRequest(Guid OperationId, string CommandSha256, OperationStatus Status);
public sealed record AgentListBackupsRequest(Guid ConnectionEpoch, Guid ServerId);
public sealed record AgentResumeDataOperationRequest(Guid ConnectionEpoch, Guid OperationId, string Reason);
