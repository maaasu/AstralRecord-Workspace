using AstralRecordApi.Models;
using Xunit;

namespace AstralRecordApi.Tests.Models;

public class AccessControlContractTests
{
    [Theory]
    [InlineData((byte)0, true)]
    [InlineData((byte)1, false)]
    [InlineData((byte)2, true)]
    public void AccountMode_OnlyPlayerAndAdminAreAccepted(byte mode, bool expected)
    {
        Assert.Equal(expected, AccessControlContract.IsValidAccountMode(mode));
    }

    [Theory]
    [InlineData(0, true)]
    [InlineData(10, false)]
    [InlineData(99, true)]
    public void UserPermission_OnlyPlayerAndAdminAreAccepted(int permission, bool expected)
    {
        Assert.Equal(expected, AccessControlContract.IsValidUserPermission(permission));
    }

    [Theory]
    [InlineData("GAME", true)]
    [InlineData("PLAYER", false)]
    [InlineData("ADMIN", true)]
    public void Profile_OnlyGameAndAdminAreAccepted(string profile, bool expected)
    {
        Assert.Equal(expected, AccessControlContract.IsValidProfile(profile));
    }
}
