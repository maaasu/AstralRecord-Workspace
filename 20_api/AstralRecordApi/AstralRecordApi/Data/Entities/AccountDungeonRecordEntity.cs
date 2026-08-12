namespace AstralRecordApi.Data.Entities;

public class AccountDungeonRecordEntity
{
    public Guid AccountDungeonRecordId { get; set; }
    public Guid AccountId { get; set; }
    public string DungeonId { get; set; } = string.Empty;
    public long ClearCount { get; set; }
    public DateTime FirstClearedAt { get; set; }
    public DateTime LastClearedAt { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
