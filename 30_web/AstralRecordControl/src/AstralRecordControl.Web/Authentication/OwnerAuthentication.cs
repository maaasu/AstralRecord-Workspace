using System.Security.Claims;
using System.Threading.RateLimiting;
using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Authentication;

public static class OwnerAuthentication
{
    public const string Scheme = "OwnerCookie";
    public const string SessionClaim = "control_session";
    public const string StampClaim = "control_stamp";
    public const string LoginLimit = "OwnerLogin";
    public const string CookieName = "__Host-ControlOwner";

    public static void AddServices(IServiceCollection services, IConfiguration configuration, IHostEnvironment environment)
    {
        var configuredRoot = configuration["Control:DataRoot"];
        if (environment.IsEnvironment("Testing") && string.IsNullOrWhiteSpace(configuredRoot))
            throw new InvalidOperationException("Testing requires an explicit temporary Control:DataRoot.");
        var storage = new ControlStorage(configuredRoot ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AstralRecordControl", "Web"));
        services.AddSingleton(storage);
        services.AddDbContext<ControlDbContext>(o => o.UseSqlite(storage.ConnectionString));
        services.AddSingleton(TimeProvider.System);
        var protection = services.AddDataProtection().SetApplicationName("AstralRecordControl.Web")
            .PersistKeysToFileSystem(new DirectoryInfo(Path.Combine(storage.DataRoot, "keys")));
        if (OperatingSystem.IsWindows()) protection.ProtectKeysWithDpapi(protectToLocalMachine: true);
        else if (!environment.IsEnvironment("Testing")) throw new PlatformNotSupportedException("Control requires Windows key protection.");
        services.AddIdentityCore<ControlUser>(o =>
        {
            o.Password.RequiredLength = 12;
            o.Lockout.MaxFailedAccessAttempts = 10;
            o.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(5);
            o.Lockout.AllowedForNewUsers = true;
        }).AddEntityFrameworkStores<ControlDbContext>().AddSignInManager().AddDefaultTokenProviders();
        services.AddScoped<OwnerAuthService>();
        services.AddSingleton<OwnerSessionConnections>();
        services.AddHostedService<ControlWebLease>();
        services.AddHostedService<OwnerSessionMonitor>();
        services.AddScoped<OwnerCookieEvents>();
        services.AddAuthentication(o => { o.DefaultScheme = Scheme; o.DefaultChallengeScheme = Scheme; })
            .AddCookie(Scheme, o =>
            {
                ConfigureCookie(o, CookieName);
                o.ExpireTimeSpan = TimeSpan.FromHours(8);
                o.EventsType = typeof(OwnerCookieEvents);
            });
        services.AddAuthorization(o =>
        {
            o.AddPolicy("OwnerOnly", p => p.AddAuthenticationSchemes(Scheme).RequireAuthenticatedUser().RequireClaim("amr", "pwd").RequireClaim(SessionClaim));
        });
        services.AddAntiforgery(o => { o.Cookie.Name = "__Host-ControlCsrf"; o.Cookie.SecurePolicy = CookieSecurePolicy.Always; o.HeaderName = "X-CSRF-TOKEN"; });
        services.AddRateLimiter(o =>
        {
            o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            o.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(context =>
                HttpMethods.IsPost(context.Request.Method) && context.Request.Path.StartsWithSegments("/Login")
                ? RateLimitPartition.GetFixedWindowLimiter(context.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new FixedWindowRateLimiterOptions { PermitLimit = 5, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true })
                : RateLimitPartition.GetNoLimiter("other"));
            o.AddPolicy(LoginLimit, context => RateLimitPartition.GetFixedWindowLimiter(context.Connection.RemoteIpAddress?.ToString() ?? "unknown", _ => new FixedWindowRateLimiterOptions
            { PermitLimit = 5, Window = TimeSpan.FromMinutes(1), QueueLimit = 0, AutoReplenishment = true }));
        });
    }

    private static void ConfigureCookie(CookieAuthenticationOptions options, string name)
    {
        options.Cookie.Name = name;
        options.Cookie.HttpOnly = true;
        options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
        options.Cookie.SameSite = SameSiteMode.Lax;
        options.Cookie.Path = "/";
        options.SlidingExpiration = false;
        options.LoginPath = "/Login";
        options.AccessDeniedPath = "/Login";
        options.Events.OnRedirectToLogin = context =>
        {
            if (context.Request.Path.StartsWithSegments("/api") || context.Request.Path.StartsWithSegments("/hubs")) context.Response.StatusCode = 401;
            else context.Response.Redirect(context.RedirectUri);
            return Task.CompletedTask;
        };
        options.Events.OnRedirectToAccessDenied = context => { context.Response.StatusCode = 403; return Task.CompletedTask; };
    }
}

public sealed class OwnerCookieEvents(OwnerAuthService owner) : CookieAuthenticationEvents
{
    public override async Task ValidatePrincipal(CookieValidatePrincipalContext context)
    {
        if (await owner.ValidateSessionAsync(context.Principal) is null)
        {
            context.RejectPrincipal();
            await context.HttpContext.SignOutAsync(OwnerAuthentication.Scheme);
        }
    }
    public override Task RedirectToLogin(RedirectContext<CookieAuthenticationOptions> context)
    {
        if (context.Request.Path.StartsWithSegments("/api") || context.Request.Path.StartsWithSegments("/hubs")) context.Response.StatusCode = 401;
        else context.Response.Redirect(context.RedirectUri);
        return Task.CompletedTask;
    }
    public override Task RedirectToAccessDenied(RedirectContext<CookieAuthenticationOptions> context) { context.Response.StatusCode = 403; return Task.CompletedTask; }
}
