namespace AstralRecordApi.Data.Entities;

public sealed class PlayerAdminEditDrainEntity
{
    public Guid EditSessionId { get; set; }
    public string ServerId { get; set; } = string.Empty;
    public Guid ServerSessionId { get; set; }
    public bool Saved { get; set; }
    public bool Offline { get; set; }
    public Guid? AckId { get; set; }
    public DateTime? AcknowledgedAtUtc { get; set; }
}
