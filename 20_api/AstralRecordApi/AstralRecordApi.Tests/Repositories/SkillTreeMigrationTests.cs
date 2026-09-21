using System.Text.Json;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class SkillTreeMigrationTests
{
    private const string AdditiveCanonical = """{"rootNodeId":"root","nodes":[{"nodeId":"root","pointType":"PASSIVE_POINT","pointCost":0,"unlockCondition":{"classId":null,"playerLevel":0}},{"nodeId":"gain","pointType":"PASSIVE_POINT","pointCost":2,"unlockCondition":{"classId":null,"playerLevel":0}},{"nodeId":"bonus","pointType":"PASSIVE_POINT","pointCost":1,"unlockCondition":{"classId":null,"playerLevel":0}}],"positions":[{"nodeId":"root"},{"nodeId":"gain"},{"nodeId":"bonus"}],"edges":["root->gain","gain->bonus"],"classes":{}}""";
    private const string ClassSourceCanonical = """{"rootNodeId":"root","nodes":[{"nodeId":"root","pointType":"PASSIVE_POINT","pointCost":0,"unlockCondition":{"classId":null,"playerLevel":0}},{"nodeId":"class","pointType":"CLASS_POINT","pointCost":2,"unlockCondition":{"classId":"adventurer","playerLevel":0}}],"positions":[{"nodeId":"root"},{"nodeId":"class"}],"edges":["root->class"],"classes":{"adventurer":{"classId":"adventurer"}}}""";
    private const string ClassTargetCanonical = """{"rootNodeId":"root","nodes":[{"nodeId":"root","pointType":"PASSIVE_POINT","pointCost":0,"unlockCondition":{"classId":null,"playerLevel":0}},{"nodeId":"class","pointType":"CLASS_POINT","pointCost":2,"unlockCondition":{"classId":"adventurer","playerLevel":0}},{"nodeId":"bonus","pointType":"PASSIVE_POINT","pointCost":1,"unlockCondition":{"classId":null,"playerLevel":0}}],"positions":[{"nodeId":"root"},{"nodeId":"class"},{"nodeId":"bonus"}],"edges":["root->class","class->bonus"],"classes":{"adventurer":{"classId":"adventurer"}}}""";

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

    [Fact]
    public async Task CandidatesAndBatchMigrateMultipleAccountsWithReplaySafety()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        await f.CloseAsync();
        var second = await AddOfflineAccountAsync(f, "second");
        var targetGeneration = await RegisterAdditiveGenerationAsync(f);

        var runtime = await f.Repository.GetServerRuntimeAsync(f.Server);
        Assert.NotNull(runtime);
        Assert.Equal(targetGeneration, runtime.DefinitionGenerationId);
        var candidates = await f.Repository.GetMigrationCandidatesAsync(f.Server, f.Boot, targetGeneration, 1, 100);
        Assert.NotNull(candidates);
        Assert.Equal(2, candidates.TotalCount);
        Assert.True(new[] { f.Account, second }.ToHashSet().SetEquals(candidates.Items.Select(x => x.AccountId)));
        var firstPage = await f.Repository.GetMigrationCandidatesAsync(f.Server, f.Boot, targetGeneration, 1, 1);
        var secondPage = await f.Repository.GetMigrationCandidatesAsync(f.Server, f.Boot, targetGeneration, 2, 1);
        Assert.NotNull(firstPage);
        Assert.NotNull(secondPage);
        Assert.Equal(2, firstPage.TotalCount);
        Assert.NotEqual(Assert.Single(firstPage.Items).AccountId, Assert.Single(secondPage.Items).AccountId);

        var items = candidates.Items.Select(candidate => new SkillTreeMigrationBatchItemRequest
        {
            AccountId = candidate.AccountId,
            Migration = new()
            {
                OperationId = Guid.NewGuid(),
                ExpectedStateVersion = candidate.ExpectedStateVersion,
                FromGenerationId = candidate.FromGenerationId,
                ToGenerationId = targetGeneration,
                LegacyBaselineNodeIds = candidate.LegacyBaselineNodeIds,
                RemoveNodeIds = [],
            },
        }).ToArray();
        var preview = await f.Repository.MigrateBatchAsync(f.Server, f.Boot, new()
        {
            Mode = SkillTreeMigrationBatchModes.Preview,
            Items = items,
        });
        Assert.NotNull(preview);
        Assert.Equal(2, preview.AcceptedCount);
        Assert.Equal(0, preview.RejectedCount);
        Assert.All(preview.Items, item => Assert.Equal("PREVIEW", item.Status));
        Assert.All(await f.Db.AccountSkillTreeStates.AsNoTracking().ToListAsync(), state =>
        {
            Assert.Equal(f.Generation, state.DefinitionGenerationId);
            Assert.Equal(1, state.Version);
        });
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.AsNoTracking().ToListAsync());

        var commit = await f.Repository.MigrateBatchAsync(f.Server, f.Boot, new()
        {
            Mode = SkillTreeMigrationBatchModes.Commit,
            Items = items,
        });
        Assert.NotNull(commit);
        Assert.Equal(2, commit.AcceptedCount);
        Assert.All(commit.Items, item => Assert.Equal("APPLIED", item.Status));
        var replay = await f.Repository.MigrateBatchAsync(f.Server, f.Boot, new()
        {
            Mode = SkillTreeMigrationBatchModes.Commit,
            Items = items,
        });
        Assert.NotNull(replay);
        Assert.Equal(2, replay.AcceptedCount);
        f.Db.ChangeTracker.Clear();
        Assert.All(await f.Db.AccountSkillTreeStates.AsNoTracking().ToListAsync(), state =>
        {
            Assert.Equal(targetGeneration, state.DefinitionGenerationId);
            Assert.Equal(2, state.Version);
        });
        Assert.Equal(2, await f.Db.SkillTreeMigrationOperations.AsNoTracking().CountAsync());
    }

    [Fact]
    public async Task BatchRejectsOnlineAccountButContinuesWithOfflineAccount()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var second = await AddOfflineAccountAsync(f, "offline");
        var targetGeneration = await RegisterAdditiveGenerationAsync(f);
        var candidates = await f.Repository.GetMigrationCandidatesAsync(f.Server, f.Boot, targetGeneration, 1, 100);
        Assert.NotNull(candidates);
        var batch = await f.Repository.MigrateBatchAsync(f.Server, f.Boot, new()
        {
            Mode = SkillTreeMigrationBatchModes.Preview,
            Items = candidates.Items.Select(candidate => new SkillTreeMigrationBatchItemRequest
            {
                AccountId = candidate.AccountId,
                Migration = new()
                {
                    OperationId = Guid.NewGuid(),
                    ExpectedStateVersion = candidate.ExpectedStateVersion,
                    FromGenerationId = candidate.FromGenerationId,
                    ToGenerationId = targetGeneration,
                    LegacyBaselineNodeIds = candidate.LegacyBaselineNodeIds,
                    RemoveNodeIds = [],
                },
            }).ToArray(),
        });

        Assert.NotNull(batch);
        Assert.Equal(1, batch.AcceptedCount);
        Assert.Equal(1, batch.RejectedCount);
        Assert.Equal("REJECTED", batch.Items.Single(x => x.AccountId == f.Account).Status);
        Assert.Equal("PREVIEW", batch.Items.Single(x => x.AccountId == second).Status);
    }

    [Fact]
    public async Task BatchRejectsDuplicateAccountsBeforeApplyingAnyMigration()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        await f.CloseAsync();
        var request = new SkillTreeMigrationRequest
        {
            OperationId = Guid.NewGuid(),
            ExpectedStateVersion = 1,
            FromGenerationId = f.Generation,
            ToGenerationId = f.Generation,
            LegacyBaselineNodeIds = ["root"],
            RemoveNodeIds = [],
        };
        Assert.Null(await f.Repository.MigrateBatchAsync(f.Server, f.Boot, new()
        {
            Mode = SkillTreeMigrationBatchModes.Commit,
            Items =
            [
                new() { AccountId = f.Account, Migration = request },
                new() { AccountId = f.Account, Migration = new() { OperationId = Guid.NewGuid(), ExpectedStateVersion = 1, FromGenerationId = f.Generation, ToGenerationId = f.Generation, LegacyBaselineNodeIds = ["root"], RemoveNodeIds = [] } },
            ],
        }));
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task CandidatesIncludeNonEmptyLegacyButExcludeEmptyLegacy()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        var nonEmptyLegacy = await AddAccountAsync(f, "legacy", null, true);
        var emptyLegacy = await AddAccountAsync(f, "empty", null, false);
        var targetGeneration = await RegisterAdditiveGenerationAsync(f);

        var candidates = await f.Repository.GetMigrationCandidatesAsync(f.Server, f.Boot, targetGeneration, 1, 100);
        Assert.NotNull(candidates);
        Assert.Contains(candidates.Items, item => item.AccountId == nonEmptyLegacy && item.FromGenerationId is null);
        Assert.DoesNotContain(candidates.Items, item => item.AccountId == emptyLegacy);
    }

    [Fact]
    public async Task BatchRejectsInvalidLaterItemBeforeCommittingEarlierItem()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        await f.CloseAsync();
        var second = await AddOfflineAccountAsync(f, "second");
        var targetGeneration = await RegisterAdditiveGenerationAsync(f);
        var candidates = await f.Repository.GetMigrationCandidatesAsync(f.Server, f.Boot, targetGeneration, 1, 100);
        Assert.NotNull(candidates);
        var items = candidates.Items.Select(candidate => new SkillTreeMigrationBatchItemRequest
        {
            AccountId = candidate.AccountId,
            Migration = new()
            {
                OperationId = Guid.NewGuid(),
                ExpectedStateVersion = candidate.ExpectedStateVersion,
                FromGenerationId = candidate.FromGenerationId,
                ToGenerationId = candidate.AccountId == second ? "invalid" : targetGeneration,
                LegacyBaselineNodeIds = candidate.LegacyBaselineNodeIds,
                RemoveNodeIds = [],
            },
        }).ToArray();

        Assert.Null(await f.Repository.MigrateBatchAsync(f.Server, f.Boot, new()
        {
            Mode = SkillTreeMigrationBatchModes.Commit,
            Items = items,
        }));
        Assert.All(await f.Db.AccountSkillTreeStates.AsNoTracking().ToListAsync(), state =>
        {
            Assert.Equal(f.Generation, state.DefinitionGenerationId);
            Assert.Equal(1, state.Version);
        });
        Assert.Empty(await f.Db.SkillTreeMigrationOperations.AsNoTracking().ToListAsync());
    }

    [Fact]
    public async Task MigrationCanAssignMissingConsumedClassFromFixedDefinitionOnlyOnCommit()
    {
        await using var f = await SkillTreeOperationRepositoryTests.Fixture.CreateAsync();
        await f.CloseAsync();
        f.Db.ChangeTracker.Clear();
        var state = await f.Db.AccountSkillTreeStates.AsNoTracking().SingleAsync();
        var sourceGeneration = SkillTreeOperationRepositoryTests.Hash(ClassSourceCanonical);
        var targetGeneration = SkillTreeOperationRepositoryTests.Hash(ClassTargetCanonical);
        await f.Db.AccountSkillTreeStates.Where(x => x.AccountId == f.Account)
            .ExecuteUpdateAsync(setters => setters.SetProperty(x => x.DefinitionGenerationId, sourceGeneration));
        f.Db.AccountSkillTreeUnlockedNodes.Add(new()
        {
            AccountSkillTreeUnlockedNodeId = Guid.NewGuid(),
            AccountSkillTreeStateId = state.AccountSkillTreeStateId,
            NodeId = "class",
            ConsumedClassId = null,
            CreatedAt = DateTime.UtcNow,
            UpdatedAt = DateTime.UtcNow,
            CreatedBy = f.User,
            UpdatedBy = f.User,
        });
        await f.Db.SaveChangesAsync();
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, new()
        {
            ServerSessionId = f.Boot,
            ServerStartedAtUtc = f.Started,
            PublicationRevision = 2,
            PluginVersion = "test",
            CompatibilityVersion = "skilltree-operation-v1",
            Ready = true,
            DefinitionGenerationId = sourceGeneration,
            CanonicalSnapshotJson = ClassSourceCanonical,
        }));
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, new()
        {
            ServerSessionId = f.Boot,
            ServerStartedAtUtc = f.Started,
            PublicationRevision = 3,
            PluginVersion = "test",
            CompatibilityVersion = "skilltree-operation-v1",
            Ready = true,
            DefinitionGenerationId = targetGeneration,
            CanonicalSnapshotJson = ClassTargetCanonical,
        }));

        SkillTreeMigrationRequest Request(bool preview, string assignedClass) => new()
        {
            OperationId = Guid.NewGuid(),
            ExpectedStateVersion = 1,
            FromGenerationId = sourceGeneration,
            ToGenerationId = targetGeneration,
            LegacyBaselineNodeIds = ["root", "class"],
            RemoveNodeIds = [],
            ConsumedClassAssignments = [new() { NodeId = "class", ConsumedClassId = assignedClass }],
            PreviewOnly = preview,
        };
        Assert.Null(await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, Request(true, "warrior")));
        var preview = Request(true, "adventurer");
        Assert.NotNull(await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, preview));
        f.Db.ChangeTracker.Clear();
        Assert.Null((await f.Db.AccountSkillTreeUnlockedNodes.SingleAsync(x => x.NodeId == "class")).ConsumedClassId);

        var commit = new SkillTreeMigrationRequest
        {
            OperationId = preview.OperationId,
            ExpectedStateVersion = preview.ExpectedStateVersion,
            FromGenerationId = preview.FromGenerationId,
            ToGenerationId = preview.ToGenerationId,
            LegacyBaselineNodeIds = preview.LegacyBaselineNodeIds,
            RemoveNodeIds = preview.RemoveNodeIds,
            ConsumedClassAssignments = preview.ConsumedClassAssignments,
            PreviewOnly = false,
        };
        var applied = await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, commit);
        Assert.NotNull(applied);
        Assert.Equal("adventurer", Assert.Single(applied.ConsumedClassAssignments).ConsumedClassId);
        var replay = await f.Repository.MigrateAsync(f.Server, f.Boot, f.Account, commit);
        Assert.NotNull(replay);
        Assert.Equal(applied.StateVersion, replay.StateVersion);
        f.Db.ChangeTracker.Clear();
        Assert.Equal("adventurer", (await f.Db.AccountSkillTreeUnlockedNodes.SingleAsync(x => x.NodeId == "class")).ConsumedClassId);
        Assert.Equal(targetGeneration, (await f.Db.AccountSkillTreeStates.SingleAsync()).DefinitionGenerationId);
    }

    private static async Task<Guid> AddOfflineAccountAsync(SkillTreeOperationRepositoryTests.Fixture f, string name)
        => await AddAccountAsync(f, name, f.Generation, true);

    private static async Task<Guid> AddAccountAsync(
        SkillTreeOperationRepositoryTests.Fixture f,
        string name,
        string? generation,
        bool addRoot)
    {
        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var stateId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        f.Db.Accounts.Add(new()
        {
            Uuid = accountId,
            UserId = userId,
            AccountName = name,
            SlotIndex = 1,
            IsActive = true,
            Level = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = userId,
            UpdatedBy = userId,
        });
        f.Db.AccountSkillTreeStates.Add(new()
        {
            AccountSkillTreeStateId = stateId,
            AccountId = accountId,
            Version = 1,
            DefinitionGenerationId = generation,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = userId,
            UpdatedBy = userId,
        });
        if (addRoot) f.Db.AccountSkillTreeUnlockedNodes.Add(new()
        {
            AccountSkillTreeUnlockedNodeId = Guid.NewGuid(),
            AccountSkillTreeStateId = stateId,
            NodeId = "root",
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = userId,
            UpdatedBy = userId,
        });
        await f.Db.SaveChangesAsync();
        return accountId;
    }

    private static async Task<string> RegisterAdditiveGenerationAsync(SkillTreeOperationRepositoryTests.Fixture f)
    {
        var generation = SkillTreeOperationRepositoryTests.Hash(AdditiveCanonical);
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, new()
        {
            ServerSessionId = f.Boot,
            ServerStartedAtUtc = f.Started,
            PublicationRevision = 2,
            PluginVersion = "test",
            CompatibilityVersion = "skilltree-operation-v1",
            Ready = true,
            DefinitionGenerationId = generation,
            CanonicalSnapshotJson = AdditiveCanonical,
        }));
        return generation;
    }
}
