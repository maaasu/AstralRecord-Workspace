using System.Net;
using System.Text.RegularExpressions;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed partial class LoginDisabledTests
{
    [Fact]
    public async Task Post_WithValidAntiforgeryToken_ReturnsUnavailablePageWithoutAuthentication()
    {
        var apiHandler = new TrackingHandler();
        await using var factory = new LoginWebApplicationFactory(apiHandler);
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            AllowAutoRedirect = false,
            BaseAddress = new Uri("https://localhost"),
            HandleCookies = true,
        });

        var getResponse = await client.GetAsync("/Login");
        getResponse.EnsureSuccessStatusCode();
        var getBody = await getResponse.Content.ReadAsStringAsync();
        var antiforgeryToken = AntiforgeryTokenRegex().Match(getBody).Groups[1].Value;
        Assert.False(string.IsNullOrWhiteSpace(antiforgeryToken));

        using var postResponse = await client.PostAsync("/Login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = WebUtility.HtmlDecode(antiforgeryToken),
            ["LoginCode"] = "ABCD-EFGH",
        }));
        var postBody = await postResponse.Content.ReadAsStringAsync();
        var authenticationCookieName = factory.Services
            .GetRequiredService<IOptionsMonitor<CookieAuthenticationOptions>>()
            .Get(CookieAuthenticationDefaults.AuthenticationScheme)
            .Cookie.Name;

        Assert.Equal(HttpStatusCode.OK, postResponse.StatusCode);
        Assert.Contains("Webログインは現在未実装です。", postBody);
        Assert.Contains("ゲーム内でのログインコード発行とは別に", postBody);
        Assert.Equal(0, apiHandler.CallCount);
        Assert.DoesNotContain(
            postResponse.Headers.TryGetValues("Set-Cookie", out var cookies) ? cookies : [],
            cookie => cookie.StartsWith($"{authenticationCookieName}=", StringComparison.Ordinal));
    }

    [GeneratedRegex("name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"")]
    private static partial Regex AntiforgeryTokenRegex();

    private sealed class LoginWebApplicationFactory(TrackingHandler apiHandler) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.ConfigureTestServices(services =>
            {
                services.AddHttpClient<WebAuthApiClient>()
                    .ConfigurePrimaryHttpMessageHandler(() => apiHandler);
            });
        }
    }

    private sealed class TrackingHandler : HttpMessageHandler
    {
        public int CallCount { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            CallCount++;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.InternalServerError));
        }
    }
}
