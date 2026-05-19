using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Utilities;

namespace AstralRecordApi.Services;

public class RuneService(IItemRepository itemRepository, IRuneRepository runeRepository, IAccountRepository accountRepository) : IRuneService
{
    public async Task<RuneInstanceResponse?> CreateAsync(RuneCreateRequest request)
    {
        var account = await accountRepository.GetByUuidAsync(request.AccountId);
        if (account is null)
            return null;

        var item = itemRepository.GetById(request.RuneId);
        if (item?.Rune is null)
            return null;

        var now = DateTime.UtcNow;
        var instanceId = Guid.NewGuid();

        var instance = new RuneInstanceEntity
        {
            RuneInstanceId = instanceId,
            AccountId = request.AccountId,
            ItemId = request.RuneId,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = request.CreatedBy,
            UpdatedBy = request.CreatedBy,
            IsDeleted = false,
        };

        var statRolls = BuildStatRolls(instanceId, item.Rune.Stats, request.CreatedBy, now);
        await runeRepository.AddAsync(instance, statRolls);

        return MapToResponse(instance, statRolls);
    }

    public async Task<RuneInstanceResponse?> GetByInstanceIdAsync(Guid instanceId)
    {
        var instance = await runeRepository.FindInstanceAsync(instanceId);
        if (instance is null)
            return null;

        var statRolls = await runeRepository.FindStatRollsAsync(instanceId);
        return MapToResponse(instance, statRolls);
    }

    private static IReadOnlyList<RuneInstanceStatRollEntity> BuildStatRolls(
        Guid instanceId,
        IReadOnlyList<ItemRuneStatResponse> stats,
        Guid createdBy,
        DateTime now)
    {
        var result = new List<RuneInstanceStatRollEntity>();
        var sortOrder = 0;

        foreach (var stat in stats)
        {
            if (string.IsNullOrWhiteSpace(stat.Status) || string.IsNullOrWhiteSpace(stat.Type) || string.IsNullOrWhiteSpace(stat.Value))
                continue;

            var valueSource = !string.IsNullOrWhiteSpace(stat.Random)
                ? stat.Random.Trim()
                : stat.Value.Trim();

            result.Add(new RuneInstanceStatRollEntity
            {
                StatRollId = Guid.NewGuid(),
                RuneInstanceId = instanceId,
                Status = stat.Status,
                Type = stat.Type,
                RandomValue = RangeValueResolver.ResolveNumericString(valueSource),
                SortOrder = sortOrder++,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = createdBy,
                UpdatedBy = createdBy,
                IsDeleted = false,
            });
        }

        return result;
    }

    private static RuneInstanceResponse MapToResponse(
        RuneInstanceEntity instance,
        IEnumerable<RuneInstanceStatRollEntity> statRolls) => new()
    {
        RuneInstanceId = instance.RuneInstanceId,
        AccountId = instance.AccountId,
        ItemId = instance.ItemId,
        CreatedAt = instance.CreatedAt,
        UpdatedAt = instance.UpdatedAt,
        StatRolls = statRolls.Select(r => new RuneInstanceStatRollResponse
        {
            StatRollId = r.StatRollId,
            Status = r.Status,
            Type = r.Type,
            Value = r.RandomValue,
            SortOrder = r.SortOrder,
        }).ToList(),
    };
}
