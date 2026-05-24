namespace AstralRecordApi.Data.Entities;

public class PlayerSettingEntity
{
    public Guid UserSettingId { get; set; }
    public Guid UserId { get; set; }
    public string SettingKey { get; set; } = string.Empty;
    public string SettingValueJson { get; set; } = "{}";
    public int Version { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
