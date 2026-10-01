using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Utilities;

/// <summary>全移管入口で出自・所有者・装備状態を検証する。</summary>
internal static class PetInstanceAccess
{
    internal static bool IsPetType(string? type) => type?.Trim().ToUpperInvariant() is "PET" or "PET_EGG";
    internal static bool Matches(PetInstanceEntity pet, string? type, string? itemId)
        => string.Equals(type, pet.IsEgg ? "PET_EGG" : "PET", StringComparison.OrdinalIgnoreCase)
            && (string.IsNullOrWhiteSpace(itemId) || string.Equals(itemId, pet.ItemId, StringComparison.OrdinalIgnoreCase));

    internal static async Task<string?> TransferAsync(AstralRecordDbContext db, Guid id, string? type, string? itemId,
        Guid source, Guid target, Guid actor, DateTime now)
    {
        var pet = db.Database.IsSqlServer()
            ? await db.PetInstances.FromSqlInterpolated($"SELECT * FROM [dbo].[pet_instance] WITH (UPDLOCK,HOLDLOCK) WHERE [instance_id]={id}").SingleOrDefaultAsync()
            : await db.PetInstances.SingleOrDefaultAsync(p => p.InstanceId == id);
        if (pet is null || pet.IsDeleted || pet.AccountId != source || !Matches(pet, type, itemId)) return "pet_owner_mismatch";
        if (pet.Origin != "WILD") return "bred_pet_untradeable";
        if (await db.AccountPetStates.AnyAsync(s => s.AccountId == source && s.EquippedPetId == id)) return "pet_equipped";
        if (source != target) { pet.AccountId = target; pet.UpdatedAt = now; pet.UpdatedBy = actor; pet.Version++; }
        return null;
    }
}
