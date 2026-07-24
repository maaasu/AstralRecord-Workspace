namespace SkillTreeEditor.Server.Services;

public static class EditorHostOptions
{
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
