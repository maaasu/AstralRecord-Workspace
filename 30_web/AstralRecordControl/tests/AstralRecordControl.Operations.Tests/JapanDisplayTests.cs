using System.Globalization;
using AstralRecordControl.Web.Pages.Shared;
using Xunit;

namespace AstralRecordControl.Operations.Tests;

public sealed class JapanDisplayTests
{
    [Theory]
    [InlineData("2026-11-01T10:40:43Z", "2026/11/01 19:40:43 JST")]
    [InlineData("2026-12-31T15:00:00Z", "2027/01/01 00:00:00 JST")]
    [InlineData("2026-06-01T00:00:00-04:00", "2026/06/01 13:00:00 JST")]
    [InlineData("2026-11-01T19:40:43+09:00", "2026/11/01 19:40:43 JST")]
    public void TimestampUsesJapanTimeRegardlessOfInputOffsetAndCulture(string input, string expected)
    {
        var previous = CultureInfo.CurrentCulture;
        try
        {
            CultureInfo.CurrentCulture = CultureInfo.GetCultureInfo("ar-SA");
            Assert.Equal(expected, ControlDisplay.Timestamp(DateTimeOffset.Parse(input, CultureInfo.InvariantCulture)));
        }
        finally { CultureInfo.CurrentCulture = previous; }
    }

    [Fact]
    public void MissingTimeAndErrorHaveExplicitEmptyDisplay()
    {
        Assert.Equal("—", ControlDisplay.Timestamp(null));
        Assert.Equal("—", ControlDisplay.ScheduleError(null));
        Assert.Equal("—", ControlDisplay.ScheduleError(""));
        Assert.Equal("実行開始の期限を過ぎました", ControlDisplay.ScheduleError("DeadlineExpired"));
    }
}
