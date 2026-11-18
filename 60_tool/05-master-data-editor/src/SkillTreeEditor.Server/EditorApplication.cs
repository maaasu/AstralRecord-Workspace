using System.Net;
using Microsoft.AspNetCore.Diagnostics;
using SkillTreeEditor.Server.Endpoints;
using SkillTreeEditor.Server.Options;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server;

public static class EditorApplication
{
    public static WebApplication Create(string[] args)
    {
        var builder = WebApplication.CreateBuilder(EditorHostOptions.Create(args));
        EditorHostOptions.AddLocalSettings(builder, args);

        var mobile = MobileHostOptions.FromArguments(args);
        if (mobile is not null)
        {
            builder.Services.AddMobileAuthentication(mobile);
            // Only the configured local reverse proxy reaches this listener. Ignore Kestrel's
            // normal endpoints and check the forwarding headers in our own boundary.
            builder.Configuration["AllowedHosts"] = "*";
            builder.Configuration["ForwardedHeaders_Enabled"] = "false";
            builder.WebHost.ConfigureKestrel(options =>
            {
                options.Configure(new ConfigurationBuilder().Build());
                options.Listen(IPAddress.Loopback, mobile.LoopbackPort);
                options.AddServerHeader = false;
            });
        }
        else if (!string.IsNullOrEmpty(builder.Configuration["SkillTreeEditor:DesktopSessionToken"]))
        {
            // Desktop always owns a fresh loopback port, regardless of standalone/local Kestrel settings.
            builder.WebHost.ConfigureKestrel(options =>
            {
                options.Configure(new ConfigurationBuilder().Build());
                options.Listen(IPAddress.Loopback, 0);
            });
        }

        builder.Services.Configure<EditorOptions>(builder.Configuration.GetSection(EditorOptions.SectionName));
        builder.Services.AddProblemDetails();
        builder.Services.AddResponseCompression();
        builder.Services.AddSingleton<MasterAnalyticsService>();
        builder.Services.AddSingleton<SpawnerPlanningService>();
        builder.Services.AddSingleton<GatheringSpawnerPlacementService>();
        builder.Services.AddHttpClient<WorldPlacementProxyService>();
        builder.Services.AddSingleton<BalanceLabCatalog>();
        builder.Services.AddSingleton<BalanceLabJobs>();
        builder.Services.AddSingleton<WorkspacePaths>();
        builder.Services.AddSingleton<ToolJobService>();
        builder.Services.AddSingleton<BackupService>();
        builder.Services.AddSingleton<FilebaseRepository>();
        builder.Services.AddSingleton<SchemaCatalog>();
        builder.Services.AddSingleton<ValidationService>();
        builder.Services.AddSingleton<PluginConfigService>();
        builder.Services.AddSingleton<SkillMasterCatalog>();
        builder.Services.AddSingleton<MasterTagCatalog>();
        builder.Services.AddSingleton<WorkspaceMutationGate>();
        builder.Services.AddSingleton<MasterDataPaths>();
        builder.Services.AddSingleton<MasterDataCodec>();
        builder.Services.AddSingleton<FilebaseSnapshotCache>();
        builder.Services.AddHostedService<EditorWarmup>();
        builder.Services.AddSingleton<MasterDataCatalog>();
        builder.Services.AddSingleton<MasterDataValidation>();
        builder.Services.AddSingleton<MasterDataService>();
        builder.Services.Configure<MasterDataAiOptions>(builder.Configuration.GetSection(MasterDataAiOptions.SectionName));
        builder.Services.AddSingleton<MasterDataAiSettingsService>();
        builder.Services.AddSingleton<MasterDataAiService>();
        builder.Services.AddHttpClient<MasterDataAiProvider>(client => client.Timeout = Timeout.InfiniteTimeSpan)
            .ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { AllowAutoRedirect = false });
        builder.Services.AddSingleton<MasterDataFilterIndexService>();
        builder.Services.AddSingleton<ProgressionDesignService>();
        builder.Services.AddSingleton<GameFlowService>();
        builder.Services.AddSingleton<WorkspaceItemTextures>();
        builder.Services.AddHttpClient<MinecraftIconService>((services, client) =>
        {
            var options = services.GetRequiredService<Microsoft.Extensions.Options.IOptions<EditorOptions>>().Value;
            client.BaseAddress = new Uri(options.MinecraftIconsBaseUrl.TrimEnd('/') + "/", UriKind.Absolute);
            client.Timeout = TimeSpan.FromSeconds(15);
            client.DefaultRequestHeaders.UserAgent.ParseAdd("AstralRecord-SkillTreeEditor/1.0");
        });

        var app = builder.Build();
        if (mobile is null)
        {
            app.UseMiddleware<LocalEditorRequestBoundaryMiddleware>();
            app.UseMiddleware<DesktopSessionBoundaryMiddleware>();
            app.UseWhen(context => context.Request.Path.StartsWithSegments("/api/master-data/progression-design"),
                branch => branch.UseResponseCompression());
            app.MapMasterAnalyticsEndpoints();
            app.MapSpawnerPlanningEndpoints();
            app.MapWorldPlacementEndpoints();
            app.MapBalanceLabEndpoints();
            app.MapToolJobEndpoints();
            app.MapMasterDataAiEndpoints();
        }

        app.UseExceptionHandler(errorApp => errorApp.Run(async context =>
        {
            var exception = context.Features.Get<IExceptionHandlerFeature>()?.Error;
            var status = exception switch
            {
                BadHttpRequestException invalidRequest when mobile is not null => invalidRequest.StatusCode,
                ArgumentException => StatusCodes.Status400BadRequest,
                FileNotFoundException => StatusCodes.Status404NotFound,
                DirectoryNotFoundException => StatusCodes.Status404NotFound,
                UnauthorizedAccessException => StatusCodes.Status403Forbidden,
                InvalidOperationException => StatusCodes.Status409Conflict,
                IOException => StatusCodes.Status409Conflict,
                _ => StatusCodes.Status500InternalServerError
            };
            context.Response.StatusCode = status;
            await Results.Problem(
                    statusCode: status,
                    title: mobile is not null || status == StatusCodes.Status500InternalServerError ? "エディタの処理に失敗しました" : $"エディタの処理を実行できませんでした。{exception?.Message}",
                    detail: mobile is null && status == StatusCodes.Status500InternalServerError ? exception?.Message : null)
                .ExecuteAsync(context);
        }));

        if (mobile is not null)
        {
            app.UseRouting();
            app.UseMiddleware<MobileRequestBoundaryMiddleware>();
            app.UseRateLimiter();
            app.MapMobileEndpoints();
        }

        var staticFilesRoot = EditorStaticFiles.ResolveRoot(app.Environment);
        if (staticFilesRoot is not null)
            app.UseEditorStaticFiles(staticFilesRoot);
        app.MapEditorEndpoints();
        app.MapMasterDataEndpoints();
        app.MapFallback(async context =>
        {
            if (mobile is not null && context.Request.Path != "/")
            {
                context.Response.StatusCode = StatusCodes.Status404NotFound;
                return;
            }

            if (staticFilesRoot is null)
            {
                context.Response.StatusCode = StatusCodes.Status404NotFound;
                await context.Response.WriteAsJsonAsync(new
                {
                    message = "エディタの画面がビルドされていません。src/SkillTreeEditor.Client で npm ci と npm run build を実行してください。"
                });
                return;
            }

            context.Response.ContentType = "text/html; charset=utf-8";
            await context.Response.SendFileAsync(Path.Combine(staticFilesRoot, "index.html"));
        }).WithMetadata(MobileEndpointMetadata.Page);

        return app;
    }
}
