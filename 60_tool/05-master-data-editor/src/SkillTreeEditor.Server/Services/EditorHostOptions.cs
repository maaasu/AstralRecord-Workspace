namespace SkillTreeEditor.Server.Services;

public static class EditorHostOptions
{
    public static void AddLocalSettings(WebApplicationBuilder builder, string[] args)
    {
        var workspace = builder.Configuration["SkillTreeEditor:WorkspaceRoot"];
        if (!string.IsNullOrEmpty(builder.Configuration["SkillTreeEditor:DesktopSessionToken"])
            && !string.IsNullOrWhiteSpace(workspace))
        {
            var workspaceSettings = Path.Combine(Path.GetFullPath(workspace), "60_tool", "05-master-data-editor",
                "src", "SkillTreeEditor.Server", "appsettings.local.json");
            builder.Configuration.AddJsonFile(workspaceSettings, optional: true, reloadOnChange: false);
        }
        var path = Path.Combine(builder.Environment.ContentRootPath, "appsettings.local.json");
        builder.Configuration.AddJsonFile(path, optional: true, reloadOnChange: false);
        // The optional local file sits below environment variables and command line settings.
        builder.Configuration.AddEnvironmentVariables();
        builder.Configuration.AddCommandLine(args);
    }

    public static WebApplicationOptions Create(string[] args)
        => Create(args, AppContext.BaseDirectory);

    public static WebApplicationOptions Create(string[] args, string applicationBaseDirectory)
    {
        ArgumentNullException.ThrowIfNull(args);
        ArgumentException.ThrowIfNullOrWhiteSpace(applicationBaseDirectory);

        var normalizedBaseDirectory = Path.GetFullPath(applicationBaseDirectory);
        var publishedIndex = Path.Combine(normalizedBaseDirectory, "wwwroot", "index.html");

        return new WebApplicationOptions
        {
            Args = args,
            ContentRootPath = File.Exists(publishedIndex) ? normalizedBaseDirectory : null
        };
    }
}
