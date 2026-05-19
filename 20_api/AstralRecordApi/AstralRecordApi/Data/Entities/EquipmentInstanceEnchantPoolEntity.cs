namespace AstralRecordApi.Data.Entities;

public class EquipmentInstanceEnchantPoolEntity
{
    public Guid EnchantPoolId { get; set; }
    public Guid EquipmentInstanceId { get; set; }
    public int PoolIndex { get; set; }
    public string? RecipeId { get; set; }
    public string? RequiredMaterialItemId { get; set; }
    public int RequiredMaterialAmount { get; set; } = 1;
    public int RequiredCurrency { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
