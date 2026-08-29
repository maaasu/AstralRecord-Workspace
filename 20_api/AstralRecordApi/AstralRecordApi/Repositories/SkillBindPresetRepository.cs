using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class SkillBindPresetRepository(AstralRecordDbContext dbContext) : ISkillBindPresetRepository
{
    public const int PresetCount = 6;
    public const int ActionRingSlotCount = 6;
    public const int PassiveSlotCount = 9;
    private const int DefaultUnlockedPresetCount = 3;
    internal const string WeaponNormalAttackBindingId = "__weapon_normal_attack__";

    public async Task<IReadOnlyList<SkillBindPresetResponse>> GetByAccountIdAsync(Guid accountId)
    {
        var saved = await dbContext.SkillBindPresets
            .AsNoTracking()
            .Where(x => x.AccountId == accountId && !x.IsDeleted)
            .OrderBy(x => x.PresetIndex)
            .ToListAsync();

        // 個体ID導入前のプリセットには skillId が保存されている。読み出し時に所有する最古の
        // learnedSkillId へ正規化して返すことで、次回保存を UUID 正本へ安全に移行する。
        var ownedLearnedSkills = await dbContext.AccountLearnedSkills
                .AsNoTracking()
                .Where(skill => skill.AccountId == accountId && !skill.IsDeleted)
                .OrderBy(skill => skill.CreatedAt)
                .ThenBy(skill => skill.LearnedSkillId)
                .Select(skill => new { skill.SkillId, skill.LearnedSkillId })
                .ToListAsync();
        var legacyBindingIds = ownedLearnedSkills
            .GroupBy(skill => skill.SkillId, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(
                group => group.Key,
                group => group.First().LearnedSkillId.ToString(),
                StringComparer.OrdinalIgnoreCase);
        var ownedBindingIds = ownedLearnedSkills
            .Select(skill => skill.LearnedSkillId.ToString())
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

        var byIndex = saved.ToDictionary(x => x.PresetIndex);
        var result = new List<SkillBindPresetResponse>(PresetCount);
        for (var index = 1; index <= PresetCount; index++)
        {
            result.Add(byIndex.TryGetValue(index, out var entity)
                ? Map(entity, legacyBindingIds, ownedBindingIds)
                : Empty(accountId, index));
        }

        return result;
    }

    public async Task<SkillBindPresetResponse?> UpsertAsync(
        Guid accountId,
        int presetIndex,
        SkillBindPresetUpsertRequest request)
    {
        if (presetIndex is < 1 or > PresetCount)
            return null;

        var now = DateTime.UtcNow;
        var activeSlots = NormalizeSlots(request.ActiveSkillSlots, ActionRingSlotCount);
        var passiveSlots = NormalizeSlots(request.PassiveSkillSlots, PassiveSlotCount);
        var leftClickSkillId = NormalizeSkillId(request.LeftClickSkillId);
        if (!await HasValidOwnedBindingsAsync(accountId, activeSlots, leftClickSkillId, passiveSlots))
            return null;
        var entity = await dbContext.SkillBindPresets
            .FirstOrDefaultAsync(x => x.AccountId == accountId
                && x.PresetIndex == presetIndex
                && !x.IsDeleted);

        if (entity is null)
        {
            entity = new SkillBindPresetEntity
            {
                SkillBindPresetId = Guid.NewGuid(),
                AccountId = accountId,
                PresetIndex = presetIndex,
                CreatedAt = now,
                CreatedBy = request.UpdatedBy,
                Version = 1,
                IsDeleted = false,
            };
            await dbContext.SkillBindPresets.AddAsync(entity);
        }
        else
        {
            entity.Version += 1;
        }

        entity.ActiveSkillSlotsJson = JsonSerializer.Serialize(activeSlots);
        entity.LeftClickSkillId = leftClickSkillId ?? string.Empty;
        entity.PassiveSkillSlotsJson = JsonSerializer.Serialize(passiveSlots);
        entity.IsUnlocked = request.IsUnlocked ?? (entity.IsUnlocked || presetIndex <= DefaultUnlockedPresetCount);
        entity.UpdatedAt = now;
        entity.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();
        return Map(entity);
    }

    public async Task<bool> SelectAsync(
        Guid accountId,
        int presetIndex,
        SkillBindPresetSelectionRequest request)
    {
        if (presetIndex is < 1 or > PresetCount)
            return false;

        var accountExists = await dbContext.Accounts
            .AsNoTracking()
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!accountExists)
            return false;

        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();

            await using var transaction = await dbContext.Database.BeginTransactionAsync();
            var presets = await dbContext.SkillBindPresets
                .Where(preset => preset.AccountId == accountId && !preset.IsDeleted)
                .ToListAsync();
            var selected = presets.FirstOrDefault(preset => preset.PresetIndex == presetIndex);
            if (selected is null && presetIndex > DefaultUnlockedPresetCount)
                return false;
            if (selected is not null && !selected.IsUnlocked && presetIndex > DefaultUnlockedPresetCount)
                return false;

            var now = DateTime.UtcNow;
            foreach (var preset in presets)
                preset.IsSelected = preset == selected;

            if (selected is null)
            {
                selected = new SkillBindPresetEntity
                {
                    SkillBindPresetId = Guid.NewGuid(),
                    AccountId = accountId,
                    PresetIndex = presetIndex,
                    ActiveSkillSlotsJson = JsonSerializer.Serialize(EmptySlots(ActionRingSlotCount)),
                    LeftClickSkillId = WeaponNormalAttackBindingId,
                    PassiveSkillSlotsJson = JsonSerializer.Serialize(EmptySlots(PassiveSlotCount)),
                    IsUnlocked = presetIndex <= DefaultUnlockedPresetCount,
                    IsSelected = true,
                    Version = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                    IsDeleted = false,
                };
                await dbContext.SkillBindPresets.AddAsync(selected);
            }
            else
            {
                selected.IsSelected = true;
                selected.UpdatedAt = now;
                selected.UpdatedBy = request.UpdatedBy;
            }

            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return true;
        });
    }

    private static SkillBindPresetResponse Empty(Guid accountId, int presetIndex) => new()
    {
        AccountId = accountId,
        PresetIndex = presetIndex,
        ActiveSkillSlots = EmptySlots(ActionRingSlotCount),
        LeftClickSkillId = WeaponNormalAttackBindingId,
        PassiveSkillSlots = EmptySlots(PassiveSlotCount),
        IsUnlocked = presetIndex <= DefaultUnlockedPresetCount,
        IsSaved = false,
    };

    private static SkillBindPresetResponse Map(
        SkillBindPresetEntity entity,
        IReadOnlyDictionary<string, string>? legacyBindingIds = null,
        IReadOnlySet<string>? ownedBindingIds = null) => new()
    {
        SkillBindPresetId = entity.SkillBindPresetId,
        AccountId = entity.AccountId,
        PresetIndex = entity.PresetIndex,
        ActiveSkillSlots = NormalizeLegacySlots(
            DeserializeSlots(entity.ActiveSkillSlotsJson, ActionRingSlotCount), legacyBindingIds, ownedBindingIds,
            allowWeaponNormalAttack: true),
        LeftClickSkillId = entity.LeftClickSkillId is null
            ? WeaponNormalAttackBindingId
            : NormalizeLegacyBinding(
                entity.LeftClickSkillId, legacyBindingIds, ownedBindingIds, allowWeaponNormalAttack: true),
        PassiveSkillSlots = NormalizeLegacySlots(
            DeserializeSlots(entity.PassiveSkillSlotsJson, PassiveSlotCount), legacyBindingIds, ownedBindingIds),
        IsUnlocked = entity.IsUnlocked || entity.PresetIndex <= DefaultUnlockedPresetCount,
        IsSelected = entity.IsSelected,
        IsSaved = true,
        Version = entity.Version,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
    };

    private static IReadOnlyList<string?> EmptySlots(int slotCount)
        => Enumerable.Repeat<string?>(null, slotCount).ToArray();

    private static IReadOnlyList<string?> NormalizeSlots(IEnumerable<string?> slots, int slotCount)
        => slots
            .Take(slotCount)
            .Select(slot => string.IsNullOrWhiteSpace(slot) ? null : slot.Trim())
            .Concat(Enumerable.Repeat<string?>(null, slotCount))
            .Take(slotCount)
            .ToArray();

    private static string? NormalizeSkillId(string? skillId)
        => string.IsNullOrWhiteSpace(skillId) ? null : skillId.Trim();

    private static IReadOnlyList<string?> NormalizeLegacySlots(
        IReadOnlyList<string?> slots,
        IReadOnlyDictionary<string, string>? legacyBindingIds,
        IReadOnlySet<string>? ownedBindingIds,
        bool allowWeaponNormalAttack = false)
        => slots.Select(slot => NormalizeLegacyBinding(
            slot, legacyBindingIds, ownedBindingIds, allowWeaponNormalAttack)).ToArray();

    private static string? NormalizeLegacyBinding(
        string? binding,
        IReadOnlyDictionary<string, string>? legacyBindingIds,
        IReadOnlySet<string>? ownedBindingIds,
        bool allowWeaponNormalAttack)
    {
        var normalized = NormalizeSkillId(binding);
        if (normalized is null)
            return null;
        if (string.Equals(normalized, WeaponNormalAttackBindingId, StringComparison.Ordinal))
            return allowWeaponNormalAttack ? WeaponNormalAttackBindingId : null;
        if (Guid.TryParse(normalized, out _))
            return ownedBindingIds is null || ownedBindingIds.Contains(normalized) ? normalized : null;
        return legacyBindingIds is not null && legacyBindingIds.TryGetValue(normalized, out var learnedSkillId)
            ? learnedSkillId
            : null;
    }

    private async Task<bool> HasValidOwnedBindingsAsync(
        Guid accountId,
        IReadOnlyList<string?> activeSlots,
        string? leftClickSkillId,
        IReadOnlyList<string?> passiveSlots)
    {
        if (!await dbContext.Accounts.AsNoTracking()
                .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted))
            return false;
        if (passiveSlots.Any(slot => string.Equals(slot, WeaponNormalAttackBindingId, StringComparison.Ordinal)))
            return false;

        var rawBindings = activeSlots
            .Concat(passiveSlots)
            .Append(leftClickSkillId)
            .Where(binding => !string.IsNullOrWhiteSpace(binding)
                && !string.Equals(binding, WeaponNormalAttackBindingId, StringComparison.Ordinal))
            .Select(binding => binding!)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();
        var learnedSkillIds = new List<Guid>(rawBindings.Length);
        foreach (var binding in rawBindings)
        {
            if (!Guid.TryParse(binding, out var learnedSkillId))
                return false;
            learnedSkillIds.Add(learnedSkillId);
        }
        if (learnedSkillIds.Count == 0)
            return true;

        var ownedCount = await dbContext.AccountLearnedSkills.AsNoTracking()
            .CountAsync(skill => skill.AccountId == accountId
                && !skill.IsDeleted
                && learnedSkillIds.Contains(skill.LearnedSkillId));
        return ownedCount == learnedSkillIds.Count;
    }

    private static IReadOnlyList<string?> DeserializeSlots(string? json, int slotCount)
    {
        if (string.IsNullOrWhiteSpace(json))
            return EmptySlots(slotCount);

        try
        {
            return NormalizeSlots(JsonSerializer.Deserialize<IReadOnlyList<string?>>(json) ?? [], slotCount);
        }
        catch (JsonException)
        {
            return EmptySlots(slotCount);
        }
    }
}
