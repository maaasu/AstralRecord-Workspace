using System.Data.Common;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Xunit;
using Fixture = AstralRecordApi.Tests.Repositories.SkillTreeOperationRepositoryTests.Fixture;
using static AstralRecordApi.Tests.Repositories.SkillTreeOperationRepositoryTests;

namespace AstralRecordApi.Tests.Repositories;

public sealed class SkillTreeAccountHeartbeatTests
{
    [Fact]
    public async Task AckPreservesLegacyEditorContractAndRejectsDelayedSequence()
    {
        await using var f = await Fixture.CreateAsync();
        Assert.True(await f.Repository.RegisterPlayerViewAckAsync(f.Server, f.Account, f.ViewRequest(2)));
        Assert.False(await f.Repository.RegisterPlayerViewAckAsync(f.Server, f.Account, f.ViewRequest(2)));
        var legacy = await f.Repository.RegisterPlayerViewAsync(f.Server, f.Account, f.ViewRequest(3));
        Assert.Equal("online", legacy!.Connection.Status);
        Assert.True(legacy.CanEdit);
        Assert.NotNull(legacy.Tree);
    }

    [Fact]
    public async Task UnchangedViewRefreshesLeaseAndFreshnessWithoutRewritingJson()
    {
        await using var f = await Fixture.CreateAsync();
        var cached = await f.Db.SkillTreeServerPlayerViews.SingleAsync();
        var originalJson = cached.ViewJson;
        cached.LastSeenUtc = DateTime.UtcNow.AddMinutes(-1);
        var owner = await f.Db.SkillTreeAccountSessions.SingleAsync();
        owner.ExpiresAtUtc = DateTime.UtcNow.AddSeconds(3);
        await f.Db.SaveChangesAsync();
        var result = await Send(f, Item(f, ViewWithoutPayload(f, 2), true, Hash(originalJson)));
        Assert.True(result.Renewed);
        Assert.True(result.ViewAccepted);
        f.Db.ChangeTracker.Clear();
        cached = await f.Db.SkillTreeServerPlayerViews.SingleAsync();
        Assert.Equal(originalJson, cached.ViewJson);
        Assert.True(cached.LastSeenUtc > DateTime.UtcNow.AddSeconds(-5));
        owner = await f.Db.SkillTreeAccountSessions.SingleAsync();
        Assert.Equal(2, owner.ViewSequence);
        Assert.True(owner.ExpiresAtUtc > DateTime.UtcNow.AddSeconds(35));
        Assert.True((await f.Repository.GetEditorAsync(f.Account, f.User))!.HasFreshState);
    }

    [Theory]
    [InlineData("sequence")]
    [InlineData("hash")]
    [InlineData("version")]
    public async Task RejectedUnchangedViewCannotAdvanceSequenceOrFreshnessButRenewsValidSession(string conflict)
    {
        await using var f = await Fixture.CreateAsync();
        var original = await f.Db.SkillTreeServerPlayerViews.SingleAsync();
        var before = original.LastSeenUtc;
        if (conflict == "version")
        {
            (await f.Db.AccountSkillTreeStates.SingleAsync()).Version = 2;
            await f.Db.SaveChangesAsync();
        }
        var result = await Send(f, Item(f, ViewWithoutPayload(f, conflict == "sequence" ? 1 : 2), true,
            conflict == "hash" ? Hash("different-location-with-same-evaluation") : Hash(original.ViewJson)));
        Assert.True(result.Renewed);
        Assert.False(result.ViewAccepted);
        f.Db.ChangeTracker.Clear();
        Assert.Equal(1, (await f.Db.SkillTreeAccountSessions.SingleAsync()).ViewSequence);
        Assert.Equal(before, (await f.Db.SkillTreeServerPlayerViews.SingleAsync()).LastSeenUtc);
    }

