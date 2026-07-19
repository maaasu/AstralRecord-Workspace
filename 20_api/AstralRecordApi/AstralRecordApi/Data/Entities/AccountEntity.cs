namespace AstralRecordApi.Data.Entities;

public class AccountEntity
{
    public Guid Uuid { get; set; }
    public Guid UserId { get; set; }
    public string AccountName { get; set; } = string.Empty;
    public int SlotIndex { get; set; }
    public bool IsActive { get; set; }
    public byte Mode { get; set; }
    public string MenuShortcutsJson { get; set; } = """["STATUS","NONE","INVENTORY_CURRENCY","EQUIPMENT_GUI"]""";
    public int Level { get; set; } = 1;
    public long TotalExperience { get; set; }
    public string ClassId { get; set; } = "adventurer";
    public int ClassLevel { get; set; } = 1;
    public long ClassExperience { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
    public ICollection<AccountClassProgressEntity> ClassProgresses { get; set; } = [];
}
