using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

internal sealed class StoragePaths(HostResources resources)
{
    private readonly HostResources resources = resources ?? throw new ArgumentNullException(nameof(resources));

    public string Server(ServerConfig server)
    {
        var root = Root(server.ManagedRootId, false);
        return Under(root, server.ServerDirectory);
    }

    public string Target(ServerConfig server, FileTarget target)
    {
        FileOperationValidation.ValidateTarget(target);
        if (target.ManagedRootId != server.ManagedRootId || (target.RelativePath != server.ServerDirectory && !target.RelativePath.StartsWith(server.ServerDirectory + '/', StringComparison.OrdinalIgnoreCase)))
            throw new InvalidOperationException("Path is outside the server directory.");
        return Under(Root(target.ManagedRootId, false), target.RelativePath);
    }

    public string Root(Guid id, bool allowNetwork)
    {
        var root = resources.Roots.SingleOrDefault(x => x.Id == id) ?? throw new InvalidOperationException("Unregistered managed root.");
        var path = Path.TrimEndingDirectorySeparator(Path.GetFullPath(root.Path));
        if (!Path.IsPathFullyQualified(path) || (!allowNetwork && (root.BackupOnly || IsNetwork(path))) || allowNetwork && IsNetwork(path) && !root.BackupOnly) throw new InvalidOperationException("Invalid managed root.");
        CheckNoReparse(path);
        if (!Directory.Exists(path)) throw new DirectoryNotFoundException("Registered root is unavailable.");
        return path;
    }

    public string Under(string root, string relativePath)
    {
        if (!ControlProtocol.IsRelativePath(relativePath)) throw new InvalidOperationException("Invalid relative path.");
        var path = Path.GetFullPath(Path.Combine(root, relativePath.Replace('/', Path.DirectorySeparatorChar)));
        if (!path.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("Path escapes registered root.");
        CheckNoReparse(path);
        return path;
    }

    public static void CheckNoReparse(string path)
    {
        var current = Path.GetFullPath(path);
        while (current is not null)
        {
            try
            {
                var attributes = File.GetAttributes(current);
                if ((attributes & FileAttributes.ReparsePoint) != 0) throw new InvalidOperationException("Reparse point in managed path.");
            }
            catch (FileNotFoundException) { }
            catch (DirectoryNotFoundException) { }
            current = Path.GetDirectoryName(current);
        }
    }

    public static bool IsNetwork(string path) => path.StartsWith("\\\\", StringComparison.Ordinal) || path.StartsWith("//", StringComparison.Ordinal);

    public static void CheckTree(string directory)
    {
        CheckNoReparse(directory);
        if (!Directory.Exists(directory)) throw new DirectoryNotFoundException(directory);
        var pending = new Stack<string>();
        pending.Push(directory);
        while (pending.Count != 0)
        {
            foreach (var entry in Directory.EnumerateFileSystemEntries(pending.Pop()))
            {
                CheckNoReparse(entry);
                if (Directory.Exists(entry)) pending.Push(entry);
            }
        }
    }
}
