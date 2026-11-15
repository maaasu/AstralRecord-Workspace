using Microsoft.Extensions.FileProviders;

namespace SkillTreeEditor.Server.Services;

public static class EditorStaticFiles
{
    public static string? ResolveRoot(IWebHostEnvironment environment)
        => ResolveRoot(environment.ContentRootPath, environment.WebRootPath, AppContext.BaseDirectory);

    public static string? ResolveRoot(
        string contentRootPath,
        string? webRootPath,
        string? applicationBasePath = null)
    {
        var candidates = new[]
        {
            webRootPath,
            string.IsNullOrWhiteSpace(applicationBasePath)
                ? null
                : Path.Combine(applicationBasePath, "wwwroot"),
            Path.Combine(contentRootPath, "wwwroot"),
            Path.GetFullPath(Path.Combine(contentRootPath, "..", "SkillTreeEditor.Client", "dist"))
        };

        return candidates
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Select(path => Path.GetFullPath(path!))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault(path => File.Exists(Path.Combine(path, "index.html")));
    }

    public static void UseEditorStaticFiles(this WebApplication app, string rootPath)
    {
        var provider = new PhysicalFileProvider(Path.GetFullPath(rootPath));
        app.Lifetime.ApplicationStopped.Register(provider.Dispose);
        app.UseDefaultFiles(new DefaultFilesOptions { FileProvider = provider });
        app.UseStaticFiles(new StaticFileOptions { FileProvider = provider });
    }
}
