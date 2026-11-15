using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.Configuration;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class DesktopSessionBoundaryTests
{
    [Fact]
    public void DesktopLoadsSelectedWorkspaceSettingsAndKeepsCommandLinePriority()
    {
        var root = Path.Combine(Path.GetTempPath(), "AstralEditorSettings-" + Guid.NewGuid().ToString("N"));
        var settingsDirectory = Path.Combine(root, "60_tool", "skilltree-editor", "src", "SkillTreeEditor.Server");
        Directory.CreateDirectory(settingsDirectory);
        try
        {
            File.WriteAllText(Path.Combine(settingsDirectory, "appsettings.local.json"), """
                { "SkillTreeEditor": { "WorldPlacementApiBaseUrl": "http://workspace.example/", "DesktopSessionToken": "wrong-local-token" } }
                """);
            string[] args = ["--SkillTreeEditor:WorkspaceRoot", root, "--SkillTreeEditor:DesktopSessionToken", "native-token"];
            var builder = WebApplication.CreateBuilder(EditorHostOptions.Create(args));
            EditorHostOptions.AddLocalSettings(builder, args);

            Assert.Equal("http://workspace.example/", builder.Configuration["SkillTreeEditor:WorldPlacementApiBaseUrl"]);
            Assert.Equal("native-token", builder.Configuration["SkillTreeEditor:DesktopSessionToken"]);
            Assert.Equal(root, builder.Configuration["SkillTreeEditor:WorkspaceRoot"]);
        }
        finally { Directory.Delete(root, recursive: true); }
    }

    [Theory]
    [InlineData(null, null, true)]
    [InlineData("native-secret", null, false)]
    [InlineData("native-secret", "wrong-token", false)]
    [InlineData("native-secret", "native-secret", true)]
    public async Task OnlyNativeRequestsPassWhenDesktopModeIsEnabled(string? expected, string? supplied, bool allowed)
    {
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["SkillTreeEditor:DesktopSessionToken"] = expected
        }).Build();
        var called = false;
        var middleware = new DesktopSessionBoundaryMiddleware(_ => { called = true; return Task.CompletedTask; }, configuration);
        var context = new DefaultHttpContext();
        context.Response.Body = new MemoryStream();
        if (supplied is not null) context.Request.Headers[DesktopSessionBoundaryMiddleware.HeaderName] = supplied;

        await middleware.InvokeAsync(context);

        Assert.Equal(allowed, called);
        Assert.Equal(allowed ? 200 : 403, context.Response.StatusCode);
    }
}
