namespace AstralRecordApi.Data.Entities;

public class AccountEntity
{
    public Guid Uuid { get; set; }
    public Guid UserId { get; set; }
    public string AccountName { get; set; } = string.Empty;
    public int SlotIndex { get; set; }
    public bool IsActive { get; set; }
    public byte Mode { get; set; }
    public string MenuShortcutsJson { get; set; } = """["INVENTORY_NORMAL","INVENTORY_EQUIPMENT","INVENTORY_RUNE","INVENTORY_CURRENCY"]""";
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
