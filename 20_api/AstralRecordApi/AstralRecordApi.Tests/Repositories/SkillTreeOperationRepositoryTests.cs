using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class SkillTreeOperationRepositoryTests
{
    [Fact]
    public async Task UnknownCannotEdit_AndOnlyClosedSessionProvidesOfflineDraft()
    {
        await using var f = await Fixture.CreateAsync();
        Assert.Equal("online", (await f.Repository.GetEditorAsync(f.Account, f.User))!.Connection.Status);
        await f.CloseAsync();
        var saved = await f.Repository.GetEditorAsync(f.Account, f.User);
        Assert.Equal("offline", saved!.Connection.Status);
        Assert.Equal("SAVED", saved.BalanceKind);
        Assert.True(saved.CanEdit);
        Assert.Equal(SkillTreeOperationStatuses.PendingOffline, (await f.Repository.CreateAsync(f.Account, f.Request()))!.Status);
        await f.Repository.CancelAsync(f.Account, (await f.Db.SkillTreeOperations.SingleAsync()).OperationId, f.User);
        f.Db.SkillTreeServerPlayerViews.Single().OfflineConfirmed = false;
        await f.Db.SaveChangesAsync();
        Assert.Null(await f.Repository.CreateAsync(f.Account, f.Request()));
    }

    [Fact]
    public async Task IdempotentRequestAndSinglePendingAreEnforced()
    {
        await using var f = await Fixture.CreateAsync();
        var request = f.Request();
        var first = await f.Repository.CreateAsync(f.Account, request);
        var retry = await f.Repository.CreateAsync(f.Account, request);
        Assert.NotNull(first);
        Assert.Equal(first.OperationId, retry!.OperationId);
        Assert.Null(await f.Repository.CreateAsync(f.Account, f.Request()));
        Assert.Null(await f.Repository.CreateAsync(f.Account, f.Request(request.OperationId, node: "changed")));
        Assert.Single(await f.Db.SkillTreeOperations.ToListAsync());
    }

    [Fact]
    public async Task ForeignOwnerCannotReadSubmitCancelOrLookup()
    {
        await using var f = await Fixture.CreateAsync();
        var foreign = Guid.NewGuid();
        Assert.Null(await f.Repository.GetEditorAsync(f.Account, foreign));
        Assert.Null(await f.Repository.CreateAsync(f.Account, f.Request(actor: foreign)));
        var operation = await f.Repository.CreateAsync(f.Account, f.Request());
        Assert.NotNull(operation);
        Assert.Null(await f.Repository.FindAsync(f.Account, operation.OperationId, foreign));
        Assert.Null(await f.Repository.CancelAsync(f.Account, operation.OperationId, foreign));
    }

    [Fact]
    public async Task ChangedOfflineEvaluationRequiresReconfirmation()
    {
        await using var f = await Fixture.CreateAsync();
        await f.CloseAsync();
        var operation = await f.Repository.CreateAsync(f.Account, f.Request());
        Assert.NotNull(operation);
        await f.NewSessionAsync();
        await f.PublishAsync(Hash("changed-balance"));
        Assert.Null(await f.Repository.ClaimAsync(f.Server, operation.OperationId, f.Claim()));
        Assert.Equal(SkillTreeOperationStatuses.ReconfirmationRequired,
            (await f.Repository.FindAsync(f.Account, operation.OperationId, f.User))!.Status);
    }

    [Fact]
    public async Task LogoutCancelsOnlineRequests_AndClosedSessionCannotReturn()
    {
        await using var f = await Fixture.CreateAsync();
        var old = f.SessionRequest();
        var operation = await f.Repository.CreateAsync(f.Account, f.Request());
        await f.CloseAsync();
        Assert.False(await f.Repository.AcquireAccountSessionAsync(f.Server, f.Account, old));
        Assert.Equal(SkillTreeOperationStatuses.Canceled, (await f.Repository.FindAsync(f.Account, operation!.OperationId, f.User))!.Status);
        await f.NewSessionAsync();
        Assert.False(await f.Repository.AcquireAccountSessionAsync(f.Server, f.Account, old));
    }

    [Fact]
    public async Task AccountCannotBeOwnedByTwoSessions_AndExpiredIdIsNotReusable()
    {
        await using var f = await Fixture.CreateAsync();
        var other = new SkillTreeAccountSessionRequest
        {
            AccountSessionId = Guid.NewGuid(), AccountLeaseToken = Hash("other"), ServerSessionId = f.Boot, DefinitionGenerationId = f.Generation,
        };
        Assert.False(await f.Repository.AcquireAccountSessionAsync(f.Server, f.Account, other));
        var current = await f.Db.SkillTreeAccountSessions.SingleAsync(x => !x.Closed);
        current.ExpiresAtUtc = DateTime.UtcNow.AddMinutes(-1);
        await f.Db.SaveChangesAsync();
        Assert.False(await f.Repository.AcquireAccountSessionAsync(f.Server, f.Account, f.SessionRequest()));
        Assert.True(await f.Repository.AcquireAccountSessionAsync(f.Server, f.Account, other));
        Assert.Single(await f.Db.SkillTreeAccountSessions.Where(x => !x.Closed).ToListAsync());
    }

    [Fact]
    public async Task CancelAndExpiredLeaseCannotComplete()
    {
        await using var f = await Fixture.CreateAsync();
        var operation = (await f.Repository.CreateAsync(f.Account, f.Request()))!;
        var claim = await f.Repository.ClaimAsync(f.Server, operation.OperationId, f.Claim());
        Assert.NotNull(claim);
        await f.Repository.CancelAsync(f.Account, operation.OperationId, f.User);
        await AssertRejectedCompletion(f, f.Receipt(operation.OperationId, claim.LeaseToken));
        var next = (await f.Repository.CreateAsync(f.Account, f.Request()))!;
        var nextClaim = (await f.Repository.ClaimAsync(f.Server, next.OperationId, f.Claim()))!;
        f.Db.SkillTreeOperations.Single(x => x.OperationId == next.OperationId).LeaseExpiresAtUtc = DateTime.UtcNow.AddSeconds(-1);
        await f.Db.SaveChangesAsync();
        await AssertRejectedCompletion(f, f.Receipt(next.OperationId, nextClaim.LeaseToken));
        Assert.Equal(SkillTreeOperationStatuses.ReconfirmationRequired, (await f.Repository.FindAsync(f.Account, next.OperationId, f.User))!.Status);
    }

    [Fact]
    public async Task OldBootAndOldPublicationCannotReplaceNewRuntime()
    {
        await using var f = await Fixture.CreateAsync();
        var first = f.Registration();
        var laterBoot = Guid.NewGuid();
        var next = new SkillTreeServerRegistrationRequest
        {
            ServerSessionId = laterBoot, ServerStartedAtUtc = first.ServerStartedAtUtc.AddSeconds(1), PublicationRevision = 1,
            DefinitionGenerationId = f.Generation, CanonicalSnapshotJson = f.Canonical, PluginVersion = "new", CompatibilityVersion = "skilltree-operation-v1", Ready = true,
        };
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, next));
        Assert.Null(await f.Repository.RegisterServerAsync(f.Server, first));
        Assert.False(await f.Repository.ValidateRuntimeStateSaveAsync(f.Account, f.Server, f.Boot, f.Generation, f.Session, f.Token));
    }

    [Fact]
    public async Task ViewSequenceCannotMoveBackwards_AndStaleViewIsNotOffline()
    {
        await using var f = await Fixture.CreateAsync();
        Assert.Null(await f.Repository.RegisterPlayerViewAsync(f.Server, f.Account, f.ViewRequest(sequence: 1)));
        f.Db.SkillTreeServerPlayerViews.Single().LastSeenUtc = DateTime.UtcNow.AddMinutes(-1);
        await f.Db.SaveChangesAsync();
        var editor = await f.Repository.GetEditorAsync(f.Account, f.User);
        Assert.Equal("stale", editor!.Connection.Status);
        Assert.Null(editor.Points);
        Assert.False(editor.CanEdit);
        Assert.Null(await f.Repository.CreateAsync(f.Account, f.Request()));
    }

    [Fact]
    public async Task MasterChangeStopsEditingButAllowsOldStateToFlushBeforeLogout()
    {
        await using var f = await Fixture.CreateAsync();
        var canonical = f.Canonical.Replace("\"pointCost\":2", "\"pointCost\":3");
        var newer = new SkillTreeServerRegistrationRequest
        {
            ServerSessionId = f.Boot, ServerStartedAtUtc = f.Started, PublicationRevision = 2, PluginVersion = "new",
            CompatibilityVersion = "skilltree-operation-v1", CanonicalSnapshotJson = canonical, DefinitionGenerationId = Hash(canonical), Ready = true,
        };
        Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, newer));
        Assert.Null(await f.Repository.RegisterServerAsync(f.Server, f.Registration()));
        Assert.False((await f.Repository.GetEditorAsync(f.Account, f.User))!.CanEdit);
        Assert.True(await f.Repository.ValidateRuntimeStateSaveAsync(f.Account, f.Server, f.Boot, f.Generation, f.Session, f.Token));
    }

    private static async Task AssertRejectedCompletion(Fixture f, PlayerStateSkillTreeOperationSection receipt)
    {
        await using (var transaction = await f.Db.Database.BeginTransactionAsync())
        {
            (await f.Db.AccountSkillTreeStates.SingleAsync()).Version = 2;
            await f.Db.SaveChangesAsync();
            Assert.False(await f.Repository.CompleteFromSnapshotAsync(f.Account, receipt, DateTime.UtcNow.AddMinutes(-1)));
            await transaction.RollbackAsync();
        }
        f.Db.ChangeTracker.Clear();
    }

    internal sealed class Fixture : IAsyncDisposable
    {
        internal readonly SqliteConnection? Connection;
        internal readonly AstralRecordDbContext Db;
        internal readonly SkillTreeOperationRepository Repository;
        internal readonly Guid Account = Guid.NewGuid(), User = Guid.NewGuid(), Boot = Guid.NewGuid();
        internal Guid Session = Guid.NewGuid();
        internal string Token = Hash("account-token");
        internal readonly string Server = "test-server";
        internal readonly DateTime Started = DateTime.UtcNow;
        internal readonly string Canonical = """{"rootNodeId":"root","nodes":[{"nodeId":"root","pointType":"PASSIVE_POINT","pointCost":0,"unlockCondition":{"classId":null,"playerLevel":0}},{"nodeId":"gain","pointType":"PASSIVE_POINT","pointCost":2,"unlockCondition":{"classId":null,"playerLevel":0}}],"positions":[{"nodeId":"root"},{"nodeId":"gain"}],"edges":["root->gain"],"classes":{}}""";
        internal string Generation => Hash(Canonical);
        internal string Fingerprint = Hash("initial");
        private long sequence = 1;
        internal Fixture(SqliteConnection? connection, AstralRecordDbContext db)
        {
            Connection = connection; Db = db;
            Repository = new(db, new NetworkRuntimeService(TimeProvider.System));
        }
        internal static async Task<Fixture> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
            var db = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options);
            await db.Database.EnsureCreatedAsync();
            return await SeedAsync(db, connection);
        }
        internal static async Task<Fixture> SeedAsync(AstralRecordDbContext db, SqliteConnection? connection = null)
        {
            var f = new Fixture(connection, db); var now = DateTime.UtcNow;
            db.Accounts.Add(new() { Uuid = f.Account, UserId = f.User, AccountName = "fixture", SlotIndex = 0, IsActive = true, Level = 1, CreatedAt = now, UpdatedAt = now, CreatedBy = f.User, UpdatedBy = f.User });
            await db.SaveChangesAsync();
            Assert.NotNull(await f.Repository.RegisterServerAsync(f.Server, f.Registration()));
            var state = new AccountSkillTreeStateEntity { AccountSkillTreeStateId = Guid.NewGuid(), AccountId = f.Account, Version = 1, DefinitionGenerationId = f.Generation, CreatedAt = now, UpdatedAt = now, CreatedBy = f.User, UpdatedBy = f.User };
            db.AccountSkillTreeStates.Add(state);
            db.AccountSkillTreeUnlockedNodes.Add(new() { AccountSkillTreeUnlockedNodeId = Guid.NewGuid(), AccountSkillTreeStateId = state.AccountSkillTreeStateId, NodeId = "root", CreatedAt = now, UpdatedAt = now, CreatedBy = f.User, UpdatedBy = f.User });
            await db.SaveChangesAsync();
            Assert.True(await f.Repository.AcquireAccountSessionAsync(f.Server, f.Account, f.SessionRequest()));
            Assert.NotNull(await f.Repository.RegisterPlayerViewAsync(f.Server, f.Account, f.ViewRequest()));
            Assert.True((await f.Repository.GetEditorAsync(f.Account, f.User))!.CanEdit);
            return f;
        }
        internal SkillTreeServerRegistrationRequest Registration() => new() { ServerSessionId = Boot, ServerStartedAtUtc = Started, PublicationRevision = 1, DefinitionGenerationId = Generation, CanonicalSnapshotJson = Canonical, PluginVersion = "test", CompatibilityVersion = "skilltree-operation-v1", Ready = true };
        internal SkillTreeAccountSessionRequest SessionRequest() => new() { ServerSessionId = Boot, AccountSessionId = Session, AccountLeaseToken = Token, DefinitionGenerationId = Generation };
        internal SkillTreePlayerViewRegistrationRequest ViewRequest(long? sequence = null) => new()
        {
            ServerSessionId = Boot, AccountSessionId = Session, AccountLeaseToken = Token, ViewSequence = sequence ?? this.sequence,
            DefinitionGenerationId = Generation, PlayerStateVersion = 1, EvaluationFingerprint = Fingerprint, EditEligible = true,
            View = JsonSerializer.SerializeToElement(new { tree = new { structureId = "main", name = "tree", rootNodeId = "root", nodes = Array.Empty<object>(), edges = Array.Empty<object>() }, points = new { pp = 10, gold = 1000, classes = Array.Empty<object>() }, relockGoldCost = 100, channelName = "test", location = new { worldDisplayName = "base", x = 1, y = 2, z = 3 } }),
        };
        internal async Task CloseAsync() => Assert.True(await Repository.CloseAccountSessionAsync(Server, Account, ViewRequest()));
        internal async Task NewSessionAsync()
        {
            Session = Guid.NewGuid(); Token = Hash(Session.ToString()); sequence = 1;
            Assert.True(await Repository.AcquireAccountSessionAsync(Server, Account, SessionRequest()));
        }
        internal async Task PublishAsync(string? fingerprint = null)
        {
            Fingerprint = fingerprint ?? Fingerprint;
            Assert.NotNull(await Repository.RegisterPlayerViewAsync(Server, Account, ViewRequest(++sequence)));
        }
        internal SkillTreeOperationCreateRequest Request(Guid? id = null, Guid? actor = null, string node = "gain") => new() { OperationId = id ?? Guid.NewGuid(), ActorUserId = actor ?? User, TargetServerId = Server, ExpectedDefinitionGenerationId = Generation, ExpectedPlayerStateVersion = 1, Action = "UNLOCK", NodeId = node };
        internal SkillTreeOperationClaimRequest Claim() => new() { AccountId = Account, ServerSessionId = Boot, AccountSessionId = Session, AccountLeaseToken = Token };
        internal PlayerStateSkillTreeOperationSection Receipt(Guid id, string token) => new() { OperationId = id, ServerId = Server, ServerSessionId = Boot, LeaseToken = token, FinalStatus = SkillTreeOperationStatuses.Applied, DefinitionGenerationId = Generation, FinalPlayerStateVersion = 2, FinalEvaluationFingerprint = Hash("after") };
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); if (Connection is not null) await Connection.DisposeAsync(); }
    }
    internal static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
}
