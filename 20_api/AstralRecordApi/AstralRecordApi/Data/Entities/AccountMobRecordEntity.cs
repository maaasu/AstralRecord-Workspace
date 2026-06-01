namespace AstralRecordApi.Data.Entities;

public class AccountMobRecordEntity
{
    public Guid AccountMobRecordId { get; set; }
    public Guid AccountId { get; set; }
    public string MobId { get; set; } = string.Empty;
    public string MobCategory { get; set; } = string.Empty;
    public long DefeatCount { get; set; }
    public DateTime FirstDefeatedAt { get; set; }
    public DateTime LastDefeatedAt { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
