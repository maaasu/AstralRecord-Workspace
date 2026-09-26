namespace AstralRecordApi.Models;

public sealed record WebMailCurrencyClaimRequest(Guid OperationId, Guid AccountId);
public sealed record WebMailClaimProcessRequest(Guid AccountId, bool PreparedOnline);
public sealed record WebMailClaimPendingResponse(Guid OperationId, Guid AccountId);
public sealed record WebMailCurrencyClaimResponse(Guid OperationId, string Status, string? Reason,
    string MailId, Guid AccountId, IReadOnlyList<Guid>? AffectedInventoryEntryIds = null,
    InventoryOperationSnapshotResponse? InventorySnapshot = null);
