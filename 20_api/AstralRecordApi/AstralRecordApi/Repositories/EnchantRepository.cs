using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>MasterDataDB の共通エンチャントマスタを取得する。</summary>
public class EnchantRepository(MasterDataDbContext dbContext) : IEnchantRepository
{
    private const string MasterType = "enchant";

    public EnchantMasterResponse? GetById(string enchantMasterId)
    {
        var normalizedId = NormalizeMasterId(enchantMasterId);
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.MasterId == normalizedId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<EnchantMasterResponse>(payload);
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
