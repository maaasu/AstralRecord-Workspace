using System.Security.AccessControl;
using System.Security.Principal;
using AstralRecordControl.Cli;
using AstralRecordControl.Contracts;
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

    [Fact]
    public async Task AgentEnrollmentWritesHostOriginAndTokenInAgentPromptOrder()
    {
        var host = Guid.NewGuid();
        const string token = "fixture-secret-token";
        using var input = new StringWriter();
        await ControlCli.WriteAgentConfigurationInputAsync(input, host, new Uri("https://control.example/"), token);
        Assert.Equal(new[] { host.ToString("D"), "https://control.example/", token },
            input.ToString().Split([Environment.NewLine], StringSplitOptions.RemoveEmptyEntries));
    }

    [Fact]
    public void AgentEnrollmentResolvesInstallerReleasePathWithoutAssumingAgentRootExecutable()
    {
        var image = "\"C:\\Program Files\\AstralRecordControl\\Agent\\release-id\\AstralRecordControl.Agent.exe\" --data-root \"C:\\ProgramData\\AstralRecordControl\\Agent\"";
        var parsed = ControlCli.ParseAgentServiceImagePath(image);
        Assert.EndsWith(@"Agent\release-id\AstralRecordControl.Agent.exe", parsed.AgentPath, StringComparison.OrdinalIgnoreCase);
        Assert.Equal(@"C:\ProgramData\AstralRecordControl\Agent", parsed.DataRoot);
        Assert.Throws<InvalidOperationException>(() => ControlCli.ParseAgentServiceImagePath("C:\\other.exe --data-root C:\\data"));
    }

    [Fact]
    public void AgentEnrollmentAcceptsOnlyMatchingPreparedConfigWithoutCredential()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-enroll-" + Guid.NewGuid().ToString("N"));
        var host = Guid.NewGuid();
        var origin = new Uri("https://control.example/");
        Directory.CreateDirectory(root);
        try
        {
            var credential = ControlCli.ValidateAgentEnrollmentState(root, host, origin);
            Assert.Equal(Path.Combine(root, "credentials", "token.bin"), credential);

            File.WriteAllText(Path.Combine(root, "config.json"), ControlJson.Serialize(new
            {
                hostId = host, serverUrl = origin.AbsoluteUri, tokenFile = "private/agent.bin",
                localResources = new { directoryRoots = Array.Empty<object>() }, enableFileControl = false
            }));
            credential = ControlCli.ValidateAgentEnrollmentState(root, host, origin);
            Assert.Equal(Path.Combine(root, "private", "agent.bin"), credential);
            Assert.Throws<InvalidOperationException>(() => ControlCli.ValidateAgentEnrollmentState(root, Guid.NewGuid(), origin));
            Assert.Throws<InvalidOperationException>(() => ControlCli.ValidateAgentEnrollmentState(root, host, new Uri("https://other.example/")));

            Directory.CreateDirectory(Path.GetDirectoryName(credential)!);
            File.WriteAllText(credential, "existing protected credential fixture");
            Assert.Throws<InvalidOperationException>(() => ControlCli.ValidateAgentEnrollmentState(root, host, origin));
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void ConcurrentAgentEnrollmentsCannotShareOneDataRoot()
    {
        var root = Path.Combine(Path.GetTempPath(), "control-enroll-lock-" + Guid.NewGuid().ToString("N"));
        try
        {
            using (ControlCli.AcquireAgentEnrollmentLock(root))
                Assert.Throws<IOException>(() => ControlCli.AcquireAgentEnrollmentLock(root));
            using (var retry = ControlCli.AcquireAgentEnrollmentLock(root))
                Assert.True(retry.CanWrite);
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
