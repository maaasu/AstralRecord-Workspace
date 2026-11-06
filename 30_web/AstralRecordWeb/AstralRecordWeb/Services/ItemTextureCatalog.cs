using System.Text.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public sealed class ItemTextureCatalog
{
    private readonly Dictionary<(string BaseItem, int ModelData), string> models;

    public ItemTextureCatalog()
    {
        using var registry = typeof(ItemTextureCatalog).Assembly.GetManifestResourceStream("ItemModels.json")
            ?? throw new InvalidOperationException("The item model registry is missing.");
        using var document = JsonDocument.Parse(registry);
        models = document.RootElement.GetProperty("items").EnumerateArray().ToDictionary(
            model => (model.GetProperty("base_item").GetString()!, model.GetProperty("custom_model_data").GetInt32()),
            model => model.GetProperty("id").GetString()!);
    }

    public string? FindModelId(ItemMasterResponse? item)
    {
        if (item?.CustomModelData is not int modelData || string.IsNullOrWhiteSpace(item.Icon))
            return null;

        var baseItem = item.Icon.Trim().ToLowerInvariant();
        if (!baseItem.Contains(':'))
            baseItem = "minecraft:" + baseItem;

        return models.GetValueOrDefault((baseItem, modelData));
    }
}
