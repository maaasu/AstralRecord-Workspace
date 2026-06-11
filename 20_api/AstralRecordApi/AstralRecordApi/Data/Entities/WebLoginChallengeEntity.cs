namespace AstralRecordApi.Data.Entities;

public class WebLoginChallengeEntity
{
    public Guid ChallengeId { get; set; }
    public Guid UserId { get; set; }
    public string LoginCodeHash { get; set; } = string.Empty;
    public DateTime IssuedAt { get; set; }
    public DateTime ExpiresAt { get; set; }
    public DateTime? ConsumedAt { get; set; }
    public DateTime? RevokedAt { get; set; }
    public int FailedAttempts { get; set; }
    public string IssuedByServer { get; set; } = string.Empty;
    public DateTime CreatedAt { get; set; }
}
