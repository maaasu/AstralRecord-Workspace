namespace AstralRecordApi.Models;

public sealed record AccountBenefitsResponse(Guid AccountId, int InstancePriorityUses, string VipTier,
    DateTime? VipExpiresAt, DateTime? DonerExpiresAt, DateTime? AstralderExpiresAt,
    int RemainingDays, int AstralderDailyCreditsRemaining);
public sealed record VipSupporterResponse(Guid AccountId, string DisplayName, string VipTier, DateTime? VipExpiresAt, int RemainingDays);
public class AccountBenefitOperationRequest
{
    public Guid OperationId { get; init; }
}
public sealed class AccountBenefitItemRequest : AccountBenefitOperationRequest
{
    public Guid InventoryEntryId { get; init; }
    public DateTime ExpectedUpdatedAt { get; init; }
}
public sealed record AccountBenefitOperationResponse(Guid OperationId, string Status, string? Reason,
    AccountBenefitsResponse Benefits, int AwardedPriorityUses = 0, InventoryOperationSnapshotResponse? InventorySnapshot = null);
