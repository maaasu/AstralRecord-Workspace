using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class MobileEditorEndpointTests : IDisposable
{
    private const string PublicHost = "pc.tailnet.ts.net";
    private const string PublicOrigin = "https://" + PublicHost;
    private const string FileName = "example/v1.example.yml";
    private const string MasterFileApi = "/api/master-data/file";

    private readonly string _workspace = Path.Combine(Path.GetTempPath(), $"astral-mobile-host-{Guid.NewGuid():N}");

    [Fact]
    public async Task PairSignAndEditRequireTheRightProxyAndOrigin()
    {
        await using var host = await StartAsync();
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Get, "/", proxyHeaders: false));
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Get, "/", requestHost: "other.tailnet.ts.net"));
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Get, "/", forwardedHost: "other.tailnet.ts.net"));
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Get, "/", funnel: true));
        Assert.Equal(HttpStatusCode.Unauthorized, await host.StatusAsync(HttpMethod.Get, "/?mobile=android"));
        Assert.Equal(HttpStatusCode.Unauthorized, await host.StatusAsync(HttpMethod.Get, "/assets/app.js"));
        Assert.Equal(HttpStatusCode.Unauthorized, await host.StatusAsync(HttpMethod.Get, MasterFileApi));

        var code = new MobileDeviceRegistry(_workspace).CreatePairingCode().Code;
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var pair = new { code, publicKey = Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()), name = "Android" };
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Post, "/api/mobile/pair", pair, fetchSite: "same-origin"));
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Post, "/api/mobile/pair", pair, origin: "https://other.tailnet.ts.net"));
        using var pairing = await host.SendAsync(HttpMethod.Post, "/api/mobile/pair", pair, requestHost: host.LoopbackHost);
        Assert.Equal(HttpStatusCode.OK, pairing.StatusCode);
        var deviceId = (await pairing.Content.ReadFromJsonAsync<JsonObject>())!["deviceId"]!.GetValue<string>();
        Assert.Equal(HttpStatusCode.Unauthorized, await host.StatusAsync(HttpMethod.Post, "/api/mobile/pair", pair));
        using var issued = await host.SendAsync(HttpMethod.Post, "/api/mobile/challenge", new { deviceId });
        Assert.Equal(HttpStatusCode.OK, issued.StatusCode);
        var challenge = (await issued.Content.ReadFromJsonAsync<JsonObject>())!["challenge"]!.GetValue<string>();
        var signature = Convert.ToBase64String(MobileAuthenticationTests.Sign(key, Convert.FromBase64String(challenge)));
        var signed = new { deviceId, challenge, signature };
        using var session = await host.SendAsync(HttpMethod.Post, "/api/mobile/session", signed);
        Assert.Equal(HttpStatusCode.OK, session.StatusCode);
        host.Token = (await session.Content.ReadFromJsonAsync<JsonObject>())!["token"]!.GetValue<string>();
        Assert.Equal(HttpStatusCode.Unauthorized, await host.StatusAsync(HttpMethod.Post, "/api/mobile/session", signed));
        Assert.Equal(HttpStatusCode.NoContent, await host.StatusAsync(HttpMethod.Get, "/api/mobile/session"));

        using var page = await host.SendAsync(HttpMethod.Get, "/");
        Assert.Equal(HttpStatusCode.Redirect, page.StatusCode);
        Assert.Equal("/?mobile=android", page.Headers.Location?.OriginalString);
        Assert.Equal(HttpStatusCode.OK, await host.StatusAsync(HttpMethod.Get, "/?mobile=android"));
        using var script = await host.SendAsync(HttpMethod.Get, "/assets/app.js");
        Assert.Equal(HttpStatusCode.OK, script.StatusCode);
        Assert.True(script.Headers.CacheControl?.NoStore);
        Assert.Contains("connect-src 'self'", Assert.Single(script.Headers.GetValues("Content-Security-Policy")));
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Get, "/assets/app.js", fetchSite: "cross-site"));

        using var file = await host.SendAsync(HttpMethod.Get, MasterFileApi + "?path=" + FileName);
        Assert.Equal(HttpStatusCode.OK, file.StatusCode);
        var revision = (await file.Content.ReadFromJsonAsync<JsonObject>())!["revision"]!.GetValue<string>();
        var edit = new { path = FileName, revision, raw = "name: Edited on mobile\n" };
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Put, MasterFileApi, edit));
        Assert.Equal(HttpStatusCode.Forbidden, await host.StatusAsync(HttpMethod.Put, MasterFileApi, edit, origin: PublicOrigin, fetchSite: "same-site"));
        Assert.Equal(HttpStatusCode.OK, await host.StatusAsync(HttpMethod.Put, MasterFileApi, edit, origin: PublicOrigin, fetchSite: "same-origin"));
        Assert.Equal(edit.raw, await File.ReadAllTextAsync(Path.Combine(_workspace, "40_filebase", FileName)));

        foreach (var (method, path) in new[]
        {
            (HttpMethod.Get, "/api/tool-jobs/capabilities"),
            (HttpMethod.Post, "/api/tool-jobs/plans"),
            (HttpMethod.Get, "/api/world-placements/dev"),
            (HttpMethod.Put, "/api/world-placements/dev"),
            (HttpMethod.Get, "/api/spawner-planning/worlds"),
            (HttpMethod.Get, "/api/master-data/ai/settings"),
            (HttpMethod.Get, "/api/master-data/progression-design"),
            (HttpMethod.Get, "/api/master-data/game-flow/context"),
            (HttpMethod.Get, "/api/balance-lab/catalog"),
            (HttpMethod.Get, "/api/metadata"),
            (HttpMethod.Put, "/api/settings"),
            (HttpMethod.Post, "/api/nodes"),
            (HttpMethod.Post, "/api/structures"),
            (HttpMethod.Post, "/api/master-data/catalog"),
        })
        {
            Assert.Equal(HttpStatusCode.NotFound, await host.StatusAsync(method, path, origin: PublicOrigin));
        }
        Assert.Equal(HttpStatusCode.OK, await host.StatusAsync(HttpMethod.Get, "/api/settings"));
        Assert.Equal(HttpStatusCode.OK, await host.StatusAsync(HttpMethod.Get, "/api/structures"));
        Assert.Equal(HttpStatusCode.OK, await host.StatusAsync(HttpMethod.Get, "/api/skills"));

        Assert.True(new MobileDeviceRegistry(_workspace).Revoke(deviceId));
        Assert.Equal(HttpStatusCode.Unauthorized, await host.StatusAsync(HttpMethod.Get, "/?mobile=android"));
        Assert.Equal(HttpStatusCode.Unauthorized, await host.StatusAsync(HttpMethod.Get, MasterFileApi + "?path=" + FileName));
        Assert.Equal(HttpStatusCode.Unauthorized, await host.StatusAsync(HttpMethod.Post, "/api/mobile/challenge", new { deviceId }));
    }

    [Fact]
    public async Task HandshakeChecksBodySizeAndJsonMediaType()
    {
        await using var host = await StartAsync();
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge,
            await host.StatusAsync(HttpMethod.Post, "/api/mobile/pair", new { name = new string('a', 3000) }));
        using var textBody = new StringContent("{}", Encoding.UTF8, "text/plain");
        Assert.Equal(HttpStatusCode.Forbidden,
            await host.StatusAsync(HttpMethod.Post, "/api/mobile/pair", textBody));
    }

    [Fact]
    public async Task PairingAttemptsAreRateLimited()
    {
        await using var host = await StartAsync();
        for (var attempt = 0; attempt < 10; attempt++)
            Assert.Equal(HttpStatusCode.BadRequest, await host.StatusAsync(HttpMethod.Post, "/api/mobile/pair", new { }));
        Assert.Equal(HttpStatusCode.TooManyRequests, await host.StatusAsync(HttpMethod.Post, "/api/mobile/pair", new { }));
    }

    [Fact]
    public async Task DesktopHostDoesNotMapDeviceEnrollmentOrAuthentication()
    {
        await using var desktop = EditorApplication.Create(["--SkillTreeEditor:DesktopSessionToken", "desktop-token"]);
        var endpoints = ((IEndpointRouteBuilder)desktop).DataSources.SelectMany(source => source.Endpoints).OfType<RouteEndpoint>();
        Assert.DoesNotContain(endpoints, endpoint => endpoint.RoutePattern.RawText?.StartsWith("/api/mobile", StringComparison.Ordinal) == true);
    }

    [Theory]
    [InlineData("http://pc.tailnet.ts.net", "5285")]
    [InlineData("https://pc.example", "5285")]
    [InlineData("https://pc.tailnet.ts.net/path", "5285")]
    [InlineData("https://pc.tailnet.ts.net", "0")]
    public void HostRejectsAnInvalidOriginOrPort(string origin, string port)
    {
        Assert.Throws<ArgumentException>(() => MobileHostOptions.FromArguments(
            ["--SkillTreeEditor:MobileOrigin", origin, "--SkillTreeEditor:MobilePort", port]));
    }

    private async Task<RunningEditor> StartAsync()
    {
        var paths = new WorkspacePaths(_workspace);
        var data = Path.Combine(_workspace, "40_filebase", FileName);
        foreach (var directory in new[] { paths.Nodes, paths.Structures, paths.Skills, Path.GetDirectoryName(paths.PluginConfig)!,
                     Path.GetDirectoryName(data)!, Path.Combine(_workspace, "60_tool"), Path.Combine(_workspace, "wwwroot", "assets") })
            Directory.CreateDirectory(directory);
        await File.WriteAllTextAsync(paths.PluginConfig, "skilltree:\n  worldName: world\n  structureId: other\n  center: { x: 0, y: 0, z: 0 }\n");
        await File.WriteAllTextAsync(data, "name: Original\n");
        await File.WriteAllTextAsync(Path.Combine(_workspace, "wwwroot", "index.html"), "<!doctype html><title>Editor</title>");
        await File.WriteAllTextAsync(Path.Combine(_workspace, "wwwroot", "assets", "app.js"), "globalThis.editorLoaded = true;");

        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();

        var app = EditorApplication.Create([
            "--contentRoot", _workspace,
            "--SkillTreeEditor:WorkspaceRoot", _workspace,
            "--SkillTreeEditor:MobileOrigin", PublicOrigin,
            "--SkillTreeEditor:MobilePort", port.ToString(System.Globalization.CultureInfo.InvariantCulture),
            "--Kestrel:Endpoints:Http:Url", "http://0.0.0.0:5274",
        ]);
        await app.StartAsync();
        var address = Assert.Single(app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses);
        Assert.Equal($"http://127.0.0.1:{port}", address);
        return new RunningEditor(app, address);
    }

    public void Dispose()
    {
        var normalizedWorkspace = OperatingSystem.IsWindows() ? _workspace.ToUpperInvariant() : _workspace;
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(normalizedWorkspace)));
        var state = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "AstralRecord", "SkillTreeEditor", "mobile", hash);
        if (Directory.Exists(state)) Directory.Delete(state, recursive: true);
        if (Directory.Exists(_workspace)) Directory.Delete(_workspace, recursive: true);
    }

    private sealed class RunningEditor(WebApplication app, string address) : IAsyncDisposable
    {
        private readonly HttpClient _client = new(new HttpClientHandler { AllowAutoRedirect = false })
        {
            BaseAddress = new Uri(address)
        };

        public string LoopbackHost => _client.BaseAddress!.Authority;
        public string? Token { get; set; }

        public async Task<HttpResponseMessage> SendAsync(HttpMethod method, string path, object? payload = null,
            string? origin = null, string? fetchSite = null, bool proxyHeaders = true, string? requestHost = null,
            string? forwardedHost = null, bool funnel = false)
        {
            using var request = new HttpRequestMessage(method, path);
            request.Content = payload is HttpContent content ? content : payload is null ? null : JsonContent.Create(payload);
            request.Headers.Host = requestHost ?? PublicHost;
            if (origin is not null) request.Headers.Add("Origin", origin);
            if (fetchSite is not null) request.Headers.Add("Sec-Fetch-Site", fetchSite);
            if (proxyHeaders)
            {
                request.Headers.Add("X-Forwarded-Host", forwardedHost ?? PublicHost);
                request.Headers.Add("X-Forwarded-Proto", "https");
            }
            if (funnel) request.Headers.Add("Tailscale-Funnel-Request", "?1");
            if (Token is not null) request.Headers.Add("Cookie", $"__Host-AstralMobile={Token}");
            return await _client.SendAsync(request);
        }

        public async Task<HttpStatusCode> StatusAsync(HttpMethod method, string path, object? payload = null,
            string? origin = null, string? fetchSite = null, bool proxyHeaders = true, string? requestHost = null,
            string? forwardedHost = null, bool funnel = false)
        {
            using var response = await SendAsync(method, path, payload, origin, fetchSite, proxyHeaders, requestHost, forwardedHost, funnel);
            return response.StatusCode;
        }

        public async ValueTask DisposeAsync()
        {
            _client.Dispose();
            await app.StopAsync();
            await app.DisposeAsync();
        }
    }
}
