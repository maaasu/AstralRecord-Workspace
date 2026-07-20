using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class AccountGuideProgressRepository(AstralRecordDbContext dbContext) : IAccountGuideProgressRepository
{
    public async Task<AccountGuideProgressResponse> GetByAccountIdAsync(Guid accountId)
    {
        await EnsureAccountExistsAsync(accountId);
        var completedSteps = await dbContext.AccountGuideStepProgresses
            .AsNoTracking()
            .Where(progress => progress.AccountId == accountId)
            .OrderBy(progress => progress.CompletedAt)
            .ThenBy(progress => progress.GuideId)
            .ThenBy(progress => progress.StepId)
            .Select(progress => Map(progress))
            .ToListAsync();

        return new AccountGuideProgressResponse
        {
            AccountId = accountId,
            CompletedSteps = completedSteps,
        };
    }

    public async Task<AccountGuideStepProgressResponse> CompleteStepAsync(
        Guid accountId,
        AccountGuideStepCompleteRequest request)
    {
        await EnsureAccountExistsAsync(accountId);
        var guideId = NormalizeId(request.GuideId, nameof(request.GuideId));
        var stepId = NormalizeId(request.StepId, nameof(request.StepId));

        var existing = await dbContext.AccountGuideStepProgresses
            .AsNoTracking()
            .FirstOrDefaultAsync(progress => progress.AccountId == accountId
                && progress.GuideId == guideId
                && progress.StepId == stepId);
        if (existing is not null)
            return Map(existing);

        var now = DateTime.UtcNow;
        var entity = new AccountGuideStepProgressEntity
        {
            AccountGuideStepProgressId = Guid.NewGuid(),
            AccountId = accountId,
            GuideId = guideId,
            StepId = stepId,
            CompletedAt = now,
            CreatedAt = now,
            CreatedBy = request.UpdatedBy,
        };
        await dbContext.AccountGuideStepProgresses.AddAsync(entity);
        await dbContext.SaveChangesAsync();
        return Map(entity);
    }

    private async Task EnsureAccountExistsAsync(Guid accountId)
    {
        var exists = await dbContext.Accounts
            .AsNoTracking()
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        if (!exists)
            throw new KeyNotFoundException($"Account not found: {accountId}");
    }

    private static string NormalizeId(string value, string parameterName)
    {
        var normalized = value.Trim();
        if (normalized.Length == 0)
            throw new ArgumentException("ID is required.", parameterName);
        if (normalized.Length > 100)
            throw new ArgumentException("ID must be 100 characters or fewer.", parameterName);
        return normalized;
    }

    private static AccountGuideStepProgressResponse Map(AccountGuideStepProgressEntity entity) => new()
    {
        AccountGuideStepProgressId = entity.AccountGuideStepProgressId,
        AccountId = entity.AccountId,
        GuideId = entity.GuideId,
        StepId = entity.StepId,
        CompletedAt = entity.CompletedAt,
        CreatedAt = entity.CreatedAt,
        CreatedBy = entity.CreatedBy,
    };
}
