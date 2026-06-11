namespace AstralRecordApi.Data.Entities;

public class MarketTransactionEntity
{
    public Guid TransactionId { get; set; }
    public Guid ListingId { get; set; }
    public Guid SellerAccountId { get; set; }
    public Guid BuyerAccountId { get; set; }
    public string ItemCategory { get; set; } = string.Empty;
    public string ItemId { get; set; } = string.Empty;
    public string? InstanceType { get; set; }
    public Guid? InstanceId { get; set; }
    public long Quantity { get; set; }
    public string CurrencyId { get; set; } = string.Empty;
    public long UnitPrice { get; set; }
    public long TotalPrice { get; set; }
    public long FeeAmount { get; set; }
    public long SellerProceeds { get; set; }
    public string? ValuationSignature { get; set; }
    public string? ValuationSnapshotJson { get; set; }
    public string IdempotencyKey { get; set; } = string.Empty;
    public DateTime CompletedAt { get; set; }
    public DateTime CreatedAt { get; set; }
    public Guid CreatedBy { get; set; }
}
