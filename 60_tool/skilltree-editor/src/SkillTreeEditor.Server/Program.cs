using Microsoft.AspNetCore.Diagnostics;
using SkillTreeEditor.Server.Endpoints;
using SkillTreeEditor.Server.Options;
using SkillTreeEditor.Server.Services;

var builder = WebApplication.CreateBuilder(EditorHostOptions.Create(args));

builder.Services.Configure<EditorOptions>(builder.Configuration.GetSection(EditorOptions.SectionName));
builder.Services.AddProblemDetails();
builder.Services.AddSingleton<WorkspacePaths>();
builder.Services.AddSingleton<BackupService>();
builder.Services.AddSingleton<FilebaseRepository>();
builder.Services.AddSingleton<SchemaCatalog>();
builder.Services.AddSingleton<ValidationService>();
builder.Services.AddSingleton<PluginConfigService>();
builder.Services.AddSingleton<SkillMasterCatalog>();
builder.Services.AddSingleton<WorkspaceMutationGate>();
builder.Services.AddHttpClient<MinecraftIconService>((services, client) =>
{
    var options = services.GetRequiredService<Microsoft.Extensions.Options.IOptions<EditorOptions>>().Value;
    client.BaseAddress = new Uri(options.MinecraftIconsBaseUrl.TrimEnd('/') + "/", UriKind.Absolute);
    client.Timeout = TimeSpan.FromSeconds(15);
    client.DefaultRequestHeaders.UserAgent.ParseAdd("AstralRecord-SkillTreeEditor/1.0");
});

var app = builder.Build();
var staticFilesRoot = EditorStaticFiles.ResolveRoot(app.Environment);

app.UseExceptionHandler(errorApp => errorApp.Run(async context =>
{
    var exception = context.Features.Get<IExceptionHandlerFeature>()?.Error;
    var status = exception switch
    {
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
            title: status == StatusCodes.Status500InternalServerError ? "Editor operation failed" : exception?.Message,
            detail: status == StatusCodes.Status500InternalServerError ? exception?.Message : null)
        .ExecuteAsync(context);
}));

if (staticFilesRoot is not null)
    app.UseEditorStaticFiles(staticFilesRoot);
app.MapEditorEndpoints();
app.MapFallback(async context =>
{
    if (staticFilesRoot is null)
    {
        context.Response.StatusCode = StatusCodes.Status404NotFound;
        await context.Response.WriteAsJsonAsync(new
        {
            message = "The editor frontend is not built. Run npm ci and npm run build in src/SkillTreeEditor.Client."
        });
        return;
    }

    context.Response.ContentType = "text/html; charset=utf-8";
    await context.Response.SendFileAsync(Path.Combine(staticFilesRoot, "index.html"));
});

app.Run();

public partial class Program;
