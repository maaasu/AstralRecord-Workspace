using AstralRecordControl.Web.Authentication;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    static partial void ConfigureAuthentication(WebApplicationBuilder builder)
    {
        OwnerAuthentication.AddServices(builder.Services, builder.Configuration, builder.Environment);
        builder.Services.AddRazorPages(o =>
        {
            o.Conventions.AuthorizePage("/Security", "OwnerOnly");
            o.Conventions.AuthorizeFolder("/Mfa", "OwnerPending");
        });
    }

    static partial void ConfigureAuthenticationMiddleware(WebApplication app)
    {
        var testing = app.Environment.IsEnvironment("Testing");
        var origin = app.Configuration["Control:PublicOrigin"];
        if (!Uri.TryCreate(origin, UriKind.Absolute, out var uri) || uri.Scheme != "https" || uri.AbsolutePath != "/" || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment) || !string.IsNullOrEmpty(uri.UserInfo))
            throw new InvalidOperationException("Control:PublicOrigin must be a fixed HTTPS origin.");
        var expectedOrigin = uri.GetLeftPart(UriPartial.Authority);
        app.UseRateLimiter();
        app.Use(async (context, next) =>
        {
            if (!context.Request.IsHttps && !testing) { context.Response.StatusCode = 400; return; }
            if (!testing && !string.Equals(context.Request.Host.Value, uri.Authority, StringComparison.OrdinalIgnoreCase)) { context.Response.StatusCode = 400; return; }
            var unsafeMethod = !HttpMethods.IsGet(context.Request.Method) && !HttpMethods.IsHead(context.Request.Method) && !HttpMethods.IsOptions(context.Request.Method);
            var cookieRequest = context.Request.Cookies.ContainsKey(OwnerAuthentication.CookieName) || context.Request.Cookies.ContainsKey("__Host-ControlPending");
            if (context.WebSockets.IsWebSocketRequest || string.Equals(context.Request.Headers.Upgrade, "websocket", StringComparison.OrdinalIgnoreCase) || (unsafeMethod && cookieRequest))
            {
                if (!string.Equals(context.Request.Headers.Origin, expectedOrigin, StringComparison.Ordinal)) { context.Response.StatusCode = 403; return; }
            }
            context.Response.Headers.CacheControl = "no-store";
            await next(context);
        });
    }
    static partial void MapAuthentication(WebApplication app) { }
}
