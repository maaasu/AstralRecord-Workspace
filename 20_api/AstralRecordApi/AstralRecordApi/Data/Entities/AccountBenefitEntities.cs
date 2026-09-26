namespace AstralRecordApi.Data.Entities;

public sealed class AccountBenefitsEntity
{
    public Guid AccountId { get; set; }
    public int InstancePriorityUses { get; set; }
    public DateTime? DonerExpiresAt { get; set; }
    public DateTime? AstralderExpiresAt { get; set; }
    public int AstralderDailyCreditsRemaining { get; set; }
    public DateOnly? LastDailyClaimDate { get; set; }
}

/// <summary>購入品消費・予約消費と取消の履歴。アカウント削除後も保持する。</summary>
public sealed class AccountBenefitOperationEntity
{
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public string Kind { get; set; } = string.Empty;
    public string RequestHash { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public string? Reason { get; set; }
    public Guid? InventoryEntryId { get; set; }
    public int AwardedPriorityUses { get; set; }
    public bool Refunded { get; set; }
    public DateTime CreatedAtUtc { get; set; }
}
