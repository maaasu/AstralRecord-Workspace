using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordControl.Agent;
using AstralRecordControl.Contracts;
using AstralRecordControl.Runner;
using AstralRecordControl.Web;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http.Connections;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.SignalR.Client;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace AstralRecordControl.Integration.Tests;

public sealed partial class LiveTransportTests
{
    [Theory]
    [InlineData(HttpTransportType.WebSockets)]
    [InlineData(HttpTransportType.LongPolling)]
    public async Task HttpsAgentInventoryTraversesRealRunnerPipeAndRevocationClosesHub(HttpTransportType transport)
    {
        await using var fixture = await LiveFixture.StartAsync();
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);
        var host = await fixture.OwnerApiAsync<InventoryHost>(owner, HttpMethod.Post, "/api/control/v1/hosts", new HostCreateRequest("dev-pc", "Fixture Dev"), create: true);
        await fixture.AssertMissingEnabledRejectedAsync(owner, host);
        var credential = await fixture.OwnerApiAsync<CredentialIssued>(owner, HttpMethod.Post, $"/api/control/v1/hosts/{host.HostId}/credentials", new { });
        using var agent = fixture.CreateClient();
        agent.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credential.Token);

        // An ordinary TLS client still rejects the fixture certificate: no machine trust is changed.
        using (var ordinary = new HttpClient())
            await Assert.ThrowsAsync<HttpRequestException>(() => ordinary.GetAsync(new Uri(fixture.Origin, "/health/live")));
        Assert.Equal(HttpStatusCode.Unauthorized, (await agent.GetAsync("/api/control/v1/overview")).StatusCode);
        Assert.Equal(HttpStatusCode.Redirect, (await agent.GetAsync("/Security")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await owner.GetAsync("/api/agent/v1/inventory")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await agent.PostAsync(ControlProtocol.UiHubPath + "/negotiate?negotiateVersion=1", null)).StatusCode);
        await using (var cookieAgent = fixture.CreateHub(ControlProtocol.AgentHubPath, transport, cookies: fixture.OwnerCookies))
        {
            var rejected = await Assert.ThrowsAsync<HttpRequestException>(() => cookieAgent.StartAsync());
            Assert.Equal(HttpStatusCode.Unauthorized, rejected.StatusCode);
        }

        var resources = fixture.Resources;
        var pipeName = "control-integration-" + Guid.NewGuid().ToString("N");
        using var identity = WindowsIdentity.GetCurrent();
        var runnerConfiguration = new RunnerConfiguration { HostId = host.HostId, AgentSid = identity.User!.Value, PipeName = pipeName, LocalResources = resources };
        using var runnerStore = new RunnerStore(Path.Combine(fixture.Root, "Runner"), runnerConfiguration);
        using var runner = new RunnerService(runnerConfiguration, new RunnerDispatcher(runnerStore), NullLogger<RunnerService>.Instance);
        await runner.StartAsync(CancellationToken.None);
        try
        {
            var configuration = new AgentConfiguration { HostId = host.HostId, ServerUrl = fixture.Origin.ToString(), RunnerPipeName = pipeName, LocalResources = resources };
            using var agentStore = new AgentStore(Path.Combine(fixture.Root, "Agent"), host.HostId);
            var runnerClient = new RunnerClient(configuration);
            var applier = new InventoryApplier(configuration, agentStore, runnerClient);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(25));
            var snapshot = await runnerClient.GetSnapshotAsync(timeout.Token);
            await using var hub = fixture.CreateHub(ControlProtocol.AgentHubPath, transport, credential.Token);
            var inventoryChanged = new TaskCompletionSource<InventoryChanged>(TaskCreationOptions.RunContinuationsAsynchronously);
            hub.On<JsonElement>("InventoryChanged", value => inventoryChanged.TrySetResult(ControlJson.Deserialize<InventoryChanged>(value.GetRawText())));
            var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            hub.Closed += _ => { closed.TrySetResult(); return Task.CompletedTask; };
            await hub.StartAsync(timeout.Token);
            var hello = await Invoke<HelloAck>(hub, "Hello", new AgentHello
            {
                AgentVersion = "integration-fixture", AgentStoreId = agentStore.GetState().StoreId,
                RunnerEpoch = snapshot.RunnerEpoch, LocalResources = resources
            }, timeout.Token);
            Assert.NotEqual(Guid.Empty, hello.ConnectionEpoch);
            agent.DefaultRequestHeaders.Add("X-Control-Connection-Epoch", hello.ConnectionEpoch.ToString());

            var serverId = Guid.NewGuid();
            var config = new ServerConfig
            {
                ServerId = serverId, HostId = host.HostId, Revision = 1, Kind = ServerKind.Java,
                ManagedRootId = resources.Roots.Single().Id, ExecutableId = resources.Executables.Single().Id,
                ServerDirectory = "dev", ArgumentList = ["-jar", "fixture.jar", "nogui"], ListenPort = 25565
            };
            await fixture.OwnerApiAsync<InventoryServer>(owner, HttpMethod.Post, "/api/control/v1/servers", new { hostId = host.HostId, serverKey = "dev", displayName = "Dev", config }, create: true);
            var changed = await inventoryChanged.Task.WaitAsync(timeout.Token);
            Assert.Equal(hello.ConnectionEpoch, changed.ConnectionEpoch);
            var reconcile = await Invoke<ReconcileAck>(hub, "Reconcile", new ReconcileRequest
            { ConnectionEpoch = hello.ConnectionEpoch, RunnerEpoch = snapshot.RunnerEpoch, AppliedInventoryRevision = 0 }, timeout.Token);
            Assert.True(reconcile.InventoryRequired);
            Assert.Empty(reconcile.OperationIds);

            var firstEnvelope = await ApplyAndAck(agent, hub, hello.ConnectionEpoch, applier, agentStore, runnerClient, timeout.Token);
            snapshot = await runnerClient.GetSnapshotAsync(timeout.Token);
            var firstServer = Assert.Single(snapshot.Servers);
            Assert.Equal(serverId, firstServer.ServerId);
            Assert.Equal(ProcessState.Stopped, firstServer.ProcessState);
            await Heartbeat(hub, hello.ConnectionEpoch, snapshot, timeout.Token);
            await AssertApplied(owner, host.HostId, serverId, firstEnvelope.Revision, 1);

            // A real second definition must advance Runner stateRevision before its next heartbeat.
            var next = config with { Revision = 2, ListenPort = 25566 };
            await fixture.OwnerApiAsync<InventoryServer>(owner, HttpMethod.Put, $"/api/control/v1/servers/{serverId}", new { displayName = "Dev updated", config = next }, match: 1);
            var secondEnvelope = await ApplyAndAck(agent, hub, hello.ConnectionEpoch, applier, agentStore, runnerClient, timeout.Token);
            Assert.True(secondEnvelope.Revision > firstEnvelope.Revision);
            snapshot = await runnerClient.GetSnapshotAsync(timeout.Token);
            var updated = Assert.Single(snapshot.Servers);
            Assert.Equal(2, updated.ConfigRevision);
            Assert.True(updated.StateRevision > firstServer.StateRevision);
            await Heartbeat(hub, hello.ConnectionEpoch, snapshot, timeout.Token);
            await AssertApplied(owner, host.HostId, serverId, secondEnvelope.Revision, 2);

            await fixture.OwnerApiAsync<object>(owner, HttpMethod.Delete, $"/api/control/v1/hosts/{host.HostId}/credentials/{credential.CredentialId}", null);
            await closed.Task.WaitAsync(timeout.Token);
            Assert.Equal(HubConnectionState.Disconnected, hub.State);
            Assert.Equal(HttpStatusCode.Unauthorized, (await agent.GetAsync("/api/agent/v1/inventory", timeout.Token)).StatusCode);
            await using var revoked = fixture.CreateHub(ControlProtocol.AgentHubPath, transport, credential.Token);
            var rejected = await Assert.ThrowsAsync<HttpRequestException>(() => revoked.StartAsync(timeout.Token));
            Assert.Equal(HttpStatusCode.Unauthorized, rejected.StatusCode);
        }
        finally { await runner.StopAsync(CancellationToken.None); }
    }

    [Fact]
    public async Task WebSocketUpgradeRejectsDifferentOriginWithValidAgentToken()
    {
        await using var fixture = await LiveFixture.StartAsync();
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);
        var host = await fixture.OwnerApiAsync<InventoryHost>(owner, HttpMethod.Post, "/api/control/v1/hosts", new HostCreateRequest("origin-pc", "Origin fixture"), create: true);
        var credential = await fixture.OwnerApiAsync<CredentialIssued>(owner, HttpMethod.Post, $"/api/control/v1/hosts/{host.HostId}/credentials", new { });
        await using var hub = fixture.CreateHub(ControlProtocol.AgentHubPath, HttpTransportType.WebSockets, credential.Token, origin: "https://untrusted.example");
        var rejected = await Record.ExceptionAsync(() => hub.StartAsync());
        Assert.NotNull(rejected);
        Assert.Contains("403", rejected.ToString());
        Assert.Equal(HubConnectionState.Disconnected, hub.State);
    }

    private static async Task<InventoryEnvelope> ApplyAndAck(HttpClient agent, HubConnection hub, Guid epoch, InventoryApplier applier, AgentStore store, RunnerClient runner, CancellationToken cancellationToken)
    {
        var envelope = ControlJson.Deserialize<InventoryEnvelope>(await agent.GetStringAsync("/api/agent/v1/inventory", cancellationToken));
        await applier.ApplyAsync(envelope, cancellationToken);
        Assert.Equal(envelope.Revision, (await runner.GetSnapshotAsync(cancellationToken)).AppliedInventoryRevision);
        Assert.Equal(envelope.Revision, store.GetState().AppliedInventoryRevision);
        Assert.Equal(envelope.Sha256, store.GetState().InventorySha256);
        Assert.True(await Invoke<bool>(hub, "InventoryApplied", new InventoryApplied(epoch, envelope.Revision, envelope.Sha256, true), cancellationToken));
        var events = store.ReadEvents();
        if (events.Length > 0)
        {
            var ack = await Invoke<EventAck>(hub, "PublishEvents", new EventBatch(epoch, events), cancellationToken);
            store.Acknowledge(ack.AckEventSeq);
            Assert.Empty(store.ReadEvents());
        }
        return envelope;
    }
    private static async Task Heartbeat(HubConnection hub, Guid epoch, RunnerSnapshot snapshot, CancellationToken cancellationToken)
        => Assert.True(await Invoke<bool>(hub, "Heartbeat", new AgentHeartbeat { ConnectionEpoch = epoch, RunnerEpoch = snapshot.RunnerEpoch, RunnerAvailable = true, ObservedAtUtc = DateTimeOffset.UtcNow, ServerSnapshots = snapshot.Servers }, cancellationToken));
    private static async Task AssertApplied(HttpClient owner, Guid hostId, Guid serverId, long inventoryRevision, long configRevision)
    {
        var host = ControlJson.Deserialize<InventoryHost>(await owner.GetStringAsync($"/api/control/v1/hosts/{hostId}"));
        var server = ControlJson.Deserialize<InventoryServer>(await owner.GetStringAsync($"/api/control/v1/servers/{serverId}"));
        Assert.Equal(inventoryRevision, host.AppliedInventoryRevision);
        Assert.Equal(HostConnectionState.Online, host.ConnectionState);
        Assert.Equal(configRevision, server.AppliedRevision);
    }
    private static async Task<T> Invoke<T>(HubConnection hub, string method, object request, CancellationToken token)
        => ControlJson.Deserialize<T>((await hub.InvokeAsync<JsonElement>(method, request, token)).GetRawText());

    private sealed class LiveFixture : IAsyncDisposable
    {
        private const string Password = "Integration-Owner-Password1!";
        private readonly WebApplication app;
        private readonly string thumbprint;
        public string Root { get; }
        public Uri Origin { get; }
        public CookieContainer OwnerCookies { get; } = new();
        public HostResources Resources { get; }
        private LiveFixture(WebApplication app, string root, Uri origin, string thumbprint, HostResources resources)
        { this.app = app; Root = root; Origin = origin; this.thumbprint = thumbprint; Resources = resources; }

        public static async Task<LiveFixture> StartAsync(bool enableProcessControl = false, bool enableFileControl = false)
        {
            var root = Path.Combine(Path.GetTempPath(), "control-live-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(root);
            using var rsa = RSA.Create(2048);
            var request = new CertificateRequest("CN=localhost", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            var names = new SubjectAlternativeNameBuilder(); names.AddDnsName("localhost"); names.AddIpAddress(IPAddress.Loopback);
            request.CertificateExtensions.Add(names.Build());
            using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddHours(1));
            var certificatePassword = Convert.ToBase64String(RandomNumberGenerator.GetBytes(24));
            var certificatePath = Path.Combine(root, "fixture.pfx");
            await File.WriteAllBytesAsync(certificatePath, certificate.Export(X509ContentType.Pfx, certificatePassword));
            using var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
            var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
            var origin = new Uri($"https://localhost:{port}");
            var contentRoot = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../../src/AstralRecordControl.Web"));
            var app = ControlApplication.Build([
                "--environment=Testing", "--applicationName=AstralRecordControl.Web", "--contentRoot=" + contentRoot,
                "--urls=https://127.0.0.1:" + port, "--Control:DataRoot=" + Path.Combine(root, "Web"), "--Control:PublicOrigin=" + origin.GetLeftPart(UriPartial.Authority),
                "--Control:EnableProcessControl=" + enableProcessControl,
                "--Control:EnableFileControl=" + enableFileControl,
                "--Kestrel:Certificates:Default:Path=" + certificatePath, "--Kestrel:Certificates:Default:Password=" + certificatePassword,
                "--Logging:LogLevel:Default=Warning", "--Logging:LogLevel:Microsoft.AspNetCore.Hosting.Diagnostics=Error"
            ]);
            var managedRoot = Path.Combine(root, "managed"); Directory.CreateDirectory(Path.Combine(managedRoot, "dev"));
            var executable = enableProcessControl ? Environment.ProcessPath! : Path.Combine(root, "fixture-java.exe");
            if (enableProcessControl)
                executable = Path.Combine(System.Runtime.InteropServices.RuntimeEnvironment.GetRuntimeDirectory(), "../../..", "dotnet.exe");
            else await File.WriteAllTextAsync(executable, "fixture only; never executed");
            executable = Path.GetFullPath(executable);
            await File.WriteAllTextAsync(Path.Combine(managedRoot, "dev", "fixture.jar"), "fixture only; never executed");
            var resources = new HostResources
            {
                Roots = [new ManagedRoot { Id = Guid.NewGuid(), DisplayName = "Fixture root", Path = managedRoot }],
                Executables = [new ManagedExecutable { Id = Guid.NewGuid(), DisplayName = "Fixture Java", Path = executable, Kind = ServerKind.Java }]
            };
            if (enableFileControl)
            {
                var backupRoot = Path.Combine(root, "backup-destination"); Directory.CreateDirectory(backupRoot);
                resources = resources with { Roots = [.. resources.Roots, new ManagedRoot { Id = Guid.NewGuid(), DisplayName = "Backup fixture", Path = backupRoot, BackupOnly = true }] };
            }
            try
            {
                await app.StartAsync();
                using var scope = app.Services.CreateScope();
                Assert.True((await scope.ServiceProvider.GetRequiredService<OwnerAuthService>().CreateAsync("owner", Password)).Succeeded);
                return new LiveFixture(app, root, origin, certificate.Thumbprint, resources);
            }
            catch { await app.DisposeAsync(); SqliteConnection.ClearAllPools(); Directory.Delete(root, true); throw; }
        }

        public HttpClient CreateClient(CookieContainer? cookies = null)
        {
            var handler = new HttpClientHandler { AllowAutoRedirect = false, CookieContainer = cookies ?? new CookieContainer() };
            handler.ServerCertificateCustomValidationCallback = (request, certificate, _, _) => request.RequestUri?.Host == "localhost" && certificate?.Thumbprint == thumbprint;
            return new HttpClient(handler) { BaseAddress = Origin, Timeout = TimeSpan.FromSeconds(15) };
        }
        public HubConnection CreateHub(string path, HttpTransportType transport, string? token = null, CookieContainer? cookies = null, string? origin = null)
            => new HubConnectionBuilder().WithUrl(new Uri(Origin, path), options =>
            {
                options.Transports = transport;
                options.Headers["Origin"] = origin ?? Origin.GetLeftPart(UriPartial.Authority);
                options.Cookies = cookies ?? new CookieContainer();
                options.AccessTokenProvider = () => Task.FromResult(token);
                options.HttpMessageHandlerFactory = handler =>
                {
                    var client = Assert.IsType<HttpClientHandler>(handler);
                    client.AllowAutoRedirect = false;
                    client.ServerCertificateCustomValidationCallback = (request, certificate, _, _) => request.RequestUri?.Host == "localhost" && certificate?.Thumbprint == thumbprint;
                    return client;
                };
                options.WebSocketConfiguration = socket => socket.RemoteCertificateValidationCallback = (_, certificate, _, _) => certificate?.GetCertHashString() == thumbprint;
            }).AddJsonProtocol(options => options.PayloadSerializerOptions = ControlJson.CreateOptions()).Build();

        public async Task LoginOwnerAsync(HttpClient owner)
        {
            var response = await Form(owner, "/Login", new() { ["UserName"] = "owner", ["Password"] = Password });
            Assert.Equal("/Mfa/Enroll", response.Headers.Location?.OriginalString);
            await owner.GetStringAsync("/Mfa/Enroll");
            using var scope = app.Services.CreateScope();
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ControlUser>>();
            var key = (await users.GetAuthenticatorKeyAsync((await users.FindByNameAsync("owner"))!))!;
            Assert.Equal(HttpStatusCode.OK, (await Form(owner, "/Mfa/Enroll", new() { ["Code"] = Totp(key) })).StatusCode);
        }
        public async Task<CliTokenIssued> IssueCliAsync(HttpClient cli)
        {
            using var scope = app.Services.CreateScope();
            var users = scope.ServiceProvider.GetRequiredService<UserManager<ControlUser>>();
            var key = (await users.GetAuthenticatorKeyAsync((await users.FindByNameAsync("owner"))!))!;
            using var response = await cli.PostAsync("/api/control/v1/auth/cli-token", new StringContent(ControlJson.Serialize(new CliLoginRequest("owner", Password, Totp(key))), Encoding.UTF8, "application/json"));
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            return ControlJson.Deserialize<CliTokenIssued>(await response.Content.ReadAsStringAsync());
        }
        public async Task<T> OwnerApiAsync<T>(HttpClient owner, HttpMethod method, string path, object? body, bool create = false, long? match = null, HttpStatusCode? expectedStatus = null, Guid? requestId = null)
        {
            var csrf = Token(await owner.GetStringAsync("/Security"));
            using var request = new HttpRequestMessage(method, path);
            request.Headers.Add("Origin", Origin.GetLeftPart(UriPartial.Authority));
            request.Headers.Add("X-CSRF-TOKEN", csrf);
            if (create) { request.Headers.Add("If-None-Match", "*"); request.Headers.Add("Idempotency-Key", (requestId ?? Guid.NewGuid()).ToString()); }
            if (match.HasValue) request.Headers.Add("If-Match", $"\"{match}\"");
            if (body is not null) request.Content = new StringContent(JsonSerializer.Serialize(body, body.GetType(), ControlJson.CreateOptions()), Encoding.UTF8, "application/json");
            using var response = await owner.SendAsync(request);
            var expected = expectedStatus ?? (method == HttpMethod.Post ? HttpStatusCode.Created : method == HttpMethod.Delete ? HttpStatusCode.NoContent : HttpStatusCode.OK);
            Assert.True(expected == response.StatusCode, $"{method} {path}: expected {expected}, received {response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
            return response.StatusCode == HttpStatusCode.NoContent ? default! : ControlJson.Deserialize<T>(await response.Content.ReadAsStringAsync());
        }
        public async Task AssertMissingEnabledRejectedAsync(HttpClient owner, InventoryHost host)
        {
            using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/control/v1/hosts/{host.HostId}")
            { Content = new StringContent("{\"displayName\":\"missing enabled\"}", Encoding.UTF8, "application/json") };
            request.Headers.Add("Origin", Origin.GetLeftPart(UriPartial.Authority));
            request.Headers.Add("X-CSRF-TOKEN", Token(await owner.GetStringAsync("/Security")));
            request.Headers.Add("If-Match", $"\"{host.Revision}\"");
            using var response = await owner.SendAsync(request);
            Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
            var current = ControlJson.Deserialize<InventoryHost>(await owner.GetStringAsync($"/api/control/v1/hosts/{host.HostId}"));
            Assert.True(current.Enabled);
            Assert.Equal(host.Revision, current.Revision);
        }
        private async Task<HttpResponseMessage> Form(HttpClient client, string path, Dictionary<string, string> values)
        {
            values["__RequestVerificationToken"] = Token(await client.GetStringAsync(path));
            using var request = new HttpRequestMessage(HttpMethod.Post, path) { Content = new FormUrlEncodedContent(values) };
            request.Headers.Add("Origin", Origin.GetLeftPart(UriPartial.Authority));
            return await client.SendAsync(request);
        }
        private static string Token(string html) => WebUtility.HtmlDecode(Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
        private static string Totp(string key)
        {
            const string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
            var bytes = new List<byte>(); var buffer = 0; var bits = 0;
            foreach (var character in key) { buffer = (buffer << 5) | alphabet.IndexOf(character); bits += 5; if (bits >= 8) { bits -= 8; bytes.Add((byte)(buffer >> bits)); } }
            var step = BitConverter.GetBytes(DateTimeOffset.UtcNow.ToUnixTimeSeconds() / 30); if (BitConverter.IsLittleEndian) Array.Reverse(step);
            var hash = HMACSHA1.HashData(bytes.ToArray(), step); var offset = hash[^1] & 15;
            var value = ((hash[offset] & 127) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3];
            return (value % 1000000).ToString("D6");
        }
        public async ValueTask DisposeAsync()
        {
            await app.StopAsync(); await app.DisposeAsync(); SqliteConnection.ClearAllPools(); Directory.Delete(Root, true);
        }
    }
}
