using System.Security.Cryptography;
using System.Text;

namespace SkillTreeEditor.Server.Services;

/// <summary>Desktop requests are authenticated by the native host, never by JavaScript.</summary>
public sealed class DesktopSessionBoundaryMiddleware
{
    public const string HeaderName = "X-Astral-Desktop-Token";
    private readonly RequestDelegate next;
    private readonly byte[]? expectedToken;

    public DesktopSessionBoundaryMiddleware(RequestDelegate next, IConfiguration configuration)
    {
        this.next = next;
        var token = configuration["SkillTreeEditor:DesktopSessionToken"];
        expectedToken = string.IsNullOrEmpty(token) ? null : Encoding.UTF8.GetBytes(token);
    }

    public async Task InvokeAsync(HttpContext context)
    {
        if (expectedToken is not null)
        {
            var supplied = context.Request.Headers[HeaderName];
            if (supplied.Count != 1 || !CryptographicOperations.FixedTimeEquals(
                    expectedToken, Encoding.UTF8.GetBytes(supplied[0] ?? "")))
            {
                context.Response.StatusCode = StatusCodes.Status403Forbidden;
                await context.Response.WriteAsJsonAsync(new { message = "デスクトップアプリから開いてください。" });
                return;
            }
        }

        await next(context);
    }
}
