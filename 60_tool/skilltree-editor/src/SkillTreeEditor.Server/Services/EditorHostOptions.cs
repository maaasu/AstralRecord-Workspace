namespace SkillTreeEditor.Server.Services;

public static class EditorHostOptions
{
    public static void AddLocalSettings(WebApplicationBuilder builder, string[] args)
    {
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
