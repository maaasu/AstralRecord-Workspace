namespace AstralRecordApi.Data.Entities;

/// <summary>
/// マーケット出品作成の冪等な完了結果を保持します。
/// </summary>
public class MarketListingCreateReceiptEntity
{
    public Guid OperationId { get; set; }
    public Guid SellerAccountId { get; set; }
    public string RequestHash { get; set; } = string.Empty;
    public Guid ListingId { get; set; }
    public string ResponseJson { get; set; } = string.Empty;
    public DateTime CompletedAt { get; set; }
    public DateTime CreatedAt { get; set; }
}
