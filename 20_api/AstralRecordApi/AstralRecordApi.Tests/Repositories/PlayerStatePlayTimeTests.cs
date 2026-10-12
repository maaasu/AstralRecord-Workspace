using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed partial class PlayerStateSnapshotRepositoryTests
{
    [Fact]
    public async Task PlayTime_AddsOnceAndReplayPreservesAckAndHash()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var request = PlayTimeRequest(fixture, 42);
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);
        var first = await repository.SaveAsync(request);
        var replay = await repository.SaveAsync(request);
        Assert.True(first.Succeeded);
        Assert.True(replay.Succeeded);
        Assert.Equal(42, fixture.DbContext.Accounts.Single().PlayTimeSeconds);
        Assert.Equal(42, first.Ack!.PlayTime!.Value.GetProperty("totalSeconds").GetInt64());
        Assert.Equal(first.Ack.PlayTime.Value.GetRawText(), replay.Ack!.PlayTime!.Value.GetRawText());
        var collision = await repository.SaveAsync(PlayTimeRequest(fixture, 43, request.SnapshotId));
        Assert.False(collision.Succeeded);
        Assert.Equal(42, fixture.DbContext.Accounts.Single().PlayTimeSeconds);
        var second = await repository.SaveAsync(PlayTimeRequest(fixture, 8));
        Assert.True(second.Succeeded);
        Assert.Equal(50, second.Ack!.PlayTime!.Value.GetProperty("totalSeconds").GetInt64());
    }

    [Theory]
    [InlineData(-1, 0, 0, false)]
    [InlineData(1, 0, long.MaxValue, false)]
    [InlineData(1, 0, 0, true)]
    public async Task PlayTime_RejectsInvalidDeltaOverflowOrDeletedAccount(long delta, byte mode, long total, bool deleted)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var account = fixture.DbContext.Accounts.Single();
        account.Mode = mode; account.PlayTimeSeconds = total; account.IsDeleted = deleted;
        await fixture.DbContext.SaveChangesAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(PlayTimeRequest(fixture, delta));
        Assert.False(result.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(total, fixture.DbContext.Accounts.Single().PlayTimeSeconds);
        Assert.Empty(fixture.DbContext.PlayerStateSnapshots);
    }

    [Theory]
    [InlineData(0, 2)]
    [InlineData(2, 0)]
    [InlineData(2, 2)] // ADMIN→PLAY→ADMINのPLAYER区間が1 snapshotにまとまっても保存する。
    public async Task PlayTime_ModeTransitionPreservesPlayerInterval(byte before, byte after)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        fixture.DbContext.Accounts.Single().Mode = before;
        await fixture.DbContext.SaveChangesAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            PlayTime = JsonSerializer.SerializeToElement(new { accountId = fixture.AccountId, deltaSeconds = 7L }),
            AccountProgress = JsonSerializer.SerializeToElement(new PlayerStateAccountProgressSection
            {
                AccountId = fixture.AccountId, ExpectedProgressVersion = 1, Level = 1, ClassId = "adventurer", ClassLevel = 1, Mode = after,
            }, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        });
        Assert.True(result.Succeeded);
        Assert.Equal(after, fixture.DbContext.Accounts.Single().Mode);
        Assert.Equal(7, fixture.DbContext.Accounts.Single().PlayTimeSeconds);
    }

    [Fact]
    public async Task PlayTime_RejectsAnotherAccountsDeltaWithoutPersistingSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            PlayTime = JsonSerializer.SerializeToElement(new { accountId = Guid.NewGuid(), deltaSeconds = 10L }),
        });
        Assert.False(result.Succeeded);
        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        Assert.Equal(0, fixture.DbContext.Accounts.Single().PlayTimeSeconds);
        Assert.Empty(fixture.DbContext.PlayerStateSnapshots);
    }

    [Fact]
    public async Task PlayTime_LaterSectionFailureRollsBackAndMissingSectionPreservesLegacyRequestHash()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);
        var failed = await repository.SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            PlayTime = JsonSerializer.SerializeToElement(new { accountId = fixture.AccountId, deltaSeconds = 17L }),
            PlayerSettings = JsonSerializer.SerializeToElement(new { userId = Guid.NewGuid(), settings = Array.Empty<object>() }),
        });
        Assert.False(failed.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(0, fixture.DbContext.Accounts.Single().PlayTimeSeconds);
        var legacy = new PlayerStateSnapshotSaveRequest { SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId };
        var json = JsonSerializer.Serialize(legacy, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.DoesNotContain("playTime", json);
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(json))).ToLowerInvariant();
        fixture.DbContext.PlayerStateSnapshots.Add(new PlayerStateSnapshotEntity
        {
            SnapshotId = legacy.SnapshotId, AccountId = legacy.AccountId, RequestHash = hash,
            AckPayloadJson = JsonSerializer.Serialize(new PlayerStateSnapshotAck { SnapshotId = legacy.SnapshotId, AccountId = legacy.AccountId }, new JsonSerializerOptions(JsonSerializerDefaults.Web)),
            CreatedAt = DateTime.UtcNow, CompletedAt = DateTime.UtcNow, CreatedBy = legacy.UpdatedBy,
        });
        await fixture.DbContext.SaveChangesAsync();
        var replay = await repository.SaveAsync(legacy);
        Assert.True(replay.Succeeded);
        Assert.Null(replay.Ack!.PlayTime);
    }

    private static PlayerStateSnapshotSaveRequest PlayTimeRequest(SnapshotFixture fixture, long delta, Guid? snapshotId = null) => new()
    {
        SnapshotId = snapshotId ?? Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
        PlayTime = JsonSerializer.SerializeToElement(new { accountId = fixture.AccountId, deltaSeconds = delta }),
    };
}
