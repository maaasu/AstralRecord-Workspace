using System.Data;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;

namespace AstralRecordApi.Repositories;

public class AccountSkillTreeStateRepository(
    AstralRecordDbContext dbContext,
    MasterDataDbContext masterDataDbContext) : IAccountSkillTreeStateRepository
{
    private const string CompensationMailMasterId = "skilltree_structure_reset_compensation";
    private const string CompensationMailIdPrefix = "skilltree-structure-reset-";
    private const string MasterTypeMail = "mail";

    public async Task<AccountSkillTreeStateResponse> GetByAccountIdAsync(Guid accountId)
    {
        var accountExists = await dbContext.Accounts
            .AsNoTracking()
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);

        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");

        var entity = await dbContext.AccountSkillTreeStates
            .AsNoTracking()
            .Include(state => state.UnlockedNodes)
            .FirstOrDefaultAsync(state => state.AccountId == accountId && !state.IsDeleted);

        return entity is null
            ? CreateUnsavedResponse(accountId)
            : Map(entity);
    }

    public async Task<AccountSkillTreeStateResponse> UpsertAsync(Guid accountId, AccountSkillTreeStateUpsertRequest request)
    {
        var accountExists = await dbContext.Accounts
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);

        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");

        var now = DateTime.UtcNow;
        var normalizedNodes = NormalizeUnlockedNodes(request.UnlockedNodes);
        await ReplaceUnlockedNodesAsync(accountId, normalizedNodes, request.UpdatedBy, now);

        await dbContext.SaveChangesAsync();
        return await GetByAccountIdAsync(accountId);
    }

    public async Task<AccountSkillTreeStateResponse> RepairInvalidStateAsync(
        Guid accountId,
        AccountSkillTreeInvalidStateRepairRequest request)
    {
        ValidateRepairRequest(request);
        var account = await dbContext.Accounts
            .AsNoTracking()
            .SingleOrDefaultAsync(candidate => candidate.Uuid == accountId && !candidate.IsDeleted);
        if (account is null)
            throw new KeyNotFoundException($"Account not found: {accountId}");
        if (account.UserId != request.UserId)
            throw new InvalidOperationException($"User does not own account: {accountId}");

        var deliveryMailId = CompensationMailIdPrefix + request.RepairKey;
        var deliveryExists = await DeliveryExistsAsync(request.UserId, deliveryMailId);
        if (deliveryExists && !await HasUnlockedNodesAsync(accountId))
            return await GetByAccountIdAsync(accountId);

        var master = deliveryExists ? null : await GetCompensationMailMasterAsync();
        if (!deliveryExists && master is null)
            throw new KeyNotFoundException($"Mail master not found: {CompensationMailMasterId}");

        var now = DateTime.UtcNow;
        var strategy = dbContext.Database.CreateExecutionStrategy();
        await strategy.ExecuteAsync(async () =>
        {
            // EnableRetryOnFailure が有効なため、ユーザー開始トランザクションは
            // 実行戦略の再実行範囲内で開始する。
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable);
            var deliveryExistsInTransaction = await DeliveryExistsForRepairAsync(request.UserId, deliveryMailId);
            var hasUnlockedNodes = await HasUnlockedNodesAsync(accountId);
            if (deliveryExistsInTransaction && !hasUnlockedNodes)
            {
                await transaction.CommitAsync();
                return;
            }

            if (hasUnlockedNodes || !deliveryExistsInTransaction)
            {
                await ReplaceUnlockedNodesAsync(accountId, [], request.UpdatedBy, now);
            }
            if (!deliveryExistsInTransaction)
            {
                var deliveryMail = CreateCompensationMail(master!, deliveryMailId, now);
                await dbContext.PlayerMailDeliveries.AddAsync(new PlayerMailDeliveryEntity
                {
                    PlayerMailDeliveryId = Guid.NewGuid(),
                    UserId = request.UserId,
                    MailId = deliveryMailId,
                    PayloadJson = JsonSerializer.Serialize(deliveryMail, MasterDataPayloadJson.Options),
                    Version = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = request.UpdatedBy,
                    UpdatedBy = request.UpdatedBy,
                    IsDeleted = false,
                });
            }
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
        });
        return await GetByAccountIdAsync(accountId);
    }

    private static AccountSkillTreeStateResponse CreateUnsavedResponse(Guid accountId) => new()
    {
        AccountSkillTreeStateId = null,
        AccountId = accountId,
        UnlockedNodes = [],
        IsSaved = false,
        Version = 0,
        CreatedAt = null,
        UpdatedAt = null,
        CreatedBy = null,
        UpdatedBy = null,
    };

    private static AccountSkillTreeStateResponse Map(AccountSkillTreeStateEntity entity) => new()
    {
        AccountSkillTreeStateId = entity.AccountSkillTreeStateId,
        AccountId = entity.AccountId,
        UnlockedNodes = entity.UnlockedNodes
            .OrderBy(node => node.NodeId, StringComparer.Ordinal)
            .Select(node => new AccountSkillTreeUnlockedNodeModel
            {
                NodeId = node.NodeId,
                ConsumedClassId = node.ConsumedClassId,
            })
            .ToList(),
        IsSaved = true,
        Version = entity.Version,
        CreatedAt = entity.CreatedAt,
        UpdatedAt = entity.UpdatedAt,
        CreatedBy = entity.CreatedBy,
        UpdatedBy = entity.UpdatedBy,
    };

    private static List<AccountSkillTreeUnlockedNodeModel> NormalizeUnlockedNodes(
        IReadOnlyList<AccountSkillTreeUnlockedNodeModel> rawNodes)
    {
        return rawNodes
            .Where(node => node is not null && !string.IsNullOrWhiteSpace(node.NodeId))
            .Select(node => new AccountSkillTreeUnlockedNodeModel
            {
                NodeId = node.NodeId.Trim(),
                ConsumedClassId = string.IsNullOrWhiteSpace(node.ConsumedClassId)
                    ? null
                    : node.ConsumedClassId.Trim().ToLowerInvariant(),
            })
            .GroupBy(node => node.NodeId, StringComparer.Ordinal)
            .Select(group => group.First())
            .OrderBy(node => node.NodeId, StringComparer.Ordinal)
            .ToList();
    }

    private async Task ReplaceUnlockedNodesAsync(
        Guid accountId,
        IReadOnlyList<AccountSkillTreeUnlockedNodeModel> normalizedNodes,
        Guid updatedBy,
        DateTime now)
    {
        var entity = await dbContext.AccountSkillTreeStates
            .FirstOrDefaultAsync(state => state.AccountId == accountId && !state.IsDeleted);

        if (entity is null)
        {
            entity = new AccountSkillTreeStateEntity
            {
                AccountSkillTreeStateId = Guid.NewGuid(),
                AccountId = accountId,
                Version = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
                IsDeleted = false,
            };

            await dbContext.AccountSkillTreeStates.AddAsync(entity);
            await AddUnlockedNodesAsync(entity.AccountSkillTreeStateId, normalizedNodes, now, updatedBy);
            return;
        }

        entity.Version = Math.Max(1, entity.Version + 1);
        entity.UpdatedAt = now;
        entity.UpdatedBy = updatedBy;

        var existingNodes = await dbContext.AccountSkillTreeUnlockedNodes
            .Where(node => node.AccountSkillTreeStateId == entity.AccountSkillTreeStateId)
            .ToListAsync();
        dbContext.AccountSkillTreeUnlockedNodes.RemoveRange(existingNodes);
        await AddUnlockedNodesAsync(entity.AccountSkillTreeStateId, normalizedNodes, now, updatedBy);
    }

    private async Task<bool> DeliveryExistsAsync(Guid userId, string mailId)
        => await dbContext.PlayerMailDeliveries
            .AsNoTracking()
            .AnyAsync(delivery => delivery.UserId == userId && delivery.MailId == mailId);

    private async Task<bool> DeliveryExistsForRepairAsync(Guid userId, string mailId)
    {
        if (!dbContext.Database.IsSqlServer())
            return await DeliveryExistsAsync(userId, mailId);

        // 未登録の配信キーにも範囲ロックを取得し、同一repairKeyの補修を直列化する。
        return await dbContext.PlayerMailDeliveries
            .FromSqlInterpolated($"""
                SELECT TOP (1) *
                FROM dbo.player_mail_delivery WITH (UPDLOCK, HOLDLOCK)
                WHERE user_id = {userId} AND mail_id = {mailId}
                """)
            .AsNoTracking()
            .AnyAsync();
    }

    private async Task<bool> HasUnlockedNodesAsync(Guid accountId)
    {
        var stateId = await dbContext.AccountSkillTreeStates
            .AsNoTracking()
            .Where(state => state.AccountId == accountId && !state.IsDeleted)
            .Select(state => (Guid?)state.AccountSkillTreeStateId)
            .FirstOrDefaultAsync();
        return stateId is not null
            && await dbContext.AccountSkillTreeUnlockedNodes
                .AsNoTracking()
                .AnyAsync(node => node.AccountSkillTreeStateId == stateId.Value);
    }

    private async Task<MailResponse?> GetCompensationMailMasterAsync()
    {
        var payload = await masterDataDbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterTypeMail
                && entry.MasterId == CompensationMailMasterId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefaultAsync();
        return payload is null ? null : MasterDataPayloadJson.Deserialize<MailResponse>(payload);
    }

    private static MailResponse CreateCompensationMail(MailResponse master, string deliveryMailId, DateTime now) => new()
    {
        SchemaVersion = master.SchemaVersion,
        Id = deliveryMailId,
        Icon = master.Icon,
        Title = master.Title,
        Body = master.Body,
        PublishFrom = now,
        PublishTo = null,
        FirstLoginOnly = false,
        ReceiveOnRead = master.ReceiveOnRead,
        Rewards = master.Rewards,
    };

    private static void ValidateRepairRequest(AccountSkillTreeInvalidStateRepairRequest request)
    {
        if (request.UserId == Guid.Empty || request.UpdatedBy == Guid.Empty)
            throw new ArgumentException("userId and updatedBy are required");
        if (request.RepairKey.Length != 64
            || !request.RepairKey.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f'))
            throw new ArgumentException("repairKey must be a lowercase SHA-256 hash");
    }

    private async Task AddUnlockedNodesAsync(
        Guid stateId,
        IReadOnlyList<AccountSkillTreeUnlockedNodeModel> nodes,
        DateTime now,
        Guid updatedBy)
    {
        var entities = nodes.Select(node => new AccountSkillTreeUnlockedNodeEntity
        {
            AccountSkillTreeUnlockedNodeId = Guid.NewGuid(),
            AccountSkillTreeStateId = stateId,
            NodeId = node.NodeId,
            ConsumedClassId = node.ConsumedClassId,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
        });
        await dbContext.AccountSkillTreeUnlockedNodes.AddRangeAsync(entities);
    }
}
