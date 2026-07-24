using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Options;

namespace SkillTreeEditor.Server.Services;

public sealed class WorkspacePaths
{
    public WorkspacePaths(IOptions<EditorOptions> options, IWebHostEnvironment environment)
        : this(ResolveWorkspaceRoot(options.Value.WorkspaceRoot, environment.ContentRootPath))
    {
    }

    public WorkspacePaths(string workspaceRoot)
    {
        WorkspaceRoot = Path.GetFullPath(workspaceRoot);
        SkillTreeRoot = Path.Combine(WorkspaceRoot, "40_filebase", "35.features.skilltree");
        Nodes = Path.Combine(SkillTreeRoot, "nodes");
        Structures = Path.Combine(SkillTreeRoot, "structures");
        Schemas = Path.Combine(SkillTreeRoot, "schemas");
        NodeIdSequence = Path.Combine(SkillTreeRoot, "node-id-sequence.json");
        PluginConfig = Path.Combine(
            WorkspaceRoot,
            "10_plugin",
            "AstralRecord",
            "src",
            "main",
            "resources",
            "config.yml");
        Backups = Path.Combine(WorkspaceRoot, "60_tool", "skilltree-editor", ".backups");
        WorkspaceMutationLock = Path.Combine(Backups, ".locks", "workspace-mutation.lock");
        NodeIdSequenceLock = Path.Combine(Backups, ".locks", "node-id-sequence.lock");
    }

    public string WorkspaceRoot { get; }
    public string SkillTreeRoot { get; }
    public string Nodes { get; }
    public string Structures { get; }
    public string Schemas { get; }
    public string NodeIdSequence { get; }
    public string PluginConfig { get; }
    public string Backups { get; }
    public string WorkspaceMutationLock { get; }
    public string NodeIdSequenceLock { get; }

    public EditorMetadata ToMetadata() => new(
        WorkspaceRoot,
        Nodes,
        Structures,
        Schemas,
        NodeIdSequence,
        PluginConfig,
        Backups);

    public static string ResolveWorkspaceRoot(string? configuredRoot, params string[] startPaths)
    {
        var candidates = new List<string?>
        {
            configuredRoot,
            Environment.GetEnvironmentVariable("ASTRALRECORD_WORKSPACE")
        };
        candidates.AddRange(startPaths);
        candidates.Add(Directory.GetCurrentDirectory());
        candidates.Add(AppContext.BaseDirectory);

        foreach (var candidate in candidates.Where(value => !string.IsNullOrWhiteSpace(value)))
        {
            var resolved = TryFindWorkspace(candidate!);
            if (resolved is not null)
                return resolved;
        }

        throw new DirectoryNotFoundException(
            "AstralRecord workspace was not found. Set SkillTreeEditor:WorkspaceRoot or ASTRALRECORD_WORKSPACE.");
    }

    private static string? TryFindWorkspace(string startPath)
    {
        DirectoryInfo? directory;
        try
        {
            var fullPath = Path.GetFullPath(Environment.ExpandEnvironmentVariables(startPath));
            directory = Directory.Exists(fullPath)
                ? new DirectoryInfo(fullPath)
                : new FileInfo(fullPath).Directory;
        }
        catch
        {
            return null;
        }

        while (directory is not null)
        {
            if (Directory.Exists(Path.Combine(directory.FullName, "40_filebase"))
                && Directory.Exists(Path.Combine(directory.FullName, "10_plugin"))
                && Directory.Exists(Path.Combine(directory.FullName, "60_tool")))
                return directory.FullName;

            directory = directory.Parent;
        }

        return null;
    }
}
