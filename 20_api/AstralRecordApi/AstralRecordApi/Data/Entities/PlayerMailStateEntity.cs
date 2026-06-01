namespace AstralRecordApi.Data.Entities;

public class PlayerMailStateEntity
{
    public Guid PlayerMailStateId { get; set; }
    public Guid UserId { get; set; }
    public required string MailId { get; set; }
    public bool IsRead { get; set; }
    public DateTime? ReadAt { get; set; }
    public int Version { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
    public DateTime? DeletedAt { get; set; }
}