    [Theory]
    [InlineData("expired")]
    [InlineData("closed")]
    [InlineData("token")]
    [InlineData("boot")]
    [InlineData("generation")]
    public async Task InvalidAuthorityCannotRenewOrReadPending(string conflict)
    {
        await using var f = await Fixture.CreateAsync();
        Assert.NotNull(await f.Repository.CreateAsync(f.Account, f.Request()));
        var owner = await f.Db.SkillTreeAccountSessions.SingleAsync();
        if (conflict == "expired") owner.ExpiresAtUtc = DateTime.UtcNow.AddSeconds(-1);
        if (conflict == "closed") owner.Closed = true;
        await f.Db.SaveChangesAsync();
        var before = owner.ExpiresAtUtc;
        var session = new SkillTreeAccountSessionRequest
        {
            ServerSessionId = conflict == "boot" ? Guid.NewGuid() : f.Boot, AccountSessionId = f.Session,
            AccountLeaseToken = conflict == "token" ? Hash("wrong") : f.Token,
            DefinitionGenerationId = conflict == "generation" ? Hash("wrong") : f.Generation,
        };
        var result = await Send(f, new() { AccountId = f.Account, Session = session, View = f.ViewRequest(2) });
        Assert.False(result.Renewed);
        Assert.False(result.ViewAccepted);
        Assert.Empty(result.Operations);
        Assert.Equal(before, (await f.Db.SkillTreeAccountSessions.AsNoTracking().SingleAsync()).ExpiresAtUtc);
    }

    [Theory]
    [InlineData("DRAINING", true)]
    [InlineData("RECOVERY_REQUIRED", true)]
    [InlineData("READY", false)]
    [InlineData("APPLYING", false)]
    public async Task AdminDrainUsesSameCapturedSessionRenewalPolicyAsAcquisition(string status, bool renewed)
    {
        await using var f = await Fixture.CreateAsync();
        var now = DateTime.UtcNow; var editId = Guid.NewGuid();
        f.Db.PlayerAdminEditSessions.Add(new() { EditSessionId = editId, AccountId = f.Account,
            UserUuid = f.User, ActorUserUuid = f.User, Reason = "fixture", Status = status,
            ExpectedServerCount = 1, CreatedAtUtc = now, UpdatedAtUtc = now, ExpiresAtUtc = now.AddMinutes(30) });
        f.Db.PlayerAdminEditDrains.Add(new() { EditSessionId = editId, ServerId = f.Server, ServerSessionId = f.Boot });
        await f.Db.SaveChangesAsync();
        var result = await Send(f, Item(f));
        Assert.Equal(renewed, result.Renewed);
        Assert.Equal(renewed, await f.Repository.AcquireAccountSessionAsync(f.Server, f.Account, f.SessionRequest()));
    }

