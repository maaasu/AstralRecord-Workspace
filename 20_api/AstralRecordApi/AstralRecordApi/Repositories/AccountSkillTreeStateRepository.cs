using System.Data;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public class AccountSkillTreeStateRepository(AstralRecordDbContext dbContext) : IAccountSkillTreeStateRepository
{
    private async Task LockAccountAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
            await dbContext.Accounts.FromSqlInterpolated($"SELECT * FROM [dbo].[account] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid] = {accountId}")
                .SingleOrDefaultAsync();
    }

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

    public Task<AccountSkillTreeStateResponse> UpsertAsync(Guid accountId, AccountSkillTreeStateUpsertRequest request) =>
        dbContext.Database.CreateExecutionStrategy().ExecuteAsync(() => UpsertLegacyAsync(accountId, request));

    private async Task<AccountSkillTreeStateResponse> UpsertLegacyAsync(Guid accountId, AccountSkillTreeStateUpsertRequest request)
    {
        dbContext.ChangeTracker.Clear();
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        await LockAccountAsync(accountId);
        var accountExists = await dbContext.Accounts
            .AnyAsync(account => account.Uuid == accountId && !account.IsDeleted);

        if (!accountExists)
            throw new KeyNotFoundException($"Account not found: {accountId}");

        var now = DateTime.UtcNow;
        var normalizedNodes = NormalizeUnlockedNodes(request.UnlockedNodes);
        var current = await dbContext.AccountSkillTreeStates.AsNoTracking()
            .SingleOrDefaultAsync(state => state.AccountId == accountId && !state.IsDeleted);
        if (current?.DefinitionGenerationId is not null || await SkillTreeSessionReads.Query(dbContext).AnyAsync(x => x.AccountId == accountId))
            throw new InvalidOperationException("Generation-bound skill tree state must be changed through the Plugin operation flow.");
        await ReplaceUnlockedNodesAsync(accountId, normalizedNodes, request.UpdatedBy, now);

        await dbContext.SaveChangesAsync();
        await transaction.CommitAsync();
        return await GetByAccountIdAsync(accountId);
    }

    public async Task<AccountSkillTreeStateResponse> RepairInvalidStateAsync(
        Guid accountId,
        AccountSkillTreeInvalidStateRepairRequest request)
    {
        ValidateRepairRequest(request);
        var now = DateTime.UtcNow;
        var strategy = dbContext.Database.CreateExecutionStrategy();
        await strategy.ExecuteAsync(async () =>
        {
            // EnableRetryOnFailure が有効なため、ユーザー開始トランザクションは
            // 実行戦略の再実行範囲内で開始する。
            dbContext.ChangeTracker.Clear();
            await using var transaction = await dbContext.Database.BeginTransactionAsync(
                IsolationLevel.Serializable);
            await LockAccountAsync(accountId);
            var account = await dbContext.Accounts.AsNoTracking()
                .SingleOrDefaultAsync(candidate => candidate.Uuid == accountId && !candidate.IsDeleted);
            if (account is null)
                throw new KeyNotFoundException($"Account not found: {accountId}");
            if (account.UserId != request.UserId)
                throw new InvalidOperationException($"User does not own account: {accountId}");

            var state = await dbContext.AccountSkillTreeStates.AsNoTracking()
                .SingleOrDefaultAsync(candidate => candidate.AccountId == accountId && !candidate.IsDeleted);
            var currentVersion = state?.Version ?? 0;
            var currentGeneration = state?.DefinitionGenerationId;
            if (currentGeneration is null && await SkillTreeSessionReads.Query(dbContext).AnyAsync(x => x.AccountId == accountId))
                throw new DbUpdateConcurrencyException("Legacy enrolled state requires explicit migration, not automatic repair.");
            if (currentGeneration is not null)
            {
                var owner = await SkillTreeSessionReads.Query(dbContext).SingleOrDefaultAsync(session => session.AccountId == accountId
                    && session.AccountSessionId == request.AccountSessionId && session.ServerId == request.ServerId
                    && session.ServerSessionId == request.ServerSessionId && !session.Closed && session.ExpiresAtUtc > DateTime.UtcNow);
                var runtime = await dbContext.SkillTreeServerRuntimes.SingleOrDefaultAsync(server => server.ServerId == request.ServerId
                    && server.ServerSessionId == request.ServerSessionId && server.Ready);
                var tokenHash = string.IsNullOrEmpty(request.AccountLeaseToken) ? "" : Convert.ToHexString(
                    System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(request.AccountLeaseToken))).ToLowerInvariant();
                if (owner is null || runtime is null || owner.DefinitionGenerationId != currentGeneration || owner.LeaseTokenHash != tokenHash)
                    throw new DbUpdateConcurrencyException("Skill tree repair session is no longer authoritative.");
            }
            if (string.IsNullOrWhiteSpace(request.ExpectedDefinitionGenerationId)
                ? currentGeneration is not null
                : !string.Equals(currentGeneration, request.ExpectedDefinitionGenerationId, StringComparison.Ordinal))
                throw new DbUpdateConcurrencyException("Skill tree definition generation differs; state is held without repair.");

            if (currentVersion == request.ExpectedVersion)
            {
                await ReplaceUnlockedNodesAsync(accountId, [], request.UpdatedBy, now);
                await dbContext.SaveChangesAsync();
            }
            // 直前の補修の結果と一致する空状態だけを、書き込まずに返す。
            else if (state is null || currentVersion != (long)request.ExpectedVersion + 1
                || await dbContext.AccountSkillTreeUnlockedNodes.AsNoTracking()
                    .AnyAsync(node => node.AccountSkillTreeStateId == state.AccountSkillTreeStateId))
            {
                throw new DbUpdateConcurrencyException(
                    $"Account skill tree state version conflict: expected={request.ExpectedVersion}, current={currentVersion}");
            }
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
        DefinitionGenerationId = entity.DefinitionGenerationId,
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

    private static void ValidateRepairRequest(AccountSkillTreeInvalidStateRepairRequest request)
    {
        if (request.UserId == Guid.Empty || request.UpdatedBy == Guid.Empty)
            throw new ArgumentException("userId and updatedBy are required");
        if (request.ExpectedVersion < 0)
            throw new ArgumentException("expectedVersion must not be negative");
        if (!string.IsNullOrEmpty(request.ExpectedDefinitionGenerationId)
            && (request.ExpectedDefinitionGenerationId.Length != 64
                || !request.ExpectedDefinitionGenerationId.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f')))
            throw new ArgumentException("expectedDefinitionGenerationId must be a lowercase SHA-256 hash");
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
