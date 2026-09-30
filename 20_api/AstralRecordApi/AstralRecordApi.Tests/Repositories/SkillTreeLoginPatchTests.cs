using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class SkillTreeLoginPatchTests
{
    [Fact]
    public async Task KnownOldUnpublishedDefinitionCannotBeRenumberedAsANewPatch()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var oldDefinition = await f.Db.SkillTreeDefinitionGenerations.SingleAsync();
        oldDefinition.PatchVersion = 0;
        await f.Db.SaveChangesAsync();
        var canonical = f.Canonical.Replace("\"classes\":{}", "\"classes\":{},\"name\":\"next\"");
        var target = SkillTreeOperationRepositoryTests.Hash(canonical);
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, Registration(f, canonical, 2)));
        // DBの時刻精度が同じでも公開順を取り違えない。
        await f.Db.SkillTreeDefinitionGenerations.ExecuteUpdateAsync(s => s.SetProperty(x => x.CreatedAtUtc, f.Started));
        Assert.Null(await f.Repository.PublishPatchAsync(f.Server, Guid.NewGuid(), new() { DefinitionGenerationId = target }));
        Assert.Equal(1, (await f.Repository.PublishPatchAsync(f.Server, f.Boot, new() { DefinitionGenerationId = target }))?.PatchVersion);
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, Registration(f, f.Canonical, 3)));
        Assert.Null(await f.Repository.PublishPatchAsync(f.Server, f.Boot, new() { DefinitionGenerationId = f.Generation }));
        Assert.Equal(0, (await f.Db.SkillTreeDefinitionGenerations.AsNoTracking().SingleAsync(x => x.DefinitionGenerationId == f.Generation)).PatchVersion);
    }

    [Fact]
    public async Task PreviewPreservesStateAndApplyIsAtomicAndRetryable()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var target = await PrepareAsync(f);
        var preview = await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target));
        Assert.Equal("UPDATE_REQUIRED", preview?.Status);
        Assert.Equal(1, (await f.Db.AccountSkillTreeStates.AsNoTracking().SingleAsync()).Version);
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.ToArrayAsync());
        Assert.Null(await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target, true, 0)));
        var result = await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target, true));
        Assert.Equal("APPLIED", result?.Status);
        Assert.Equal(2, result?.StateVersion);
        var repeat = await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target, true));
        Assert.Equal("CURRENT", repeat?.Status);
        Assert.Equal(2, repeat?.StateVersion);
        Assert.Single(await f.Db.SkillTreeMigrationOperations.ToArrayAsync());
        Assert.Equal("root", (await f.Db.AccountSkillTreeUnlockedNodes.SingleAsync()).NodeId);
        Assert.Empty(await f.Db.SkillTreeServerPlayerViews.ToArrayAsync());
        Assert.True(await f.Repository.ValidateRuntimeStateSaveAsync(f.Account, f.Server, f.Boot, target, f.Session, f.Token));
        Assert.False(await f.Repository.ValidateRuntimeStateSaveAsync(f.Account, f.Server, f.Boot, f.Generation, f.Session, f.Token));
    }

    [Fact]
    public async Task NewPlayerStateCannotBeMovedBackToOlderChannelEvenAfterRepublish()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var target = await PrepareAsync(f);
        Assert.Equal("APPLIED", (await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target, true)))?.Status);
        await f.CloseAsync();
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, Registration(f, f.Canonical, 3)));
        var old = await f.Repository.PublishPatchAsync(f.Server, f.Boot, new() { DefinitionGenerationId = f.Generation });
        Assert.Equal(1, old?.PatchVersion);
        await f.NewSessionAsync();
        Assert.Equal("CHANNEL_OUTDATED", (await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, f.Generation, true, 2)))?.Status);
        var state = await f.Db.AccountSkillTreeStates.AsNoTracking().SingleAsync();
        Assert.Equal(target, state.DefinitionGenerationId);
        Assert.Equal(2, state.Version);
    }

    [Fact]
    public async Task UnpublishedDefinitionsCannotChangePlayerState()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var target = await PrepareAsync(f, publish: false);
        Assert.Equal("UNPUBLISHED", (await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target, true)))?.Status);
        Assert.Equal(f.Generation, (await f.Db.AccountSkillTreeStates.AsNoTracking().SingleAsync()).DefinitionGenerationId);
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.ToArrayAsync());
    }

    [Theory]
    [InlineData("token")]
    [InlineData("session")]
    [InlineData("boot")]
    [InlineData("expired")]
    [InlineData("playing")]
    public async Task OnlyPreAdmissionOwnerCanPatch(string rejectedCase)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var target = await PrepareAsync(f);
        if (rejectedCase is "expired" or "playing")
        {
            var session = await f.Db.SkillTreeAccountSessions.SingleAsync(x => x.AccountSessionId == f.Session);
            if (rejectedCase == "expired") session.ExpiresAtUtc = DateTime.UtcNow.AddSeconds(-1);
            else session.ViewSequence = 1;
            await f.Db.SaveChangesAsync();
        }
        var request = new SkillTreeLoginPatchRequest
        {
            DefinitionGenerationId = target, ServerSessionId = rejectedCase == "boot" ? Guid.NewGuid() : f.Boot,
            AccountSessionId = rejectedCase == "session" ? Guid.NewGuid() : f.Session,
            AccountLeaseToken = rejectedCase == "token" ? new string('0', 64) : f.Token,
            Apply = true, ExpectedStateVersion = 1,
        };
        Assert.Null(await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, request));
        Assert.Equal(1, (await f.Db.AccountSkillTreeStates.AsNoTracking().SingleAsync()).Version);
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.ToArrayAsync());
    }

    [Theory]
    [InlineData("cost", "INCOMPATIBLE")]
    [InlineData("removed", "INCOMPATIBLE")]
    [InlineData("legacy", "LEGACY_REQUIRES_REPAIR")]
    [InlineData("unknown", "LEGACY_REQUIRES_REPAIR")]
    public async Task UnsafeUpdatesLeaveSavedProgressUntouched(string change, string status)
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var canonical = change switch
        {
            "cost" => f.Canonical.Replace("\"pointCost\":0", "\"pointCost\":3"),
            "removed" => f.Canonical.Replace("\"nodeId\":\"root\"", "\"nodeId\":\"renamed\""),
            _ => null,
        };
        var target = await PrepareAsync(f, canonical);
        if (change == "legacy")
            (await f.Db.AccountSkillTreeStates.SingleAsync()).DefinitionGenerationId = null;
        if (change == "unknown")
            (await f.Db.SkillTreeDefinitionGenerations.SingleAsync(x => x.DefinitionGenerationId == f.Generation)).PatchVersion = null;
        await f.Db.SaveChangesAsync();
        Assert.Equal(status, (await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target, true)))?.Status);
        Assert.Equal(1, (await f.Db.AccountSkillTreeStates.AsNoTracking().SingleAsync()).Version);
        Assert.Equal("root", (await f.Db.AccountSkillTreeUnlockedNodes.SingleAsync()).NodeId);
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.ToArrayAsync());
    }

    [Fact]
    public async Task BaselineCanUpgradeAndPendingEditsAreCanceledOnlyOnApply()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var target = await PrepareAsync(f);
        (await f.Db.SkillTreeDefinitionGenerations.SingleAsync(x => x.DefinitionGenerationId == f.Generation)).PatchVersion = 0;
        var operation = new SkillTreeOperationEntity
        {
            OperationId = Guid.NewGuid(), AccountId = f.Account, ActorUserId = f.User, TargetServerId = f.Server,
            RequestHash = new string('c', 64), ExpectedDefinitionGenerationId = f.Generation,
            ExpectedPlayerStateVersion = 1, Action = "UNLOCK", NodeId = "gain",
            Status = SkillTreeOperationStatuses.PendingOffline, CreatedAtUtc = DateTime.UtcNow, ExpiresAtUtc = DateTime.UtcNow.AddDays(1),
        };
        f.Db.SkillTreeOperations.Add(operation);
        await f.Db.SaveChangesAsync();
        Assert.Equal("UPDATE_REQUIRED", (await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target)))?.Status);
        Assert.Equal(SkillTreeOperationStatuses.PendingOffline, (await f.Db.SkillTreeOperations.AsNoTracking().SingleAsync()).Status);
        Assert.Equal("APPLIED", (await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, target, true)))?.Status);
        Assert.Equal(SkillTreeOperationStatuses.Canceled, (await f.Db.SkillTreeOperations.AsNoTracking().SingleAsync()).Status);
    }

    [Fact]
    public async Task CurrentAndNewEmptyPlayersNeedNoPatchWrite()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        await f.Repository.PublishPatchAsync(f.Server, f.Boot, new() { DefinitionGenerationId = f.Generation });
        await f.CloseAsync();
        await f.NewSessionAsync();
        Assert.Equal("CURRENT", (await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, f.Generation)))?.Status);
        await f.Db.AccountSkillTreeUnlockedNodes.ExecuteDeleteAsync();
        await f.Db.AccountSkillTreeStates.ExecuteDeleteAsync();
        Assert.Equal("CURRENT", (await f.Repository.PrepareLoginPatchAsync(f.Server, f.Account, Request(f, f.Generation)))?.Status);
        Assert.Empty(await f.Db.AccountSkillTreeStates.ToArrayAsync());
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.ToArrayAsync());
    }

    private static SkillTreeLoginPatchRequest Request(SkillTreeOperationRepositoryTests.Fixture f, string generation, bool apply = false, int version = 1) => new()
    {
        ServerSessionId = f.Boot, AccountSessionId = f.Session, AccountLeaseToken = f.Token,
        DefinitionGenerationId = generation, Apply = apply, ExpectedStateVersion = version,
    };

    private static SkillTreeServerRegistrationRequest Registration(SkillTreeOperationRepositoryTests.Fixture f, string canonical, long revision) => new()
    {
        ServerSessionId = f.Boot, ServerStartedAtUtc = f.Started, PublicationRevision = revision,
        DefinitionGenerationId = SkillTreeOperationRepositoryTests.Hash(canonical), CanonicalSnapshotJson = canonical,
        PluginVersion = "fixture", CompatibilityVersion = "skilltree-operation-v2", Ready = true,
    };

    private static async Task<string> PrepareAsync(SkillTreeOperationRepositoryTests.Fixture f, string? canonical = null, bool publish = true)
    {
        Assert.Equal(1, (await f.Repository.PublishPatchAsync(f.Server, f.Boot, new() { DefinitionGenerationId = f.Generation }))?.PatchVersion);
        await f.CloseAsync();
        canonical ??= f.Canonical.Replace("\"classes\":{}", "\"classes\":{},\"name\":\"next\"");
        var target = SkillTreeOperationRepositoryTests.Hash(canonical);
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, Registration(f, canonical, 2)));
        if (publish)
        {
            Assert.Equal(2, (await f.Repository.PublishPatchAsync(f.Server, f.Boot, new() { DefinitionGenerationId = target }))?.PatchVersion);
            Assert.Equal(2, (await f.Repository.PublishPatchAsync(f.Server, f.Boot, new() { DefinitionGenerationId = target }))?.PatchVersion);
        }
        f.Session = Guid.NewGuid();
        Assert.True(await f.Repository.AcquireAccountSessionAsync(f.Server, f.Account, new()
        {
            ServerSessionId = f.Boot, AccountSessionId = f.Session, AccountLeaseToken = f.Token, DefinitionGenerationId = target,
        }));
        return target;
    }
}
