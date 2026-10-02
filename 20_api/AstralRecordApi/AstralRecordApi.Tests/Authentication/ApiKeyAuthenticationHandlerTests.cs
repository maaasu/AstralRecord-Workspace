using AstralRecordApi.Authentication;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordApi.Tests.Authentication;

public sealed class ApiKeyAuthenticationHandlerTests
{
    [Theory]
    [InlineData(null, false)]
    [InlineData("", false)]
    [InlineData("wrong-secret", false)]
    [InlineData("common-secret", true)]
    public async Task CommonApiKeyRemainsRequiredWithoutRuntimeKeyConfiguration(
        string? provided, bool expectedSuccess)
    {
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(
            new Dictionary<string, string?> { ["ApiKey:Key"] = "common-secret" }).Build();
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddSingleton<IConfiguration>(configuration);
        services.AddAuthentication(ApiKeyAuthenticationHandler.SchemeName)
            .AddScheme<AuthenticationSchemeOptions, ApiKeyAuthenticationHandler>(
                ApiKeyAuthenticationHandler.SchemeName, _ => { });
        using var provider = services.BuildServiceProvider();
        await using var scope = provider.CreateAsyncScope();
        var context = new DefaultHttpContext { RequestServices = scope.ServiceProvider };
        if (provided is not null)
            context.Request.Headers[ApiKeyAuthenticationHandler.HeaderName] = provided;

        var result = await context.AuthenticateAsync();

        Assert.Equal(expectedSuccess, result.Succeeded);
        if (!expectedSuccess)
            Assert.NotNull(result.Failure);
    }
}
