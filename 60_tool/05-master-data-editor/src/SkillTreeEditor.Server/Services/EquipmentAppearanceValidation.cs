using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>防具の着用外観が管理パックの参照形式と身体装備部位に一致することを検証する。</summary>
public static partial class EquipmentAppearanceValidation
{
    /// <summary>任意の着用外観参照を検証し、既存マスターの未指定値は受け入れる。</summary>
    public static void Validate(JsonObject item, ICollection<MasterDataIssue> issues)
    {
        if (item["appearance"] is not JsonObject appearance || appearance["equipmentAsset"] is null) return;
        var value = appearance["equipmentAsset"];
        if (value is not JsonValue scalar || !scalar.TryGetValue<string>(out var asset)
            || string.IsNullOrEmpty(asset) || asset.Length > 160 || !EquipmentAssetPattern().IsMatch(asset))
        {
            issues.Add(new("error", "EQUIPMENT_ASSET_FORMAT", "/appearance/equipmentAsset",
                "着用防具の外観は astralrecord:<path> を160文字以内で指定してください。pathは小文字英数字・_と/区切りです。"));
            return;
        }
        var slot = item["equipment"] is JsonObject equipment ? equipment["slot"]?.ToString() : null;
        if (slot is not ("HEAD" or "CHEST" or "LEGS" or "FEET"))
            issues.Add(new("error", "EQUIPMENT_ASSET_SLOT", "/appearance/equipmentAsset",
                "着用防具の外観は HEAD / CHEST / LEGS / FEET の身体防具だけに指定できます。"));
    }

    [GeneratedRegex("^astralrecord:[a-z0-9_]+(?:/[a-z0-9_]+)*\\z")]
    private static partial Regex EquipmentAssetPattern();
}
