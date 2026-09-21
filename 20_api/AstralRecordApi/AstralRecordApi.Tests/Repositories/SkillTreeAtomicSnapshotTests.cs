using System.Text.Json;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class SkillTreeAtomicSnapshotTests
{
    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task GoldOnlySnapshotIsFencedByItsAccountSession(bool withAuthority, bool closed)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var before = DateTime.UtcNow.AddMinutes(-1);
        var inventoryId = Guid.NewGuid(); var entryId = Guid.NewGuid();
        f.Db.Inventories.Add(new() { InventoryId = inventoryId, AccountId = f.Account, InventoryType = "CURRENCY", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = before, UpdatedAt = before, CreatedBy = f.User, UpdatedBy = f.User });
        f.Db.InventoryEntries.Add(new() { InventoryEntryId = entryId, InventoryId = inventoryId, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 1000, CreatedAt = before, UpdatedAt = before, CreatedBy = f.User, UpdatedBy = f.User });
        if (closed) (await f.Db.SkillTreeAccountSessions.SingleAsync()).Closed = true;
        await f.Db.SaveChangesAsync();
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = f.Account, UpdatedBy = f.Account,
            RuntimeAuthority = withAuthority ? JsonSerializer.SerializeToElement(new {
                serverId = f.Server, serverSessionId = f.Boot, accountSessionId = f.Session,
                accountLeaseToken = f.Token, definitionGenerationId = f.Generation }) : null,
            Inventories = [new PlayerStateInventorySnapshot {
                InventoryId = inventoryId, EntryMode = "DELTA",
                ExpectedEntries = [new() { InventoryEntryId = entryId, UpdatedAt = before }],
                Entries = [new() { InventoryEntryId = entryId, ExpectedUpdatedAt = before, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 900 }],
            }],
        };
        var result = await new PlayerStateSnapshotRepository(f.Db, f.Repository).SaveAsync(request);
        var allowed = withAuthority && !closed;
        Assert.Equal(allowed, result.Succeeded);
        f.Db.ChangeTracker.Clear();
        Assert.Equal(allowed ? 900 : 1000, (await f.Db.InventoryEntries.SingleAsync()).Quantity);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RelockCommitsGoldNodesVersionAndReceiptTogether_OrRollsEverythingBack(bool expiredLease)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var before = DateTime.UtcNow.AddMinutes(-1);
        var inventoryId = Guid.NewGuid(); var entryId = Guid.NewGuid();
        f.Db.Inventories.Add(new() { InventoryId = inventoryId, AccountId = f.Account, InventoryType = "CURRENCY", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = before, UpdatedAt = before, CreatedBy = f.User, UpdatedBy = f.User });
        f.Db.InventoryEntries.Add(new() { InventoryEntryId = entryId, InventoryId = inventoryId, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 1000, CreatedAt = before, UpdatedAt = before, CreatedBy = f.User, UpdatedBy = f.User });
        var state = await f.Db.AccountSkillTreeStates.SingleAsync();
        f.Db.AccountSkillTreeUnlockedNodes.Add(new() { AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = state.AccountSkillTreeStateId, NodeId = "gain", CreatedAt = before, UpdatedAt = before, CreatedBy = f.User, UpdatedBy = f.User });
        await f.Db.SaveChangesAsync();
        var operation = await f.Repository.CreateAsync(f.Account, new SkillTreeOperationCreateRequest
        {
            OperationId = Guid.NewGuid(), ActorUserId = f.User, TargetServerId = f.Server, ExpectedDefinitionGenerationId = f.Generation,
            ExpectedPlayerStateVersion = 1, Action = "RELOCK", NodeId = "gain",
        });
        Assert.NotNull(operation);
        var claim = await f.Repository.ClaimAsync(f.Server, operation.OperationId, f.Claim());
        Assert.NotNull(claim);
        if (expiredLease)
        {
            (await f.Db.SkillTreeOperations.SingleAsync()).LeaseExpiresAtUtc = DateTime.UtcNow.AddSeconds(-1);
            await f.Db.SaveChangesAsync();
        }
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = f.Account, UpdatedBy = f.Account,
            Inventories = [new PlayerStateInventorySnapshot
            {
                InventoryId = inventoryId, EntryMode = "DELTA",
                ExpectedEntries = [new PlayerStateExpectedInventoryEntry { InventoryEntryId = entryId, UpdatedAt = before }],
                Entries = [new PlayerStateInventoryEntrySnapshot { InventoryEntryId = entryId, ExpectedUpdatedAt = before, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 900 }],
            }],
            SkillTree = JsonSerializer.SerializeToElement(new PlayerStateSkillTreeSection
            {
                AccountId = f.Account, ClientRevision = 2, ExpectedVersion = 1, TargetVersion = 2,
                DefinitionGenerationId = f.Generation, ServerId = f.Server, ServerSessionId = f.Boot,
                AccountSessionId = f.Session, AccountLeaseToken = f.Token,
                UnlockedNodes = [new() { NodeId = "root" }], Operation = f.Receipt(operation.OperationId, claim.LeaseToken),
            }, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        };
        var snapshots = new PlayerStateSnapshotRepository(f.Db, f.Repository);
        var result = await snapshots.SaveAsync(request);
        Assert.Equal(!expiredLease, result.Succeeded);
        f.Db.ChangeTracker.Clear();
        Assert.Equal(expiredLease ? 1000 : 900, (await f.Db.InventoryEntries.SingleAsync()).Quantity);
        Assert.Equal(expiredLease ? 1 : 2, (await f.Db.AccountSkillTreeStates.SingleAsync()).Version);
        Assert.Equal(expiredLease, await f.Db.AccountSkillTreeUnlockedNodes.AnyAsync(x => x.NodeId == "gain"));
        var savedOperation = await f.Db.SkillTreeOperations.SingleAsync();
        Assert.Equal(expiredLease ? SkillTreeOperationStatuses.Claimed : SkillTreeOperationStatuses.Applied, savedOperation.Status);
        if (expiredLease)
        {
            Assert.Null(await snapshots.FindCompletedAsync(request.SnapshotId, f.Account));
        }
        else
        {
            Assert.Equal("APPLIED", result.Ack!.SkillTree!.Value.GetProperty("operation").GetProperty("status").GetString());
            var retry = await snapshots.SaveAsync(request);
            Assert.True(retry.Succeeded, retry.Detail);
            Assert.Equal(result.Ack.SnapshotId, retry.Ack!.SnapshotId);
            f.Db.ChangeTracker.Clear();
            Assert.Equal(900, (await f.Db.InventoryEntries.SingleAsync()).Quantity);
            Assert.Equal(2, (await f.Db.AccountSkillTreeStates.SingleAsync()).Version);
        }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task NormalGameSaveRequiresAccountAuthority_ButNotAWebOperation(bool withAuthority)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var request = new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = f.Account, UpdatedBy = f.Account,
            SkillTree = JsonSerializer.SerializeToElement(new PlayerStateSkillTreeSection
            {
                AccountId = f.Account, ClientRevision = 2, ExpectedVersion = 1, TargetVersion = 2,
                DefinitionGenerationId = withAuthority ? f.Generation : null,
                ServerId = withAuthority ? f.Server : null, ServerSessionId = withAuthority ? f.Boot : null,
                AccountSessionId = withAuthority ? f.Session : null, AccountLeaseToken = withAuthority ? f.Token : null,
                UnlockedNodes = [new() { NodeId = "root" }, new() { NodeId = "gain" }],
            }, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        };
        var result = await new PlayerStateSnapshotRepository(f.Db, f.Repository).SaveAsync(request);
        Assert.Equal(withAuthority, result.Succeeded);
        f.Db.ChangeTracker.Clear();
        Assert.Equal(withAuthority ? 2 : 1, (await f.Db.AccountSkillTreeStates.SingleAsync()).Version);
        Assert.Empty(await f.Db.SkillTreeOperations.ToListAsync());
    }
}
