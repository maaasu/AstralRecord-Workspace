namespace AstralRecordControl.Contracts;

public sealed record VerifyDataOperationRequest(Guid OperationId);
public sealed record FinishDataOperationRequest(Guid OperationId, string CommandSha256, OperationStatus Status);
public sealed record AgentListBackupsRequest(Guid ConnectionEpoch, Guid ServerId);
public sealed record AgentResumeDataOperationRequest(Guid ConnectionEpoch, Guid OperationId, string Reason);
