using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class SkillBindPresetRepository(AstralRecordDbContext dbContext) : ISkillBindPresetRepository
{
    public const int PresetCount = 9;
    public const int SlotCount = 8;
    private const int DefaultUnlockedPresetCount = 3;

    public async Task<IReadOnlyList<SkillBindPresetResponse>> GetByAccountIdAsync(Guid accountId)
    {
        var saved = await dbContext.SkillBindPresets
            .AsNoTracking()
            .Where(x => x.AccountId == accountId && !x.IsDeleted)
            .OrderBy(x => x.PresetIndex)
            .ToListAsync();

        var byIndex = saved.ToDictionary(x => x.PresetIndex);
        var result = new List<SkillBindPresetResponse>(PresetCount);
        for (var index = 1; index <= PresetCount; index++)
        {
            result.Add(byIndex.TryGetValue(index, out var entity)
                ? Map(entity)
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
        var activeSlots = NormalizeSlots(request.ActiveSkillSlots);
        var passiveSlots = NormalizeSlots(request.PassiveSkillSlots);
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
        entity.PassiveSkillSlotsJson = JsonSerializer.Serialize(passiveSlots);
        entity.IsUnlocked = request.IsUnlocked ?? (entity.IsUnlocked || presetIndex <= DefaultUnlockedPresetCount);
        entity.UpdatedAt = now;
        entity.UpdatedBy = request.UpdatedBy;

        await dbContext.SaveChangesAsync();
        return Map(entity);
    }

    private static SkillBindPresetResponse Empty(Guid accountId, int presetIndex) => new()
    {
        AccountId = accountId,
        PresetIndex = presetIndex,
        ActiveSkillSlots = EmptySlots(),
        PassiveSkillSlots = EmptySlots(),
        IsUnlocked = presetIndex <= DefaultUnlockedPresetCount,
        IsSaved = false,
    };

    private static SkillBindPresetResponse Map(SkillBindPresetEntity entity) => new()
    {
        SkillBindPresetId = entity.SkillBindPresetId,
        AccountId = entity.AccountId,
        PresetIndex = entity.PresetIndex,
        ActiveSkillSlots = DeserializeSlots(entity.ActiveSkillSlotsJson),
        PassiveSkillSlots = DeserializeSlots(entity.PassiveSkillSlotsJson),
        IsUnlocked = entity.IsUnlocked || entity.PresetIndex <= DefaultUnlockedPresetCount,
        IsSaved = true,
        Version = entity.Version,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
    };

    private static IReadOnlyList<string?> EmptySlots()
        => Enumerable.Repeat<string?>(null, SlotCount).ToArray();

    private static IReadOnlyList<string?> NormalizeSlots(IEnumerable<string?> slots)
        => slots
            .Take(SlotCount)
            .Select(slot => string.IsNullOrWhiteSpace(slot) ? null : slot.Trim())
            .Concat(Enumerable.Repeat<string?>(null, SlotCount))
            .Take(SlotCount)
            .ToArray();

    private static IReadOnlyList<string?> DeserializeSlots(string? json)
    {
        if (string.IsNullOrWhiteSpace(json))
            return EmptySlots();

        try
        {
            return NormalizeSlots(JsonSerializer.Deserialize<IReadOnlyList<string?>>(json) ?? []);
        }
        catch (JsonException)
        {
            return EmptySlots();
        }
    }
}
