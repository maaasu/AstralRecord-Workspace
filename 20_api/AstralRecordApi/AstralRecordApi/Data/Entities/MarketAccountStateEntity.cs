namespace AstralRecordApi.Data.Entities;

public class MarketAccountStateEntity
{
    public Guid AccountId { get; set; }
    public int CompletedTradeCount { get; set; }
    public string Tier { get; set; } = "T0";
    public int MaxActiveListingCount { get; set; } = 3;
    public DateTime? SuspendedUntil { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
