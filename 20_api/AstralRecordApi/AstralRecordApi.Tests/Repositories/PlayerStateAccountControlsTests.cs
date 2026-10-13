using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed partial class PlayerStateSnapshotRepositoryTests
{
    [Theory]
    [InlineData(0)]
    [InlineData(2)]
    public async Task AccountControls_SaveAndReplayAreAccountOwnedAndIdempotent(byte mode)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        fixture.DbContext.Accounts.Single().Mode = mode;
        await fixture.DbContext.SaveChangesAsync();
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);
        var request = ControlsRequest(fixture, fixture.AccountId, 0, 7, -123.5f, 34.25f);
        var saved = await repository.SaveAsync(request);
        var replay = await repository.SaveAsync(request);
        Assert.True(saved.Succeeded);
        Assert.True(replay.Succeeded);
        Assert.Equal(1, fixture.DbContext.Accounts.Single().ControlsVersion);
        Assert.Equal(7, fixture.DbContext.Accounts.Single().SelectedHotbarSlot);
        Assert.Equal(-123.5f, fixture.DbContext.Accounts.Single().ViewYaw);
        Assert.Equal(34.25f, fixture.DbContext.Accounts.Single().ViewPitch);
        Assert.Equal(saved.Ack!.AccountControls!.Value.GetRawText(), replay.Ack!.AccountControls!.Value.GetRawText());
        Assert.Equal(1, saved.Ack.AccountControls.Value.GetProperty("version").GetInt32());
        var stale = await repository.SaveAsync(ControlsRequest(fixture, fixture.AccountId, 0, 1, 0, 0));
        Assert.Equal(PlayerStateSnapshotSaveFailure.Conflict, stale.Failure);
        Assert.Equal(7, fixture.DbContext.Accounts.Single().SelectedHotbarSlot);
    }

    [Theory]
    [InlineData(-1, 0, 0)]
    [InlineData(9, 0, 0)]
    [InlineData(0, 180, 0)]
    [InlineData(0, -181, 0)]
    [InlineData(0, 0, 91)]
    public async Task AccountControls_InvalidValuesNeverCommit(int slot, float yaw, float pitch)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext)
            .SaveAsync(ControlsRequest(fixture, fixture.AccountId, 0, slot, yaw, pitch));
        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid, result.Failure);
        Assert.Equal(0, fixture.DbContext.Accounts.Single().ControlsVersion);
        Assert.Empty(fixture.DbContext.PlayerStateSnapshots);
    }

    [Fact]
    public async Task AccountControls_OwnerMismatchAndLaterSectionConflictRollbackTheWholeSnapshot()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var repository = new PlayerStateSnapshotRepository(fixture.DbContext);
        Assert.Equal(PlayerStateSnapshotSaveFailure.Invalid,
            (await repository.SaveAsync(ControlsRequest(fixture, Guid.NewGuid(), 0, 1, 0, 0))).Failure);
        var result = await repository.SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            AccountControls = ControlsRequest(fixture, fixture.AccountId, 0, 6, 45, 20).AccountControls,
            PlayerSettings = JsonSerializer.SerializeToElement(new { userId = Guid.NewGuid(), settings = Array.Empty<object>() }),
        });
        Assert.False(result.Succeeded);
        fixture.DbContext.ChangeTracker.Clear();
        Assert.Equal(0, fixture.DbContext.Accounts.Single().ControlsVersion);
        Assert.Equal(0, fixture.DbContext.Accounts.Single().SelectedHotbarSlot);
        Assert.Empty(fixture.DbContext.PlayerStateSnapshots);
    }

    [Fact]
    public async Task AccountControls_MissingSectionPreservesValuesAndLegacyHashShape()
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var account = fixture.DbContext.Accounts.Single();
        account.ControlsVersion = 3; account.SelectedHotbarSlot = 4; account.ViewYaw = 45;
        await fixture.DbContext.SaveChangesAsync();
        var request = new PlayerStateSnapshotSaveRequest { SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId };
        Assert.DoesNotContain("accountControls", JsonSerializer.Serialize(request, new JsonSerializerOptions(JsonSerializerDefaults.Web)));
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(request);
        Assert.True(result.Succeeded);
        Assert.Null(result.Ack!.AccountControls);
        Assert.Equal(3, account.ControlsVersion);
        Assert.Equal(4, account.SelectedHotbarSlot);
        Assert.Equal(45f, account.ViewYaw);
    }

    private static PlayerStateSnapshotSaveRequest ControlsRequest(SnapshotFixture fixture, Guid owner, int version, int slot, float yaw, float pitch) => new()
    {
        SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
        AccountControls = JsonSerializer.SerializeToElement(new { accountId = owner, expectedVersion = version, selectedHotbarSlot = slot, viewYaw = yaw, viewPitch = pitch }),
    };
}
