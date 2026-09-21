using System.Text.Json;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class SkillTreeMigrationTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task RetirementIsPreviewedThenAppliedAndReplayedWithoutExtraVersion(bool legacy)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        await f.CloseAsync();
        var state = await f.Db.AccountSkillTreeStates.SingleAsync();
        if (legacy) state.DefinitionGenerationId = null;
        f.Db.AccountSkillTreeUnlockedNodes.Add(new() { AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = state.AccountSkillTreeStateId, NodeId = "gain", CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = f.User, UpdatedBy = f.User });
        await f.Db.SaveChangesAsync();
        var id = Guid.NewGuid();
        SkillTreeMigrationRequest Request(bool preview, bool confirm) => new() {
            OperationId = id, ExpectedStateVersion = 1, FromGenerationId = legacy ? null : f.Generation,
            ToGenerationId = f.Generation, LegacyBaselineNodeIds = ["root", "gain"], RemoveNodeIds = ["gain"],
            PreviewOnly = preview, ConfirmLegacyBaseline = confirm,
        };
        if (legacy) Assert.Null(await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, Request(true, false)));
        var preview = await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, Request(true, true));
        Assert.NotNull(preview); Assert.Equal("PREVIEW", preview.Status);
        Assert.Equal(2, Assert.Single(preview.Refunds).Points);
        Assert.Equal(1, (await f.Db.AccountSkillTreeStates.AsNoTracking().SingleAsync()).Version);
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.ToListAsync());
        var applied = await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, Request(false, true));
        Assert.NotNull(applied); Assert.Equal("APPLIED", applied.Status);
        var replay = await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, Request(false, true));
        Assert.NotNull(replay); Assert.Equal(applied.StateVersion, replay.StateVersion);
        f.Db.ChangeTracker.Clear();
        Assert.Equal(2, (await f.Db.AccountSkillTreeStates.SingleAsync()).Version);
        Assert.Single(await f.Db.AccountSkillTreeUnlockedNodes.ToListAsync(), x => x.NodeId == "root");
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task RetainedCostChangesOrDisconnectedPathsRequireSeparateMigration(bool costChange)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        await f.CloseAsync();
        var state = await f.Db.AccountSkillTreeStates.SingleAsync();
        f.Db.AccountSkillTreeUnlockedNodes.Add(new() { AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = state.AccountSkillTreeStateId, NodeId = "gain", CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow, CreatedBy = f.User, UpdatedBy = f.User });
        await f.Db.SaveChangesAsync();
        var canonical = costChange ? f.Canonical.Replace("\"pointCost\":2", "\"pointCost\":3") : f.Canonical.Replace("\"root->gain\"", "");
        var generation = SkillTreeOperationRepositoryTests.Hash(canonical);
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, new() {
            ServerSessionId = f.Boot, ServerStartedAtUtc = f.Started, PublicationRevision = 2,
            PluginVersion = "test", CompatibilityVersion = "skilltree-operation-v1", Ready = true,
            DefinitionGenerationId = generation, CanonicalSnapshotJson = canonical,
        }));
        Assert.Null(await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, new() {
            OperationId = Guid.NewGuid(), ExpectedStateVersion = 1, FromGenerationId = f.Generation,
            ToGenerationId = generation, LegacyBaselineNodeIds = ["root", "gain"], RemoveNodeIds = [],
        }));
        Assert.Equal(1, (await f.Db.AccountSkillTreeStates.AsNoTracking().SingleAsync()).Version);
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.ToListAsync());
    }
}
