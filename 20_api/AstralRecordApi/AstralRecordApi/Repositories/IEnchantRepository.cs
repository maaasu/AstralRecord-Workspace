using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IEnchantRepository
{
    EnchantMasterResponse? GetById(string enchantMasterId);
}
