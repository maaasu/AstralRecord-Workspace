using System.Data;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class AccountGuideCompletionRewardRepository(
    AstralRecordDbContext dbContext,
    IGuideRepository guideRepository,
    IItemRepository itemRepository,
    IConfiguration configuration) : IAccountGuideCompletionRewardRepository
{
    private const string ConfigurationSection = "GuideCompletionReward";

    public async Task<AccountGuideCompletionRewardResponse> GetAsync(Guid accountId)
    {
        await EnsureAccountExistsAsync(accountId);
        var receipt = await dbContext.AccountGuideCompletionRewards.AsNoTracking()
            .SingleOrDefaultAsync(reward => reward.AccountId == accountId);
        var configured = TryGetRewards(out _);
        var completed = await AreAllStepsCompleteAsync(accountId, guideRepository.GetAll());
        return CreateResponse(accountId, configured, completed, receipt);
    }

    public async Task<AccountGuideCompletionRewardResponse> ClaimAsync(Guid accountId, Guid updatedBy)
    {
        if (updatedBy == Guid.Empty)
            throw new AccountGuideCompletionRewardUnavailableException("updatedBy is required.");

        // MasterDataDB and the game DB are separate. Read the current guide set before
        // locking the account; completion is checked again inside the game DB transaction.
        var guides = guideRepository.GetAll();
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            return await ClaimInTransactionAsync(accountId, updatedBy, guides);
        });
    }

    private async Task<AccountGuideCompletionRewardResponse> ClaimInTransactionAsync(
        Guid accountId, Guid updatedBy, IReadOnlyList<GuideResponse> guides)
    {
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        await EnsureAccountExistsAsync(accountId, lockForClaim: true);

        var receipt = await dbContext.AccountGuideCompletionRewards.AsNoTracking()
            .SingleOrDefaultAsync(reward => reward.AccountId == accountId);
        if (receipt is not null)
        {
            var currentCompletion = await AreAllStepsCompleteAsync(accountId, guides);
            return CreateResponse(accountId, TryGetRewards(out _), currentCompletion, receipt);
        }

        if (!TryGetRewards(out var rewards))
            throw new AccountGuideCompletionRewardUnavailableException("Guide completion reward is not configured.");
        if (!await AreAllStepsCompleteAsync(accountId, guides))
            throw new AccountGuideCompletionRewardUnavailableException("All guide steps must be complete.");

        var now = DateTime.UtcNow;
        var mailId = MailIdFor(accountId);
        var mail = new MailResponse
        {
            SchemaVersion = 1,
            Id = mailId,
            Icon = "WRITABLE_BOOK",
            Title = "全ガイド達成報酬",
            Body = "すべてのガイドを達成しました。添付された報酬を受け取ってください。",
            PublishFrom = now,
            ReceiveOnRead = true,
            Rewards = rewards,
        };
        dbContext.PlayerMailDeliveries.Add(new PlayerMailDeliveryEntity
        {
            PlayerMailDeliveryId = Guid.NewGuid(),
            AccountId = accountId,
            MailId = mailId,
            PayloadJson = JsonSerializer.Serialize(mail, MasterDataPayloadJson.Options),
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
        });
        var newReceipt = new AccountGuideCompletionRewardEntity
        {
            AccountId = accountId,
            MailId = mailId,
            ClaimedAt = now,
            ClaimedBy = updatedBy,
        };
        dbContext.AccountGuideCompletionRewards.Add(newReceipt);
        await dbContext.SaveChangesAsync();
        await transaction.CommitAsync();
        return CreateResponse(accountId, true, true, newReceipt);
    }

    private async Task EnsureAccountExistsAsync(Guid accountId, bool lockForClaim = false)
    {
        bool exists;
        if (lockForClaim && dbContext.Database.IsSqlServer())
        {
            // Serializes claims for the same account before either can observe a missing receipt.
            exists = await dbContext.Database.SqlQuery<Guid>($"SELECT [uuid] AS [Value] FROM [dbo].[account] WITH (UPDLOCK, HOLDLOCK) WHERE [uuid] = {accountId} AND [is_deleted] = 0")
                .AnyAsync();
        }
        else
        {
            exists = await dbContext.Accounts.AsNoTracking()
                .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);
        }
        if (!exists)
            throw new KeyNotFoundException($"Account not found: {accountId}");
    }

    private async Task<bool> AreAllStepsCompleteAsync(Guid accountId, IReadOnlyList<GuideResponse> guides)
    {
        if (guides.Count == 0 || guides.Any(guide => guide.Steps.Count == 0))
            return false;
        var completed = await dbContext.AccountGuideStepProgresses.AsNoTracking()
            .Where(progress => progress.AccountId == accountId)
            .Select(progress => new { progress.GuideId, progress.StepId })
            .ToListAsync();
        var completedKeys = completed.Select(progress => (progress.GuideId, progress.StepId)).ToHashSet();
        return guides.All(guide => guide.Steps.All(step => completedKeys.Contains((guide.Id, step.Id))));
    }

    private bool TryGetRewards(out IReadOnlyList<MailRewardResponse> rewards)
    {
        var configured = configuration.GetSection(ConfigurationSection)
            .Get<AccountGuideCompletionRewardOptions>()?.Rewards;
        rewards = configured ?? [];
        if (rewards.Count == 0 || !rewards.All(reward =>
            !string.IsNullOrWhiteSpace(reward.ItemId)
            && !string.IsNullOrWhiteSpace(reward.Category)
            && reward.Amount > 0
            && reward.InstanceId is null))
            return false;

        var activeItems = itemRepository.GetAllSummaries()
            .ToDictionary(item => item.Id, StringComparer.OrdinalIgnoreCase);
        return rewards.All(reward =>
            // Built-in Gold denominations have no YAML/master_data_entry row.
            (string.Equals(reward.Category, "currency", StringComparison.OrdinalIgnoreCase)
                && GoldCurrencyBalanceSupport.IsCanonicalGoldItem(reward.ItemId))
            || (activeItems.TryGetValue(reward.ItemId, out var item)
            && string.Equals(item.Category, reward.Category, StringComparison.OrdinalIgnoreCase)
            // MailService requires an existing owned instance for pets and eggs.
            // This configurable reward path cannot create one before delivery.
            && !string.Equals(item.Category, "pet", StringComparison.OrdinalIgnoreCase)
            && !string.Equals(item.Category, "pet_egg", StringComparison.OrdinalIgnoreCase)));
    }

    private static AccountGuideCompletionRewardResponse CreateResponse(
        Guid accountId, bool configured, bool allCompleted, AccountGuideCompletionRewardEntity? receipt) =>
        new(accountId, configured, allCompleted, receipt is not null,
            configured && allCompleted && receipt is null, receipt?.MailId);

    private static string MailIdFor(Guid accountId) => $"guide-completion-{accountId:N}";
}
