namespace AstralRecordApi.Data.Entities;

public sealed class WebMailCurrencyClaimEntity
{
    public Guid OperationId { get; set; }
    public Guid ActorUserUuid { get; set; }
    public Guid AccountId { get; set; }
    public string MailId { get; set; } = string.Empty;
    public string RequestHash { get; set; } = string.Empty;
    public string CurrencyRewardsJson { get; set; } = "[]";
    public bool HasNonCurrencyRewards { get; set; }
    public string Status { get; set; } = string.Empty;
    public string? Reason { get; set; }
    public string? ResponseJson { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
}
