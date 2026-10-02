using AstralRecordApi.Utilities;
using Xunit;

namespace AstralRecordApi.Tests.Utilities;

public class PlayerAdminLevelExperienceTests
{
    // Values captured from the Plugin AccountService formula for both account UUIDs.
    public static TheoryData<string, int, long, int> PluginVectors => new()
    {
        { "00000000-0000-0000-0000-000000000001", 1, 0, 707 },
        { "00000000-0000-0000-0000-000000000001", 2, 707, 1120 },
        { "00000000-0000-0000-0000-000000000001", 49, 4701267, 292219 },
        { "00000000-0000-0000-0000-000000000001", 50, 4993486, 309583 },
        { "00000000-0000-0000-0000-000000000001", 99, 38761396, 1184789 },
        { "00000000-0000-0000-0000-000000000001", 100, 39946185, 1218073 },
        { "00000000-0000-0000-0000-000000000002", 1, 0, 670 },
        { "00000000-0000-0000-0000-000000000002", 2, 670, 1072 },
        { "00000000-0000-0000-0000-000000000002", 49, 4701065, 292202 },
        { "00000000-0000-0000-0000-000000000002", 50, 4993267, 309556 },
        { "00000000-0000-0000-0000-000000000002", 99, 38760316, 1184664 },
        { "00000000-0000-0000-0000-000000000002", 100, 39944980, 1218192 },
    };

    [Theory]
    [MemberData(nameof(PluginVectors))]
    public void TotalAndNextRequirementMatchPlugin(string accountText, int level, long total, int next)
    {
        var accountId = Guid.Parse(accountText);
        Assert.Equal(total, PlayerAdminLevelExperience.TotalRequired(accountId, level));
        Assert.Equal(next, PlayerAdminLevelExperience.RequiredForNext(accountId, level));
    }

    [Fact]
    public void RejectsLevelsOutsidePluginCap()
    {
        var accountId = Guid.Parse("00000000-0000-0000-0000-000000000001");
        Assert.Throws<ArgumentOutOfRangeException>(() => PlayerAdminLevelExperience.TotalRequired(accountId, 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => PlayerAdminLevelExperience.TotalRequired(accountId, 101));
        Assert.Throws<ArgumentOutOfRangeException>(() => PlayerAdminLevelExperience.RequiredForNext(accountId, 0));
        Assert.Throws<ArgumentOutOfRangeException>(() => PlayerAdminLevelExperience.RequiredForNext(accountId, 101));
    }
}
