using System.Text.Json;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.EntityFrameworkCore;
using Xunit;
namespace AstralRecordApi.Tests.Repositories;
public sealed class SkillTreeBatchTests
{
    private static SkillTreeOperationCreateRequest Request(SkillTreeOperationRepositoryTests.Fixture f, Guid? id = null, IReadOnlyList<SkillTreeOperationChange>? changes = null) => new() {
        OperationId = id ?? Guid.NewGuid(), ActorUserId = f.User, TargetServerId = f.Server,
        ExpectedDefinitionGenerationId = f.Generation, ExpectedPlayerStateVersion = 1,
        Action = "BATCH", NodeId = "batch", Changes = changes ?? [new() { Action = "RELOCK", NodeId = "gain" }, new() { Action = "RELOCK", NodeId = "root" }],
    };
    private static async Task Upgrade(SkillTreeOperationRepositoryTests.Fixture f)
    {
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, new() {
            ServerSessionId = f.Boot, ServerStartedAtUtc = f.Started, PublicationRevision = 1,
            DefinitionGenerationId = f.Generation, CanonicalSnapshotJson = f.Canonical,
            PluginVersion = "batch-test", CompatibilityVersion = "skilltree-operation-v2", Ready = true,
        }));
    }
    [Fact]
    public async Task BatchRequiresV2AndPreservesOrderedPayloadAcrossReplayAndClaim()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var request = Request(f);
        Assert.False((await f.Repository.GetEditorAsync(f.Account, f.User))!.SupportsBatch);
        Assert.Null(await f.Repository.CreateAsync(f.Account, request));
        await Upgrade(f);
        Assert.True((await f.Repository.GetEditorAsync(f.Account, f.User))!.SupportsBatch);
        var created = await f.Repository.CreateAsync(f.Account, request);
        Assert.NotNull(created); Assert.Equal(new[] { "gain", "root" }, created.Changes!.Select(c => c.NodeId));
        Assert.Equal(created.OperationId, (await f.Repository.CreateAsync(f.Account, request))!.OperationId);
        Assert.Null(await f.Repository.CreateAsync(f.Account, Request(f, request.OperationId, [new() { Action = "RELOCK", NodeId = "root" }])));
        var claim = await f.Repository.ClaimAsync(f.Server, created.OperationId, f.Claim());
        Assert.NotNull(claim); Assert.Equal(2, claim.Operation.Changes!.Count);
        Assert.Single(await f.Db.SkillTreeOperations.ToListAsync());
    }
    [Fact]
    public async Task BatchRejectsDuplicatesNullEntriesEmptyAndExcessiveChanges()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync(); await Upgrade(f);
        foreach (var changes in new IReadOnlyList<SkillTreeOperationChange>[] { [], [null!],
            [new() { Action = "UNLOCK", NodeId = "gain" }, new() { Action = "RELOCK", NodeId = "GAIN" }],
            Enumerable.Range(0, 513).Select(i => new SkillTreeOperationChange { Action = "UNLOCK", NodeId = "node" + i }).ToArray() })
            Assert.Null(await f.Repository.CreateAsync(f.Account, Request(f, changes: changes)));
        Assert.Empty(await f.Db.SkillTreeOperations.ToListAsync());
    }
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task OneReceiptCommitsBothRelocksAndTotalGoldOrRollsBack(bool expired)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync(); await Upgrade(f);
        var before = DateTime.UtcNow.AddMinutes(-1); var inventory = Guid.NewGuid(); var entry = Guid.NewGuid();
        f.Db.Inventories.Add(new() { InventoryId = inventory, AccountId = f.Account, InventoryType = "CURRENCY", InventoryProfile = "GAME", IsEnabled = true, CreatedAt = before, UpdatedAt = before, CreatedBy = f.User, UpdatedBy = f.User });
        f.Db.InventoryEntries.Add(new() { InventoryEntryId = entry, InventoryId = inventory, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 1000, CreatedAt = before, UpdatedAt = before, CreatedBy = f.User, UpdatedBy = f.User });
        var state = await f.Db.AccountSkillTreeStates.SingleAsync();
        f.Db.AccountSkillTreeUnlockedNodes.Add(new() { AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = state.AccountSkillTreeStateId, NodeId = "gain", CreatedAt = before, UpdatedAt = before, CreatedBy = f.User, UpdatedBy = f.User });
        await f.Db.SaveChangesAsync();
        var operation = await f.Repository.CreateAsync(f.Account, Request(f)); Assert.NotNull(operation);
        var claim = await f.Repository.ClaimAsync(f.Server, operation.OperationId, f.Claim()); Assert.NotNull(claim);
        if (expired) { (await f.Db.SkillTreeOperations.SingleAsync()).LeaseExpiresAtUtc = DateTime.UtcNow.AddSeconds(-1); await f.Db.SaveChangesAsync(); }
        var request = new PlayerStateSnapshotSaveRequest {
            SnapshotId = Guid.NewGuid(), AccountId = f.Account, UpdatedBy = f.Account,
            Inventories = [new() { InventoryId = inventory, EntryMode = "DELTA", ExpectedEntries = [new() { InventoryEntryId = entry, UpdatedAt = before }], Entries = [new() { InventoryEntryId = entry, ExpectedUpdatedAt = before, ItemCategory = "CURRENCY", ItemId = "gold", Quantity = 800 }] }],
            SkillTree = JsonSerializer.SerializeToElement(new PlayerStateSkillTreeSection {
                AccountId = f.Account, ClientRevision = 1, ExpectedVersion = 1, TargetVersion = 2,
                DefinitionGenerationId = f.Generation, ServerId = f.Server, ServerSessionId = f.Boot, AccountSessionId = f.Session, AccountLeaseToken = f.Token,
                UnlockedNodes = [], Operation = f.Receipt(operation.OperationId, claim.LeaseToken),
            }, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        };
        var snapshots = new PlayerStateSnapshotRepository(f.Db, f.Repository);
        var result = await snapshots.SaveAsync(request); Assert.Equal(!expired, result.Succeeded);
        f.Db.ChangeTracker.Clear();
        Assert.Equal(expired ? 1000 : 800, (await f.Db.InventoryEntries.SingleAsync()).Quantity);
        Assert.Equal(expired ? 2 : 0, await f.Db.AccountSkillTreeUnlockedNodes.CountAsync());
        Assert.Equal(expired ? 1 : 2, (await f.Db.AccountSkillTreeStates.SingleAsync()).Version);
        if (!expired) { Assert.True((await snapshots.SaveAsync(request)).Succeeded); Assert.Equal(800, (await f.Db.InventoryEntries.AsNoTracking().SingleAsync()).Quantity); }
    }
}
