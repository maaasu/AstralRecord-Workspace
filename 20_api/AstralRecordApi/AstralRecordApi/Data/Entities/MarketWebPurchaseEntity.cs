namespace AstralRecordApi.Data.Entities;

/// <summary>Webからの本人確認済み購入要求と、その確定結果です。</summary>
public sealed class MarketWebPurchaseEntity
{
    public Guid OperationId { get; set; }
    public Guid ActorUserUuid { get; set; }
    public Guid BuyerAccountId { get; set; }
    public Guid ListingId { get; set; }
    public long Quantity { get; set; }
    public string Status { get; set; } = "PENDING";
    public Guid? TransactionId { get; set; }
    public string? ErrorCode { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
}
