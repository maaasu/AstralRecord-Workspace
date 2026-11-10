using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class ResourcePackDownloadTests
{
    [Fact]
    public async Task JavaPackAboveThirtyTwoMiBIsServedWithItsVerifiedHash()
    {
        var archive = new byte[33 * 1024 * 1024];
        var sha1 = Convert.ToHexStringLower(SHA1.HashData(archive));
        using var factory = new WebApplicationFactory<Program>().WithWebHostBuilder(builder =>
            builder.WithIsolatedWebDependencies().ConfigureTestServices(services =>
                services.AddHttpClient<ResourcePackApiClient>()
                    .ConfigurePrimaryHttpMessageHandler(() => new PackHandler(archive, sha1))));
        using var upstreamClient = factory.Services.GetRequiredService<IHttpClientFactory>()
            .CreateClient(nameof(ResourcePackApiClient));
        Assert.Equal(250L * 1024 * 1024, upstreamClient.MaxResponseContentBufferSize);

        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri("https://localhost")
        });

        using var response = await client.GetAsync($"/resource-packs/java/{sha1}.zip");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("application/zip", response.Content.Headers.ContentType?.MediaType);
        Assert.Equal(archive.LongLength, response.Content.Headers.ContentLength);
        Assert.Equal("public, max-age=31536000, immutable", response.Headers.GetValues("Cache-Control").Single());
        await using var body = await response.Content.ReadAsStreamAsync();
        Assert.Equal(sha1, Convert.ToHexStringLower(await SHA1.HashDataAsync(body)));
    }

    private sealed class PackHandler(byte[] archive, string sha1) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (request.RequestUri?.AbsolutePath != $"/api/resource-packs/java/{sha1}.zip")
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));

            var content = new ByteArrayContent(archive);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/zip");
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }
}
