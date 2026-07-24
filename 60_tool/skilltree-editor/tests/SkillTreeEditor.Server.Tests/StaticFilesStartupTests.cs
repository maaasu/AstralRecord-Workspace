using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class StaticFilesStartupTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), $"skilltree-static-tests-{Guid.NewGuid():N}");

    [Fact]
    public async Task MinimalServerStartsAndServesPublishedWwwroot()
    {
        var wwwroot = Path.Combine(_root, "wwwroot");
        Directory.CreateDirectory(Path.Combine(wwwroot, "assets"));
        await File.WriteAllTextAsync(Path.Combine(wwwroot, "index.html"), "<html><body>skilltree-editor</body></html>");
        await File.WriteAllTextAsync(Path.Combine(wwwroot, "assets", "app.js"), "globalThis.editorLoaded = true;");

        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ContentRootPath = _root,
            EnvironmentName = Environments.Development
        });
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        var app = builder.Build();
        var staticRoot = EditorStaticFiles.ResolveRoot(app.Environment);
        Assert.Equal(Path.GetFullPath(wwwroot), staticRoot);
        app.UseEditorStaticFiles(staticRoot!);

        await app.StartAsync();
        try
        {
            var addresses = app.Services
                .GetRequiredService<IServer>()
                .Features
                .Get<IServerAddressesFeature>()
                ?.Addresses;
            var address = Assert.Single(addresses!);
            using var client = new HttpClient { BaseAddress = new Uri(address) };

            Assert.Contains("skilltree-editor", await client.GetStringAsync("/"));
            Assert.Contains("editorLoaded", await client.GetStringAsync("/assets/app.js"));
        }
        finally
        {
            await app.StopAsync();
            await app.DisposeAsync();
        }
    }

    [Fact]
    public async Task ResolverFallsBackToClientDistDuringSourceRun()
    {
        var serverRoot = Path.Combine(_root, "SkillTreeEditor.Server");
        var clientDist = Path.Combine(_root, "SkillTreeEditor.Client", "dist");
        Directory.CreateDirectory(clientDist);
        await File.WriteAllTextAsync(Path.Combine(clientDist, "index.html"), "editor");

        var resolved = EditorStaticFiles.ResolveRoot(serverRoot, Path.Combine(serverRoot, "wwwroot"));

        Assert.Equal(Path.GetFullPath(clientDist), resolved);
    }

    [Fact]
    public async Task ResolverFindsPublishedWwwrootBesideExecutableWhenStartedElsewhere()
    {
        var currentDirectory = Path.Combine(_root, "launch-directory");
        var applicationBase = Path.Combine(_root, "publish");
        var publishedWebRoot = Path.Combine(applicationBase, "wwwroot");
        Directory.CreateDirectory(currentDirectory);
        Directory.CreateDirectory(publishedWebRoot);
        await File.WriteAllTextAsync(Path.Combine(publishedWebRoot, "index.html"), "published editor");

        var resolved = EditorStaticFiles.ResolveRoot(
            currentDirectory,
            Path.Combine(currentDirectory, "wwwroot"),
            applicationBase);

        Assert.Equal(Path.GetFullPath(publishedWebRoot), resolved);
    }

    [Fact]
    public async Task PublishedHostUsesApplicationBaseAsContentRootAndLoadsItsSettings()
    {
        var applicationBase = Path.Combine(_root, "published-host");
        var publishedWebRoot = Path.Combine(applicationBase, "wwwroot");
        Directory.CreateDirectory(publishedWebRoot);
        await File.WriteAllTextAsync(Path.Combine(publishedWebRoot, "index.html"), "published editor");
        await File.WriteAllTextAsync(
            Path.Combine(applicationBase, "appsettings.json"),
            """{"PublishedSetting":"loaded"}""");

        var options = EditorHostOptions.Create([], applicationBase);
        Assert.Equal(Path.GetFullPath(applicationBase), options.ContentRootPath);

        var builder = WebApplication.CreateBuilder(options);
        await using var app = builder.Build();
        Assert.Equal(Path.GetFullPath(applicationBase), app.Environment.ContentRootPath);
        Assert.Equal("loaded", app.Configuration["PublishedSetting"]);
    }

    [Fact]
    public void SourceHostLeavesContentRootUnspecifiedForDefaultProjectResolution()
    {
        var applicationBase = Path.Combine(_root, "source-bin");
        Directory.CreateDirectory(applicationBase);

        var options = EditorHostOptions.Create([], applicationBase);

        Assert.Null(options.ContentRootPath);
    }

    public void Dispose()
    {
        if (Directory.Exists(_root))
            Directory.Delete(_root, recursive: true);
    }
}
