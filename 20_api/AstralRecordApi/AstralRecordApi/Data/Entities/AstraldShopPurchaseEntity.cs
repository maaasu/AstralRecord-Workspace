namespace AstralRecordApi.Data.Entities;

public sealed class AstraldShopPurchaseEntity
{
    public Guid OperationId { get; set; }
    public Guid ActorUserUuid { get; set; }
    public Guid AccountId { get; set; }
    public string ItemId { get; set; } = string.Empty;
    public string? ChannelId { get; set; }
    public string RequestHash { get; set; } = string.Empty;
    public int PricePaidAstrald { get; set; }
    public string EffectType { get; set; } = string.Empty;
    public double EffectValue { get; set; }
    public int? DurationSeconds { get; set; }
    public string Status { get; set; } = string.Empty;
    public string? Reason { get; set; }
    public string? ResponseJson { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
}
