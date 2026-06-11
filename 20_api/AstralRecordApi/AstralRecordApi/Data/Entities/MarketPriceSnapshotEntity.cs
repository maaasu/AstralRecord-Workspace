namespace AstralRecordApi.Data.Entities;

public class MarketPriceSnapshotEntity
{
    public Guid SnapshotId { get; set; }
    public Guid? ListingId { get; set; }
    public Guid? TransactionId { get; set; }
    public string ItemCategory { get; set; } = string.Empty;
    public string ItemId { get; set; } = string.Empty;
    public string? InstanceType { get; set; }
    public Guid? InstanceId { get; set; }
    public string? ValuationSignature { get; set; }
    public string ReferenceScope { get; set; } = string.Empty;
    public int SampleCount { get; set; }
    public string Confidence { get; set; } = "LOW";
    public long SellPrice { get; set; }
    public long SuggestedUnitPrice { get; set; }
    public long? ReferenceUnitPrice { get; set; }
    public long AllowedMinUnitPrice { get; set; }
    public long AllowedMaxUnitPrice { get; set; }
    public string Judgement { get; set; } = string.Empty;
    public decimal? RollQualityScore { get; set; }
    public string? RollQualityBucket { get; set; }
    public DateTime EvaluatedAt { get; set; }
    public DateTime CreatedAt { get; set; }
}
