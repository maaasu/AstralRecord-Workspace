namespace AstralRecordApi.Data.Entities;

/// <summary>特定ユーザーだけへ配信する動的メール本文です。</summary>
public class PlayerMailDeliveryEntity
{
    public Guid PlayerMailDeliveryId { get; set; }
    public Guid UserId { get; set; }
    public string MailId { get; set; } = string.Empty;
    public string PayloadJson { get; set; } = string.Empty;
    public int Version { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
