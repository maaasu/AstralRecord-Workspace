using System.Net;
using Microsoft.AspNetCore.Http.Features;
using SkillTreeEditor.Server.Endpoints;

namespace SkillTreeEditor.Server.Services;

internal sealed class MobileRequestBoundaryMiddleware(RequestDelegate next, MobileHostOptions host, MobileAuthentication authentication)
{
    public const string SessionCookieName = "__Host-AstralMobile";
    private const int MaximumHandshakeBytes = 2048;

    public async Task InvokeAsync(HttpContext context)
    {
        var request = context.Request;
        var endpoint = context.GetEndpoint();
        var access = endpoint?.Metadata.GetMetadata<MobileEndpointMetadata>();
        var safeMethod = HttpMethods.IsGet(request.Method) || HttpMethods.IsHead(request.Method);
        var handshake = access == MobileEndpointMetadata.Handshake;

        context.Response.OnStarting(static state =>
        {
            var response = (HttpResponse)state;
            response.Headers.CacheControl = "no-store";
            response.Headers["Content-Security-Policy"] = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; "
                + "connect-src 'self'; frame-src 'none'; frame-ancestors 'none'; object-src 'none'; form-action 'self'; base-uri 'self'";
            response.Headers["X-Content-Type-Options"] = "nosniff";
            response.Headers["Referrer-Policy"] = "no-referrer";
            return Task.CompletedTask;
        }, context.Response);

        if (!IsServedByProxy(context)
            || !HasAllowedBrowserMetadata(request, safeMethod, handshake))
        {
            context.Response.StatusCode = StatusCodes.Status403Forbidden;
            return;
        }

        if (handshake)
        {
            if (!request.HasJsonContentType())
            {
                context.Response.StatusCode = StatusCodes.Status415UnsupportedMediaType;
                return;
            }

            if (request.ContentLength > MaximumHandshakeBytes)
            {
                context.Response.StatusCode = StatusCodes.Status413PayloadTooLarge;
                return;
            }

            if (context.Features.Get<IHttpMaxRequestBodySizeFeature>() is { IsReadOnly: false } size)
                size.MaxRequestBodySize = MaximumHandshakeBytes;

            await next(context);
            return;
        }

        if (!authentication.Authorize(request.Cookies[SessionCookieName]))
        {
            context.Response.StatusCode = StatusCodes.Status401Unauthorized;
            return;
        }

        if (access != MobileEndpointMetadata.Edit
            && (!safeMethod || request.Path.StartsWithSegments("/api", StringComparison.OrdinalIgnoreCase)
                || endpoint is not null && access != MobileEndpointMetadata.Page))
        {
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            return;
        }

        if ((request.Path == "/" || request.Path == "/index.html") && request.Query["mobile"] != "android")
        {
            // Always open the touch workspace on this host.
            context.Response.Redirect("/?mobile=android");
            return;
        }

        await next(context);
    }

    private bool IsServedByProxy(HttpContext context)
    {
        var request = context.Request;
        var headers = request.Headers;
        // Tailscale Serve forwards over local HTTP and sets these headers itself.
        // Never use forwarded addresses or identity headers as credentials.
        return context.Connection.RemoteIpAddress is { } peer && IPAddress.IsLoopback(peer)
            && !headers.ContainsKey("Tailscale-Funnel-Request")
            && (host.MatchesPublicHost(request.Host.Value) || host.MatchesLoopbackHost(request.Host.Value))
            && headers["X-Forwarded-Host"] is { Count: 1 } forwardedHost && host.MatchesPublicHost(forwardedHost[0])
            && headers["X-Forwarded-Proto"] is { Count: 1 } protocol && protocol[0] == "https";
    }

    private bool HasAllowedBrowserMetadata(HttpRequest request, bool safeMethod, bool handshake)
    {
        var site = request.Headers["Sec-Fetch-Site"];
        if (site.Count != 0 && (site.Count != 1 || site[0] is not ("same-origin" or "none")))
            return false;

        var origins = request.Headers.Origin;
        if (origins.Count != 0)
            return origins.Count == 1 && host.MatchesOrigin(origins[0]);

        // Native HTTPS handshake requests carry no browser headers. Every browser mutation requires Origin.
        return safeMethod || handshake && !request.Headers.Keys.Any(key =>
            key.StartsWith("Sec-Fetch-", StringComparison.OrdinalIgnoreCase));
    }
}
