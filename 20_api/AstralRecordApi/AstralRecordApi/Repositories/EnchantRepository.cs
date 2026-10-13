using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Services;

namespace AstralRecordApi.Repositories;

/// <summary>MasterDataDB の共通エンチャントマスタを取得する。</summary>
public class EnchantRepository(MasterDataDbContext dbContext, MasterDataCache? cache = null) : IEnchantRepository
{
    private const string MasterType = "enchant";
    private readonly MasterDataCatalog<EnchantMasterResponse> catalog = new(dbContext, cache ?? new(), MasterType);

    public EnchantMasterResponse? GetById(string enchantMasterId)
    {
        var normalizedId = NormalizeMasterId(enchantMasterId);
        return catalog.Get().ById.GetValueOrDefault(normalizedId);
    }

    private static string NormalizeMasterId(string value)
    {
        var trimmed = value.Trim();
        const string prefix = "enchant:";
        return trimmed.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
            ? trimmed[prefix.Length..]
            : trimmed;
    }
}
