namespace AstralRecordApi.Data.Entities;

public class MarketListingEntity
{
    public Guid ListingId { get; set; }
    public Guid SellerAccountId { get; set; }
    public Guid? BuyerAccountId { get; set; }
    public Guid? SourceInventoryEntryId { get; set; }
    public string ItemCategory { get; set; } = string.Empty;
    public string ItemId { get; set; } = string.Empty;
    public string? InstanceType { get; set; }
    public Guid? InstanceId { get; set; }
    public long Quantity { get; set; }
    public long RemainingQuantity { get; set; }
    public string CurrencyId { get; set; } = string.Empty;
    public long UnitPrice { get; set; }
    public long TotalPrice { get; set; }
    public long PriceFloor { get; set; }
    public long? ReferenceUnitPrice { get; set; }
    public decimal? PriceDeviationRate { get; set; }
    public string PriceConfidence { get; set; } = "LOW";
    public string? ValuationSignature { get; set; }
    public string? ValuationSnapshotJson { get; set; }
    public string Status { get; set; } = "ACTIVE";
    public string? StatusReason { get; set; }
    public DateTime ListedAt { get; set; }
    public DateTime ExpiresAt { get; set; }
    public DateTime? SoldAt { get; set; }
    public DateTime? CanceledAt { get; set; }
    /// <summary>売上受取を再送するための確定済み idempotency key です。</summary>
    public string? ProceedsClaimIdempotencyKey { get; set; }
    /// <summary>確定済み売上受取額です。</summary>
    public long? ProceedsClaimAmount { get; set; }
    /// <summary>確定済み売上受取で更新した通貨 entry ID の JSON 配列です。</summary>
    public string? ProceedsClaimAffectedInventoryEntryIdsJson { get; set; }
    /// <summary>売上受取を確定した日時です。</summary>
    public DateTime? ProceedsClaimedAt { get; set; }
    public int Version { get; set; } = 1;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
