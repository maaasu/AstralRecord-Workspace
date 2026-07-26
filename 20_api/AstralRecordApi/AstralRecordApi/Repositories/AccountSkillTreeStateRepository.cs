using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class AccountSkillTreeStateRepository(AstralRecordDbContext dbContext) : IAccountSkillTreeStateRepository
{
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
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
                IsDeleted = false,
            };

            await dbContext.AccountSkillTreeStates.AddAsync(entity);
            await AddUnlockedNodesAsync(entity.AccountSkillTreeStateId, normalizedNodes, now, request.UpdatedBy);
        }
        else
        {
            entity.Version = Math.Max(1, entity.Version + 1);
            entity.UpdatedAt = now;
            entity.UpdatedBy = request.UpdatedBy;

            var existingNodes = await dbContext.AccountSkillTreeUnlockedNodes
                .Where(node => node.AccountSkillTreeStateId == entity.AccountSkillTreeStateId)
                .ToListAsync();
            dbContext.AccountSkillTreeUnlockedNodes.RemoveRange(existingNodes);
            await AddUnlockedNodesAsync(entity.AccountSkillTreeStateId, normalizedNodes, now, request.UpdatedBy);
        }

        await dbContext.SaveChangesAsync();
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
