using System.Net;
using System.Net.Http.Json;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace AstralRecordWeb.Tests;

internal static class IsolatedWebDependencies
{
    public static IWebHostBuilder WithIsolatedWebDependencies(this IWebHostBuilder builder)
    {
        builder.ConfigureLogging(logging => logging.ClearProviders());
        builder.ConfigureTestServices(services =>
        {
            services.AddDataProtection().UseEphemeralDataProtectionProvider();
            services.AddHttpClient<DonationApiClient>()
                .ConfigurePrimaryHttpMessageHandler(() => new DefaultDonationHandler());
        });
        return builder;
    }

    private sealed class DefaultDonationHandler : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var response = request.Method == HttpMethod.Get
                && request.RequestUri?.AbsolutePath.EndsWith("/api/donations/admin/pending-count", StringComparison.Ordinal) == true
                    ? new HttpResponseMessage(HttpStatusCode.OK) { Content = JsonContent.Create(new { pendingCount = 0 }) }
                    : new HttpResponseMessage(HttpStatusCode.NotFound);
            return Task.FromResult(response);
        }
    }
}
