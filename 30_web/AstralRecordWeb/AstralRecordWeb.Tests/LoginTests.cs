using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed partial class LoginTests
{
    [Fact]
    public async Task Post_WithValidCode_ConsumesChallengeAndIssuesAuthenticationCookie()
    {
        var apiHandler = new WebAuthApiHandler(webAdmin: false);
        await using var factory = new LoginWebApplicationFactory(apiHandler);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            AllowAutoRedirect = false,
            BaseAddress = new Uri("https://localhost"),
            HandleCookies = true,
        });

        var antiforgeryToken = await GetAntiforgeryTokenAsync(client);
        using var postResponse = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = WebUtility.HtmlDecode(antiforgeryToken),
            ["LoginCode"] = "ABCD-EFGH",
        }));
        var authenticationCookieName = factory.Services
            .GetRequiredService<IOptionsMonitor<CookieAuthenticationOptions>>()
            .Get(CookieAuthenticationDefaults.AuthenticationScheme)
            .Cookie.Name;

        Assert.Equal(HttpStatusCode.Found, postResponse.StatusCode);
        Assert.Equal("/MyPage", postResponse.Headers.Location?.OriginalString);
        Assert.Equal(1, apiHandler.ConsumeCallCount);
        Assert.Contains(
            postResponse.Headers.GetValues("Set-Cookie"),
            cookie => cookie.StartsWith($"{authenticationCookieName}=", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Post_WithTrustedBrowserChoice_IssuesTrustedAdminCookie()
    {
        var apiHandler = new WebAuthApiHandler(webAdmin: true);
        await using var factory = new LoginWebApplicationFactory(apiHandler);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            AllowAutoRedirect = false,
            BaseAddress = new Uri("https://localhost"),
            HandleCookies = true,
        });

        var antiforgeryToken = await GetAntiforgeryTokenAsync(client);
        using var postResponse = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = WebUtility.HtmlDecode(antiforgeryToken),
            ["LoginCode"] = "ABCD-EFGH",
            ["TrustBrowser"] = "true",
        }));

        Assert.Equal(HttpStatusCode.Found, postResponse.StatusCode);
        Assert.Contains(
            postResponse.Headers.GetValues("Set-Cookie"),
            cookie => cookie.StartsWith("__Host-AstralRecordTrustedAdmin=", StringComparison.Ordinal)
                && cookie.Contains("httponly", StringComparison.OrdinalIgnoreCase)
                && cookie.Contains("secure", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task AdminPage_WithGamePermission99AndFalseWebAdmin_IsDenied()
    {
        var apiHandler = new WebAuthApiHandler(webAdmin: false, permission: 99);
        await using var factory = new LoginWebApplicationFactory(apiHandler);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            AllowAutoRedirect = false,
            BaseAddress = new Uri("https://localhost"),
            HandleCookies = true,
        });

        var antiforgeryToken = await GetAntiforgeryTokenAsync(client);
        using var loginResponse = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = WebUtility.HtmlDecode(antiforgeryToken),
            ["LoginCode"] = "ABCD-EFGH",
        }));
        Assert.Equal(HttpStatusCode.Found, loginResponse.StatusCode);

        using var adminResponse = await client.GetAsync("/Admin/Items");

        Assert.Equal(HttpStatusCode.Found, adminResponse.StatusCode);
        Assert.Equal("/Login", adminResponse.Headers.Location?.AbsolutePath);
        Assert.Equal(1, apiHandler.AuthorizationCallCount);
    }

    [Fact]
    public async Task AdminPage_WhenAuthorizationApiFails_IsDenied()
    {
        var apiHandler = new WebAuthApiHandler(webAdmin: false, authorizationStatusCode: HttpStatusCode.InternalServerError);
        await using var factory = new LoginWebApplicationFactory(apiHandler);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            AllowAutoRedirect = false,
            BaseAddress = new Uri("https://localhost"),
            HandleCookies = true,
        });

        var antiforgeryToken = await GetAntiforgeryTokenAsync(client);
        using var loginResponse = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = WebUtility.HtmlDecode(antiforgeryToken),
            ["LoginCode"] = "ABCD-EFGH",
        }));
        Assert.Equal(HttpStatusCode.Found, loginResponse.StatusCode);

        using var adminResponse = await client.GetAsync("/Admin/Items");

        Assert.Equal(HttpStatusCode.Found, adminResponse.StatusCode);
        Assert.Equal(1, apiHandler.AuthorizationCallCount);
    }

    [Fact]
    public async Task AdminPage_WithWebAdminTrueAndGamePermission0_IsAllowed()
    {
        var apiHandler = new WebAuthApiHandler(webAdmin: true, permission: 0);
        await using var factory = new LoginWebApplicationFactory(apiHandler);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            AllowAutoRedirect = false,
            BaseAddress = new Uri("https://localhost"),
            HandleCookies = true,
        });

        var antiforgeryToken = await GetAntiforgeryTokenAsync(client);
        using var loginResponse = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = WebUtility.HtmlDecode(antiforgeryToken),
            ["LoginCode"] = "ABCD-EFGH",
        }));
        Assert.Equal(HttpStatusCode.Found, loginResponse.StatusCode);

        using var adminResponse = await client.GetAsync("/Admin/Items");

        Assert.Equal(HttpStatusCode.OK, adminResponse.StatusCode);
        Assert.True(apiHandler.AuthorizationCallCount >= 1);
    }

    private static async Task<string> GetAntiforgeryTokenAsync(HttpClient client)
    {
        var getResponse = await client.GetAsync("/Login");
        getResponse.EnsureSuccessStatusCode();
        var getBody = await getResponse.Content.ReadAsStringAsync();
        var antiforgeryToken = AntiforgeryTokenRegex().Match(getBody).Groups[1].Value;
        Assert.False(string.IsNullOrWhiteSpace(antiforgeryToken));
        return antiforgeryToken;
    }

    [GeneratedRegex("name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"")]
    private static partial Regex AntiforgeryTokenRegex();

    private sealed class LoginWebApplicationFactory(WebAuthApiHandler apiHandler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.ConfigureTestServices(services =>
            {
                services.AddHttpClient<WebAuthApiClient>()
                    .ConfigurePrimaryHttpMessageHandler(() => apiHandler);
                services.AddHttpClient<ItemMasterApiClient>()
                    .ConfigurePrimaryHttpMessageHandler(() => apiHandler);
            });
        }
    }

    private sealed class WebAuthApiHandler(
        bool webAdmin,
        int permission = 0,
        HttpStatusCode authorizationStatusCode = HttpStatusCode.OK) : HttpMessageHandler
    {
        private readonly Guid userUuid = Guid.NewGuid();
        private readonly Guid version = Guid.NewGuid();

        public int ConsumeCallCount { get; private set; }
        public int AuthorizationCallCount { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            if (request.RequestUri?.AbsolutePath == "/api/web-auth/challenges/consume")
            {
                ConsumeCallCount++;
                return Task.FromResult(JsonResponse(HttpStatusCode.OK, new WebLoginChallengeConsumeResponse
                {
                    UserUuid = userUuid,
                    SessionVersion = version,
                    CodeAuthenticatedAt = DateTimeOffset.UtcNow,
                    CodeAuthenticationProof = "fixture-proof",
                    TrustedBrowserToken = webAdmin ? "trusted-browser-token" : null,
                    Mcid = "Tester",
                    Permission = permission,
                    WebAdmin = webAdmin,
                    AccountIds = [],
                }));
            }

            if (request.RequestUri?.AbsolutePath == $"/api/web-auth/users/{userUuid:D}/credentials")
                return Task.FromResult(JsonResponse(HttpStatusCode.OK, new WebCredentialState { SessionVersion = version }));

            if (request.RequestUri?.AbsolutePath == "/api/item")
                return Task.FromResult(JsonResponse(HttpStatusCode.OK, Array.Empty<object>()));

            if (request.RequestUri?.AbsolutePath == $"/api/web-auth/users/{userUuid:D}/authorization")
            {
                AuthorizationCallCount++;
                return Task.FromResult(authorizationStatusCode == HttpStatusCode.OK
                    ? JsonResponse(HttpStatusCode.OK, new WebAuthorizationResponse { WebAdmin = webAdmin })
                    : new HttpResponseMessage(authorizationStatusCode));
            }

            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        }

        private static HttpResponseMessage JsonResponse(HttpStatusCode statusCode, object value) =>
            new(statusCode)
            {
                Content = JsonContent.Create(value, options: new JsonSerializerOptions(JsonSerializerDefaults.Web)),
            };
    }
}
