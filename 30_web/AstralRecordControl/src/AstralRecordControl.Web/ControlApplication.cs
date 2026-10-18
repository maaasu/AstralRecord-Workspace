using AstralRecordControl.Contracts;
using Microsoft.AspNetCore.Http.Json;
using Microsoft.Extensions.Options;
using System.Text.Json;

namespace AstralRecordControl.Web;

public static partial class ControlApplication
{
    public static WebApplication Build(string[] args)
    {
        var builder = WebApplication.CreateBuilder(args);
        builder.Services.AddWindowsService(o => o.ServiceName = "AstralRecordControl.Web");
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddRazorPages();
        builder.Services.AddAuthentication();
        builder.Services.AddAuthorization();
        builder.Services.Configure<JsonOptions>(o =>
        {
            var strict = ControlJson.CreateOptions();
            o.SerializerOptions.PropertyNamingPolicy = strict.PropertyNamingPolicy;
            o.SerializerOptions.PropertyNameCaseInsensitive = false;
            o.SerializerOptions.UnmappedMemberHandling = strict.UnmappedMemberHandling;
            o.SerializerOptions.MaxDepth = strict.MaxDepth;
            o.SerializerOptions.Converters.Clear();
            foreach (var converter in strict.Converters) o.SerializerOptions.Converters.Add(converter);
        });
        ConfigureAuthentication(builder);
        ConfigureInventory(builder);
        var app = builder.Build();
        app.UseExceptionHandler(error => error.Run(async context =>
        {
            context.Response.StatusCode = 500;
            await context.Response.WriteAsJsonAsync(new { errorCode = "InternalError", traceId = context.TraceIdentifier });
        }));
        app.Use(async (context, next) =>
        {
            if (context.Request.ContentType?.StartsWith("application/json", StringComparison.OrdinalIgnoreCase) == true)
            {
                if (context.Request.ContentLength > ControlProtocol.MaxMessageBytes) { context.Response.StatusCode = 413; return; }
                context.Request.EnableBuffering(30 * 1024, ControlProtocol.MaxMessageBytes);
                try
                {
                    using var reader = new StreamReader(context.Request.Body, leaveOpen: true);
                    var body = await reader.ReadToEndAsync(context.RequestAborted);
                    if (body.Length > 0) ControlJson.ValidateJson(body);
                    context.Request.Body.Position = 0;
                }
                catch (Exception exception) when (exception is JsonException or IOException)
                {
                    context.Response.StatusCode = 400;
                    await context.Response.WriteAsJsonAsync(new { errorCode = "InvalidJson" });
                    return;
                }
            }
            await next(context);
        });
        app.UseStaticFiles();
        app.UseRouting();
        ConfigureAuthenticationMiddleware(app);
        app.UseAuthentication();
        app.UseAuthorization();
        app.UseAntiforgery();
        MapAuthentication(app);
        MapInventory(app);
        app.MapRazorPages();
        app.MapGet("/health/live", () => Results.Ok(new { status = "live" })).AllowAnonymous();
        return app;
    }
    static partial void ConfigureAuthentication(WebApplicationBuilder builder);
    static partial void ConfigureInventory(WebApplicationBuilder builder);
    static partial void ConfigureAuthenticationMiddleware(WebApplication app);
    static partial void MapAuthentication(WebApplication app);
    static partial void MapInventory(WebApplication app);
}
