using System.Security.AccessControl;
using System.Security.Principal;
using AstralRecordControl.Cli;
using Xunit;

namespace AstralRecordControl.Cli.Tests;

public sealed class CliSecurityTests
{
    [Fact]
    public void ProtectedSessionRoundTripsWithRestrictedAclAndCannotBeReadAsPlaintext()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-cli-" + Guid.NewGuid().ToString("N"));
        var path = Path.Combine(root, "session.bin");
        try
        {
            SecureFiles.Write(path, "sensitive-fixture-token", true);
            Assert.Equal("sensitive-fixture-token", SecureFiles.Read(path, true));
            Assert.DoesNotContain("sensitive-fixture-token", System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(path)));
            var security = new FileInfo(path).GetAccessControl();
            Assert.True(security.AreAccessRulesProtected);
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.WorldSid, null), FileSystemRights.Read, AccessControlType.Allow));
            new FileInfo(path).SetAccessControl(security);
            Assert.Throws<UnauthorizedAccessException>(() => SecureFiles.Read(path, true));
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
    [Theory]
    [InlineData("http://localhost")][InlineData("https://user:secret@example.test")][InlineData("https://example.test/path")][InlineData("https://example.test/?token=secret")]
    public void ConnectionOriginsCannotCarrySecretsOrUntrustedPath(string url) => Assert.Throws<ArgumentException>(() => ControlCli.ValidateOrigin(url));

    [Theory]
    [InlineData("//host/share/file")][InlineData("\\\\host\\share\\file")][InlineData("C:\\safe\\file:stream")]
    public void SessionAndLeaseRejectNetworkAndAlternateDataStreams(string path) => Assert.Throws<ArgumentException>(() => SecureFiles.LocalPath(path));

    [Fact]
    public void CommandParserRejectsDuplicateAndMissingOptions()
    {
        Assert.Throws<ArgumentException>(() => ControlCli.ParseOptions(["--lease-file", "one", "--lease-file", "two"]));
        Assert.Throws<ArgumentException>(() => ControlCli.ParseOptions(["--lease-file"]));
        Assert.Throws<ArgumentException>(() => ControlCli.ParseOptions(["lease-file", "one"]));
    }
    [Fact]
    public void GateSessionMustMatchConfiguredControlOrigin()
    {
        ControlCli.ValidateExpectedOrigin("https://CONTROL.example:443", "https://control.example/");
        Assert.Throws<InvalidOperationException>(() => ControlCli.ValidateExpectedOrigin("https://control.example/", "https://another.example/"));
        Assert.Throws<InvalidOperationException>(() => ControlCli.ValidateExpectedOrigin("https://control.example:8443/", "https://control.example/"));
    }
    [Fact]
    public async Task MalformedServerIdIsRejectedWithoutOpeningRunnerPipe()
        => Assert.Equal(1, await ControlCli.RunAsync(["runner", "inspect", "--server-id", "not-a-guid"]));
}
