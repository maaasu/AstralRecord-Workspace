using System.Threading.RateLimiting;
using Microsoft.AspNetCore.RateLimiting;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Endpoints;

internal static class MobileEditorEndpoints
{
    private const string PairingRate = "mobile-pair";
    private const string ChallengeRate = "mobile-challenge";
    private const string SessionRate = "mobile-session";

    public static void AddMobileAuthentication(this IServiceCollection services, MobileHostOptions options)
    {
        services.AddSingleton(options);
        services.AddSingleton(provider => new MobileDeviceRegistry(provider.GetRequiredService<WorkspacePaths>().WorkspaceRoot));
        services.AddSingleton(provider => new MobileAuthentication(provider.GetRequiredService<MobileDeviceRegistry>(), TimeProvider.System));
        services.AddRateLimiter(limiter =>
        {
            limiter.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            limiter.AddFixedWindowLimiter(PairingRate, rate => SetRate(rate, 10));
            limiter.AddFixedWindowLimiter(ChallengeRate, rate => SetRate(rate, 120));
            limiter.AddFixedWindowLimiter(SessionRate, rate => SetRate(rate, 120));
        });
    }

    private static void SetRate(FixedWindowRateLimiterOptions rate, int permitLimit)
    {
        rate.PermitLimit = permitLimit;
        rate.Window = TimeSpan.FromMinutes(1);
        rate.QueueLimit = 0;
    }

    public static void MapMobileEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var api = endpoints.MapGroup("/api/mobile");
        api.MapPost("/pair", PairAsync)
            .WithMetadata(MobileEndpointMetadata.Handshake)
            .RequireRateLimiting(PairingRate);
        api.MapPost("/challenge", (DeviceRequest request, MobileAuthentication authentication) =>
        {
            if (!MobileAuthentication.IsDeviceId(request.DeviceId))
                return Results.BadRequest();
            var challenge = authentication.IssueChallenge(request.DeviceId!);
            return challenge is null ? Results.Unauthorized() : Results.Ok(new { challenge });
        }).WithMetadata(MobileEndpointMetadata.Handshake).RequireRateLimiting(ChallengeRate);

        api.MapPost("/session", (SessionRequest request, MobileAuthentication authentication) =>
        {
            if (!MobileAuthentication.IsDeviceId(request.DeviceId)
                || !TryDecode(request.Challenge, 44, out var challenge) || challenge.Length != 32
                || !TryDecode(request.Signature, 96, out var signature) || signature.Length is < 8 or > 72)
                return Results.BadRequest();

            var token = authentication.IssueSession(request.DeviceId!, challenge, signature);
            return token is null ? Results.Unauthorized() : Results.Ok(new { token });
        }).WithMetadata(MobileEndpointMetadata.Handshake).RequireRateLimiting(SessionRate);

        api.MapGet("/session", () => Results.NoContent()).AllowMobileEdit();
    }

    private static async Task<IResult> PairAsync(PairRequest request, MobileDeviceRegistry registry, CancellationToken cancellationToken)
    {
        if (request.Code is not { Length: 8 } || !request.Code.All(char.IsAsciiDigit)
            || request.Name is not { Length: > 0 and <= 64 } || request.Name.Any(char.IsControl)
            || string.IsNullOrWhiteSpace(request.Name)
            || MobileAuthentication.NormalizePublicKey(request.PublicKey) is not { } publicKey)
            return Results.BadRequest();

        var device = await registry.PairAsync(request.Code, publicKey, request.Name.Trim(), cancellationToken);
        return device is null ? Results.Unauthorized() : Results.Ok(new { device.DeviceId });
    }

    private static bool TryDecode(string? value, int maxLength, out byte[] bytes)
    {
        bytes = [];
        if (value is not { Length: > 0 } || value.Length > maxLength)
            return false;
        Span<byte> buffer = stackalloc byte[72];
        if (!Convert.TryFromBase64String(value, buffer, out var length))
            return false;
        bytes = buffer[..length].ToArray();
        return true;
    }

    private sealed record PairRequest(string? Code, string? PublicKey, string? Name);
    private sealed record DeviceRequest(string? DeviceId);
    private sealed record SessionRequest(string? DeviceId, string? Challenge, string? Signature);
}

internal sealed class MobileEndpointMetadata
{
    private MobileEndpointMetadata() { }
    public static MobileEndpointMetadata Handshake { get; } = new();
    public static MobileEndpointMetadata Edit { get; } = new();
    public static MobileEndpointMetadata Page { get; } = new();
}

internal static class MobileEndpointConvention
{
    public static RouteHandlerBuilder AllowMobileEdit(this RouteHandlerBuilder route) =>
        route.WithMetadata(MobileEndpointMetadata.Edit);
}
