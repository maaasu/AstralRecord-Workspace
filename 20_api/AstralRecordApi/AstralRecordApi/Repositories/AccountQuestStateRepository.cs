using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class AccountQuestStateRepository(AstralRecordDbContext dbContext) : IAccountQuestStateRepository
{
    public async Task<AccountQuestStateResponse> GetByAccountIdAsync(Guid accountId)
    {
        var accountExists = await dbContext.Accounts
            .AsNoTracking()
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");

        var entity = await dbContext.AccountQuestStates
            .AsNoTracking()
            .Include(state => state.ActiveQuests)
                .ThenInclude(active => active.ObjectiveProgress)
            .Include(state => state.Completions)
            .Include(state => state.Cooldowns)
            .FirstOrDefaultAsync(state => state.AccountId == accountId && !state.IsDeleted);

        return entity is null ? Unsaved(accountId) : Map(entity);
    }

    public async Task<AccountQuestStateResponse> UpsertAsync(Guid accountId, AccountQuestStateUpsertRequest request)
    {
        var accountExists = await dbContext.Accounts
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");
        if (request.UpdatedBy == Guid.Empty)
            throw new ArgumentException("UpdatedBy is required.", nameof(request));

        var now = DateTime.UtcNow;
        var state = await dbContext.AccountQuestStates
            .Include(value => value.ActiveQuests)
                .ThenInclude(active => active.ObjectiveProgress)
            .Include(value => value.Completions)
            .Include(value => value.Cooldowns)
            .FirstOrDefaultAsync(value => value.AccountId == accountId && !value.IsDeleted);

        if (state is null)
        {
            state = new AccountQuestStateEntity
            {
                AccountQuestStateId = Guid.NewGuid(),
                AccountId = accountId,
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };
            await dbContext.AccountQuestStates.AddAsync(state);
        }
        else
        {
            state.Version = Math.Max(1, state.Version + 1);
            state.UpdatedAt = now;
            state.UpdatedBy = request.UpdatedBy;
            dbContext.AccountQuestObjectiveProgresses.RemoveRange(
                state.ActiveQuests.SelectMany(active => active.ObjectiveProgress));
            dbContext.AccountQuestActives.RemoveRange(state.ActiveQuests);
            dbContext.AccountQuestCompletions.RemoveRange(state.Completions);
            dbContext.AccountQuestCooldowns.RemoveRange(state.Cooldowns);
            state.ActiveQuests.Clear();
            state.Completions.Clear();
            state.Cooldowns.Clear();
        }

        AddActiveQuests(state, request.ActiveQuests, now, request.UpdatedBy);
        AddCompletions(state, request.Completions, now, request.UpdatedBy);
        AddCooldowns(state, request.Cooldowns, now, request.UpdatedBy);
        await dbContext.SaveChangesAsync();
        return await GetByAccountIdAsync(accountId);
    }

    private static void AddActiveQuests(
        AccountQuestStateEntity state,
        IReadOnlyList<AccountQuestActiveRequest> requests,
        DateTime now,
        Guid updatedBy)
    {
        foreach (var request in requests.Where(request => !string.IsNullOrWhiteSpace(request.QuestId)))
        {
            var active = new AccountQuestActiveEntity
            {
                AccountQuestActiveId = Guid.NewGuid(),
                AccountQuestStateId = state.AccountQuestStateId,
                QuestId = request.QuestId.Trim(),
                AcceptedAt = FromEpoch(request.AcceptedAtEpochMillis, nameof(request.AcceptedAtEpochMillis)),
                AcceptedNpcId = Normalize(request.AcceptedNpcId),
                ReadyToTurnIn = request.ReadyToTurnIn,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
            };
            foreach (var objective in request.ObjectiveProgress
                         .Where(objective => !string.IsNullOrWhiteSpace(objective.ObjectiveId)))
            {
                active.ObjectiveProgress.Add(new AccountQuestObjectiveProgressEntity
                {
                    AccountQuestObjectiveProgressId = Guid.NewGuid(),
                    AccountQuestActiveId = active.AccountQuestActiveId,
                    ObjectiveId = objective.ObjectiveId.Trim(),
                    Progress = Math.Max(0, objective.Progress),
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = updatedBy,
                    UpdatedBy = updatedBy,
                });
            }
            state.ActiveQuests.Add(active);
        }
    }

    private static void AddCompletions(
        AccountQuestStateEntity state,
        IReadOnlyList<AccountQuestCompletionRequest> requests,
        DateTime now,
        Guid updatedBy)
    {
        foreach (var request in requests.Where(request => !string.IsNullOrWhiteSpace(request.QuestId)))
        {
            state.Completions.Add(new AccountQuestCompletionEntity
            {
                AccountQuestCompletionId = Guid.NewGuid(),
                AccountQuestStateId = state.AccountQuestStateId,
                QuestId = request.QuestId.Trim(),
                CompletedAt = FromEpoch(request.CompletedAtEpochMillis, nameof(request.CompletedAtEpochMillis)),
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
            });
        }
    }

    private static void AddCooldowns(
        AccountQuestStateEntity state,
        IReadOnlyList<AccountQuestCooldownRequest> requests,
        DateTime now,
        Guid updatedBy)
    {
        foreach (var request in requests.Where(request => !string.IsNullOrWhiteSpace(request.QuestId)))
        {
            state.Cooldowns.Add(new AccountQuestCooldownEntity
            {
                AccountQuestCooldownId = Guid.NewGuid(),
                AccountQuestStateId = state.AccountQuestStateId,
                QuestId = request.QuestId.Trim(),
                CooldownUntil = FromEpoch(request.CooldownUntilEpochMillis, nameof(request.CooldownUntilEpochMillis)),
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
            });
        }
    }

    private static AccountQuestStateResponse Unsaved(Guid accountId) => new()
    {
        AccountQuestStateId = null,
        AccountId = accountId,
        ActiveQuests = [],
        Completions = [],
        Cooldowns = [],
        IsSaved = false,
        Version = 0,
    };

    private static AccountQuestStateResponse Map(AccountQuestStateEntity state) => new()
    {
        AccountQuestStateId = state.AccountQuestStateId,
        AccountId = state.AccountId,
        ActiveQuests = state.ActiveQuests.Select(active => new AccountQuestActiveResponse
        {
            QuestId = active.QuestId,
            AcceptedAtEpochMillis = new DateTimeOffset(active.AcceptedAt).ToUnixTimeMilliseconds(),
            AcceptedNpcId = active.AcceptedNpcId,
            ReadyToTurnIn = active.ReadyToTurnIn,
            ObjectiveProgress = active.ObjectiveProgress.Select(objective => new AccountQuestObjectiveProgressResponse
            {
                ObjectiveId = objective.ObjectiveId,
                Progress = objective.Progress,
            }).OrderBy(objective => objective.ObjectiveId, StringComparer.Ordinal).ToList(),
        }).OrderBy(active => active.QuestId, StringComparer.Ordinal).ToList(),
        Completions = state.Completions.Select(completion => new AccountQuestCompletionResponse
        {
            QuestId = completion.QuestId,
            CompletedAtEpochMillis = new DateTimeOffset(completion.CompletedAt).ToUnixTimeMilliseconds(),
        }).OrderBy(completion => completion.QuestId, StringComparer.Ordinal).ToList(),
        Cooldowns = state.Cooldowns.Select(cooldown => new AccountQuestCooldownResponse
        {
            QuestId = cooldown.QuestId,
            CooldownUntilEpochMillis = new DateTimeOffset(cooldown.CooldownUntil).ToUnixTimeMilliseconds(),
        }).OrderBy(cooldown => cooldown.QuestId, StringComparer.Ordinal).ToList(),
        IsSaved = true,
        Version = state.Version,
        CreatedAt = state.CreatedAt,
        UpdatedAt = state.UpdatedAt,
        CreatedBy = state.CreatedBy,
        UpdatedBy = state.UpdatedBy,
    };

    private static DateTime FromEpoch(long value, string parameterName)
    {
        try
        {
            return DateTimeOffset.FromUnixTimeMilliseconds(value).UtcDateTime;
        }
        catch (ArgumentOutOfRangeException exception)
        {
            throw new ArgumentException("Epoch milliseconds are out of range.", parameterName, exception);
        }
    }

    private static string? Normalize(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
