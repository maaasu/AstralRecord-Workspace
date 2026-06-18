namespace AstralRecordApi.Data.Entities;

public class AccountWaystoneUnlockEntity
{
    public Guid AccountWaystoneUnlockId { get; set; }
    public Guid AccountId { get; set; }
    public string WaystoneId { get; set; } = string.Empty;
    public DateTime UnlockedAt { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