    [Fact]
    public async Task BatchHasOnePendingSelectAndNoOperationUpdatesEvenWhenExpiredOperationExists()
    {
        var sql = new Commands();
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var context = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection).AddInterceptors(sql).Options);
        await context.Database.EnsureCreatedAsync();
        await using var f = await Fixture.SeedAsync(context);
        Assert.NotNull(await f.Repository.CreateAsync(f.Account, f.Request()));
        (await f.Db.SkillTreeOperations.SingleAsync()).ExpiresAtUtc = DateTime.UtcNow.AddSeconds(-1);
        await f.Db.SaveChangesAsync();
        // 2件目は独立owner。SQL回数がaccount数でpending SELECT/UPDATEへ増殖しないことを検証する。
        await using var other = await Fixture.SeedAsync(context, server: "other-server");
        var otherOwner = await context.SkillTreeAccountSessions.SingleAsync(x => x.AccountSessionId == other.Session);
        otherOwner.ServerId = f.Server; otherOwner.ServerSessionId = f.Boot;
        await context.SaveChangesAsync();
        sql.Text.Clear();
        var results = await f.Repository.HeartbeatAccountsAsync(f.Server, new() { Items = [Item(f),
            new() { AccountId = other.Account, Session = new() { ServerSessionId = f.Boot, AccountSessionId = other.Session,
                AccountLeaseToken = other.Token, DefinitionGenerationId = other.Generation } }] });
        Assert.All(results!, r => { Assert.True(r.Renewed); Assert.Empty(r.Operations); });
        Assert.Single(sql.Text, s => s.TrimStart().StartsWith("SELECT", StringComparison.OrdinalIgnoreCase)
            && s.Contains("skilltree_operation\"", StringComparison.Ordinal));
        Assert.DoesNotContain(sql.Text, s => s.Contains("UPDATE \"skilltree_operation\"", StringComparison.Ordinal));
    }

    [Fact]
    public async Task PendingOperationDeliveredAfterViewAckButExpiredAndClaimedAreExcluded()
    {
        await using var f = await Fixture.CreateAsync();
        var operation = (await f.Repository.CreateAsync(f.Account, f.Request()))!;
        var result = await Send(f, Item(f, f.ViewRequest(2)));
        Assert.True(result.ViewAccepted);
        Assert.Equal(operation.OperationId, Assert.Single(result.Operations).OperationId);
        Assert.NotNull(await f.Repository.ClaimAsync(f.Server, operation.OperationId, f.Claim()));
        Assert.Empty((await Send(f, Item(f))).Operations);
    }

    [Fact]
    public async Task DuplicateOrOversizedBatchRejectedBeforeLeaseChanges()
    {
        await using var f = await Fixture.CreateAsync();
        var before = (await f.Db.SkillTreeAccountSessions.SingleAsync()).ExpiresAtUtc;
        Assert.Null(await f.Repository.HeartbeatAccountsAsync(f.Server, new() { Items = [Item(f), Item(f)] }));
        Assert.Null(await f.Repository.HeartbeatAccountsAsync(f.Server, new() { Items = Enumerable.Repeat(Item(f), 33).ToArray() }));
        Assert.Equal(before, (await f.Db.SkillTreeAccountSessions.AsNoTracking().SingleAsync()).ExpiresAtUtc);
    }

    private static SkillTreeAccountHeartbeatItemRequest Item(Fixture f, SkillTreePlayerViewRegistrationRequest? view = null,
        bool unchanged = false, string? hash = null) => new()
    { AccountId = f.Account, Session = f.SessionRequest(), View = view, ViewUnchanged = unchanged, ViewContentHash = hash };
    private static SkillTreePlayerViewRegistrationRequest ViewWithoutPayload(Fixture f, long sequence) => new()
    {
        ServerSessionId = f.Boot, AccountSessionId = f.Session, AccountLeaseToken = f.Token, ViewSequence = sequence,
        DefinitionGenerationId = f.Generation, PlayerStateVersion = 1, EvaluationFingerprint = f.Fingerprint, EditEligible = true,
    };
    private static async Task<SkillTreeAccountHeartbeatItemResponse> Send(Fixture f, SkillTreeAccountHeartbeatItemRequest item)
        => Assert.Single((await f.Repository.HeartbeatAccountsAsync(f.Server, new() { Items = [item] }))!);
    private sealed class Commands : DbCommandInterceptor
    {
        internal readonly List<string> Text = [];
        public override ValueTask<InterceptionResult<DbDataReader>> ReaderExecutingAsync(DbCommand command,
            CommandEventData eventData, InterceptionResult<DbDataReader> result, CancellationToken cancellationToken = default)
        { Text.Add(command.CommandText); return ValueTask.FromResult(result); }
        public override ValueTask<InterceptionResult<int>> NonQueryExecutingAsync(DbCommand command,
            CommandEventData eventData, InterceptionResult<int> result, CancellationToken cancellationToken = default)
        { Text.Add(command.CommandText); return ValueTask.FromResult(result); }
    }
}
