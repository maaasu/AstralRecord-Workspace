using System.Net;
using System.Security.Cryptography;

namespace AstralRecordWeb.Services;

public sealed class ResourcePackApiClient(HttpClient httpClient, ILogger<ResourcePackApiClient> logger)
{
    public async Task<IResult> GetJavaAsync(string sha1, HttpContext context, CancellationToken cancellationToken)
    {
        context.Response.Headers.CacheControl = "no-store";
        if (sha1.Length != 40 || sha1.Any(c => c is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            return Results.NotFound();

        try
        {
            using var upstream = await httpClient.GetAsync($"/api/resource-packs/java/{sha1}.zip", cancellationToken);
            if (upstream.StatusCode == HttpStatusCode.NotFound)
                return Results.NotFound();

            upstream.EnsureSuccessStatusCode();
            var archive = await upstream.Content.ReadAsByteArrayAsync(cancellationToken);
            if (Convert.ToHexStringLower(SHA1.HashData(archive)) != sha1)
            {
                logger.LogWarning("The Java resource pack downloaded from the API did not match its SHA-1.");
                return Results.StatusCode(StatusCodes.Status502BadGateway);
            }

            context.Response.Headers.CacheControl = "public, max-age=31536000, immutable";
            return Results.File(archive, "application/zip");
        }
        catch (Exception exception) when (exception is HttpRequestException ||
            exception is OperationCanceledException && !cancellationToken.IsCancellationRequested)
        {
            logger.LogWarning("The Java resource pack could not be downloaded from the API.");
            return Results.StatusCode(StatusCodes.Status502BadGateway);
        }
    }
}
