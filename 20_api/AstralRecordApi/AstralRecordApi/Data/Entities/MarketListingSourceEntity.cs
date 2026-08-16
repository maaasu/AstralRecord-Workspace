namespace AstralRecordApi.Data.Entities;

/// <summary>複数 slot にまたがるスタック出品の escrow 元と、出品時に確保した数量です。</summary>
public class MarketListingSourceEntity
{
    public Guid ListingId { get; set; }
    public Guid InventoryEntryId { get; set; }
    public long Quantity { get; set; }
}
