namespace AstralRecordWeb.Models;

public sealed record ItemIcon(string? Material, string? HeadTexture, int? CustomModelData, string Fallback = "✦")
{
    public ItemIcon(ItemMasterResponse? item, string fallback = "✦")
        : this(item?.Icon, item?.IconTexture, item?.CustomModelData, fallback)
    {
    }
}
