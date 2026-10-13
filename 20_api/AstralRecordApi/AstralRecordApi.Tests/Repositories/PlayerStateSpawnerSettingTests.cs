using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed partial class PlayerStateSnapshotRepositoryTests
{
    [Theory]
    [InlineData("NORMAL")]
    [InlineData("LIGHT")]
    [InlineData("OFF")]
    public async Task SaveAsync_SpawnerModeIsStoredAndReadByUserId(string mode)
    {
        await using var fixture = await SnapshotFixture.CreateAsync();
        var userId = (await fixture.DbContext.Accounts.SingleAsync()).UserId;
        var settingId = Guid.NewGuid();
        var json = "{\"value\":\"" + mode + "\"}";
        var saved = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            PlayerSettings = Section(new PlayerStatePlayerSettingsSection
            {
                UserId = userId, ClientRevision = 1,
                Settings = [new PlayerStatePlayerSettingSnapshot
                {
                    UserSettingId = settingId, SettingKey = "SPAWNER_VISUAL_MODE", SettingValueJson = json,
                }],
            }),
        });
        Assert.True(saved.Succeeded, saved.Detail);
        fixture.DbContext.ChangeTracker.Clear();
        var setting = Assert.Single(await new PlayerSettingRepository(fixture.DbContext).GetByUserIdAsync(userId));
        Assert.Equal(settingId, setting.UserSettingId);
        Assert.Equal("SPAWNER_VISUAL_MODE", setting.SettingKey);
        Assert.Equal(json, setting.SettingValueJson);
        Assert.Equal(1, setting.Version);
        Assert.Empty(await new PlayerSettingRepository(fixture.DbContext).GetByUserIdAsync(Guid.NewGuid()));
    }
}
