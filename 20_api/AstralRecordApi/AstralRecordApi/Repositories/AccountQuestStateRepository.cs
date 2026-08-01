using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using System.Data;

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
        ArgumentNullException.ThrowIfNull(request);
        if (request.UpdatedBy == Guid.Empty)
            throw new ArgumentException("UpdatedBy is required.", nameof(request));
        ValidateRequest(request);

        var executionStrategy = dbContext.Database.CreateExecutionStrategy();
        return await executionStrategy.ExecuteAsync(async () =>
        {
            await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var response = await UpsertInTransactionAsync(accountId, request);
            await transaction.CommitAsync();
            return response;
        });
    }

    private async Task<AccountQuestStateResponse> UpsertInTransactionAsync(
        Guid accountId,
        AccountQuestStateUpsertRequest request)
    {
        var accountExists = await dbContext.Accounts
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");

        var now = DateTime.UtcNow;
        var state = await dbContext.AccountQuestStates
            .FromSqlInterpolated($"""
                SELECT *
                FROM dbo.account_quest_state WITH (UPDLOCK, HOLDLOCK)
                WHERE account_id = {accountId} AND is_deleted = 0
                """)
            .Include(value => value.ActiveQuests)
                .ThenInclude(active => active.ObjectiveProgress)
            .Include(value => value.Completions)
            .Include(value => value.Cooldowns)
            .FirstOrDefaultAsync();

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

        AddActiveQuests(dbContext, state, request.ActiveQuests, now, request.UpdatedBy);
        AddCompletions(dbContext, state, request.Completions, now, request.UpdatedBy);
        AddCooldowns(dbContext, state, request.Cooldowns, now, request.UpdatedBy);
        await dbContext.SaveChangesAsync();
        return Map(state);
    }

    private static void AddActiveQuests(
        AstralRecordDbContext dbContext,
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
            dbContext.AccountQuestActives.Add(active);
        }
    }

    private static void AddCompletions(
        AstralRecordDbContext dbContext,
        AccountQuestStateEntity state,
        IReadOnlyList<AccountQuestCompletionRequest> requests,
        DateTime now,
        Guid updatedBy)
    {
        foreach (var request in requests.Where(request => !string.IsNullOrWhiteSpace(request.QuestId)))
        {
            var completion = new AccountQuestCompletionEntity
            {
                AccountQuestCompletionId = Guid.NewGuid(),
                AccountQuestStateId = state.AccountQuestStateId,
                QuestId = request.QuestId.Trim(),
                CompletedAt = FromEpoch(request.CompletedAtEpochMillis, nameof(request.CompletedAtEpochMillis)),
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
            };
            state.Completions.Add(completion);
            dbContext.AccountQuestCompletions.Add(completion);
        }
    }

    private static void AddCooldowns(
        AstralRecordDbContext dbContext,
        AccountQuestStateEntity state,
        IReadOnlyList<AccountQuestCooldownRequest> requests,
        DateTime now,
        Guid updatedBy)
    {
        foreach (var request in requests.Where(request => !string.IsNullOrWhiteSpace(request.QuestId)))
        {
            var cooldown = new AccountQuestCooldownEntity
            {
                AccountQuestCooldownId = Guid.NewGuid(),
                AccountQuestStateId = state.AccountQuestStateId,
                QuestId = request.QuestId.Trim(),
                CooldownUntil = FromEpoch(request.CooldownUntilEpochMillis, nameof(request.CooldownUntilEpochMillis)),
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
            };
            state.Cooldowns.Add(cooldown);
            dbContext.AccountQuestCooldowns.Add(cooldown);
        }
    }

    private static void ValidateRequest(AccountQuestStateUpsertRequest request)
    {
        ValidateActiveQuests(request.ActiveQuests);
        ValidateQuestIds(request.Completions, completion => completion.QuestId, nameof(request.Completions));
        ValidateQuestIds(request.Cooldowns, cooldown => cooldown.QuestId, nameof(request.Cooldowns));
    }

    private static void ValidateActiveQuests(IReadOnlyList<AccountQuestActiveRequest>? requests)
    {
        if (requests is null)
            throw new ArgumentException("ActiveQuests is required.", nameof(requests));

        var questIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var request in requests)
        {
            if (request is null)
                throw new ArgumentException("ActiveQuests cannot contain null.", nameof(requests));

            var questId = ValidateId(request.QuestId, "ActiveQuests.questId");
            if (!questIds.Add(questId))
                throw new ArgumentException("ActiveQuests contains a duplicate questId.", nameof(requests));

            if (!string.IsNullOrWhiteSpace(request.AcceptedNpcId))
                ValidateId(request.AcceptedNpcId, "ActiveQuests.acceptedNpcId");
            ValidateObjectiveProgress(request.ObjectiveProgress, questId);
        }
    }

    private static void ValidateObjectiveProgress(
        IReadOnlyList<AccountQuestObjectiveProgressRequest>? requests,
        string questId)
    {
        if (requests is null)
            throw new ArgumentException("ObjectiveProgress is required.", nameof(requests));

        var objectiveIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var request in requests)
        {
            if (request is null)
                throw new ArgumentException("ObjectiveProgress cannot contain null.", nameof(requests));

            var objectiveId = ValidateId(request.ObjectiveId, "ObjectiveProgress.objectiveId");
            if (!objectiveIds.Add(objectiveId))
            {
                throw new ArgumentException(
                    $"ObjectiveProgress contains a duplicate objectiveId for quest '{questId}'.",
                    nameof(requests));
            }
        }
    }

    private static void ValidateQuestIds<T>(
        IReadOnlyList<T>? requests,
        Func<T, string?> questIdSelector,
        string parameterName)
    {
        if (requests is null)
            throw new ArgumentException($"{parameterName} is required.", parameterName);

        var questIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var request in requests)
        {
            if (request is null)
                throw new ArgumentException($"{parameterName} cannot contain null.", parameterName);

            var questId = ValidateId(questIdSelector(request), parameterName + ".questId");
            if (!questIds.Add(questId))
                throw new ArgumentException($"{parameterName} contains a duplicate questId.", parameterName);
        }
    }

    private static string ValidateId(string? value, string fieldName)
    {
        var normalized = Normalize(value);
        if (normalized is null)
            throw new ArgumentException($"{fieldName} is required.", fieldName);
        if (normalized.Length > 100)
            throw new ArgumentException($"{fieldName} must be 100 characters or fewer.", fieldName);
        return normalized;
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
            AcceptedAtEpochMillis = ToEpochMillis(active.AcceptedAt),
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
            CompletedAtEpochMillis = ToEpochMillis(completion.CompletedAt),
        }).OrderBy(completion => completion.QuestId, StringComparer.Ordinal).ToList(),
        Cooldowns = state.Cooldowns.Select(cooldown => new AccountQuestCooldownResponse
        {
            QuestId = cooldown.QuestId,
            CooldownUntilEpochMillis = ToEpochMillis(cooldown.CooldownUntil),
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

    private static long ToEpochMillis(DateTime value) =>
        new DateTimeOffset(DateTime.SpecifyKind(value, DateTimeKind.Utc)).ToUnixTimeMilliseconds();

    private static string? Normalize(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
}
