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
    public async Task CreateAsync_RejectsUnknownPresence_AndAllowsExplicitOfflineView()
    {
        await using var fixture = await Fixture.CreateAsync();
        var unknown = await fixture.Repository.CreateAsync(fixture.AccountId, fixture.Request(Guid.NewGuid()));
        Assert.Equal(SkillTreeOperationStatuses.Canceled, unknown!.Status);
        Assert.Contains("接続状態", unknown.Reason);

        fixture.View.OfflineConfirmed = true;
        await fixture.Db.SaveChangesAsync();
        var accepted = await fixture.Repository.CreateAsync(fixture.AccountId, fixture.Request(Guid.NewGuid()));
        Assert.Equal(SkillTreeOperationStatuses.PendingOffline, accepted!.Status);
    }

    [Fact]
    public async Task CreateAsync_IsIdempotentForSamePayload_AndRejectsSecondPendingOperation()
    {
        await using var fixture = await Fixture.CreateAsync(offlineConfirmed: true);
        var operationId = Guid.NewGuid();
        var first = await fixture.Repository.CreateAsync(fixture.AccountId, fixture.Request(operationId));
        var retry = await fixture.Repository.CreateAsync(fixture.AccountId, fixture.Request(operationId));
        var another = await fixture.Repository.CreateAsync(fixture.AccountId, fixture.Request(Guid.NewGuid()));
        Assert.Equal(SkillTreeOperationStatuses.PendingOffline, first!.Status);
        Assert.Equal(first.OperationId, retry!.OperationId);
        Assert.Null(another);
    }

    [Fact]
    public async Task ClaimAsync_RequiresUnchangedEvaluationFingerprint()
    {
        await using var fixture = await Fixture.CreateAsync(offlineConfirmed: true);
        var operation = await fixture.Repository.CreateAsync(fixture.AccountId, fixture.Request(Guid.NewGuid()));
        fixture.View.EvaluationFingerprint = Hash("changed");
        fixture.View.EditEligible = true;
        fixture.View.OfflineConfirmed = false;
        await fixture.Db.SaveChangesAsync();
        var claimed = await fixture.Repository.ClaimAsync(fixture.ServerId, operation!.OperationId, new SkillTreeOperationClaimRequest { AccountId = fixture.AccountId, ServerSessionId = fixture.SessionId });
        Assert.Null(claimed);
        Assert.Equal(SkillTreeOperationStatuses.ReconfirmationRequired, (await fixture.Db.SkillTreeOperations.SingleAsync()).Status);
    }

    private sealed class Fixture : IAsyncDisposable
    {
        public required SqliteConnection Connection { get; init; }
        public required AstralRecordDbContext Db { get; init; }
        public required SkillTreeOperationRepository Repository { get; init; }
        public required Guid AccountId { get; init; }
        public required Guid UserId { get; init; }
        public required Guid SessionId { get; init; }
        public required string ServerId { get; init; }
        public required SkillTreeServerPlayerViewEntity View { get; init; }
        public const string Generation = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        public static async Task<Fixture> CreateAsync(bool offlineConfirmed = false)
        {
            var connection = new SqliteConnection("Data Source=:memory:"); await connection.OpenAsync();
            var options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
            var db = new AstralRecordDbContext(options); await db.Database.EnsureCreatedAsync();
            var accountId = Guid.NewGuid(); var userId = Guid.NewGuid(); var session = Guid.NewGuid(); const string server = "skilltree"; var now = DateTime.UtcNow;
            db.Accounts.Add(new AccountEntity { Uuid = accountId, UserId = userId, AccountName = "fixture", SlotIndex = 0, IsActive = true, CreatedAt = now, UpdatedAt = now, CreatedBy = userId, UpdatedBy = userId });
            db.AccountSkillTreeStates.Add(new AccountSkillTreeStateEntity { AccountSkillTreeStateId = Guid.NewGuid(), AccountId = accountId, Version = 1, DefinitionGenerationId = Generation, CreatedAt = now, UpdatedAt = now, CreatedBy = userId, UpdatedBy = userId });
            db.SkillTreeDefinitionGenerations.Add(new SkillTreeDefinitionGenerationEntity { DefinitionGenerationId = Generation, CanonicalSnapshotJson = "{}", CreatedAtUtc = now });
            db.SkillTreeServerRuntimes.Add(new SkillTreeServerRuntimeEntity { ServerId = server, ServerSessionId = session, ServerStartedAtUtc = now, PluginVersion = "test", CompatibilityVersion = "test", DefinitionGenerationId = Generation, Ready = true, LastSeenUtc = now });
            var view = new SkillTreeServerPlayerViewEntity { ServerId = server, AccountId = accountId, ServerSessionId = session, DefinitionGenerationId = Generation, PlayerStateVersion = 1, EvaluationFingerprint = Hash("initial"), EditEligible = true, OfflineConfirmed = offlineConfirmed, ViewJson = "{\"tree\":{},\"points\":{}}", LastSeenUtc = now };
            db.SkillTreeServerPlayerViews.Add(view); await db.SaveChangesAsync();
            return new Fixture { Connection = connection, Db = db, AccountId = accountId, UserId = userId, SessionId = session, ServerId = server, View = view, Repository = new SkillTreeOperationRepository(db, new NetworkRuntimeService(TimeProvider.System)) };
        }
        public SkillTreeOperationCreateRequest Request(Guid operationId) => new() { OperationId = operationId, ActorUserId = UserId, TargetServerId = ServerId, ExpectedDefinitionGenerationId = Generation, ExpectedPlayerStateVersion = 1, Action = "UNLOCK", NodeId = "n" };
        public async ValueTask DisposeAsync() { await Db.DisposeAsync(); await Connection.DisposeAsync(); }
    }
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
}
