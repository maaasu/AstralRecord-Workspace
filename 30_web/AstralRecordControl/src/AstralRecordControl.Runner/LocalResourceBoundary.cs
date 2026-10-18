using AstralRecordControl.Contracts;

namespace AstralRecordControl.Runner;

/// <summary>Every existing path component is inspected, including ancestors of missing destinations.</summary>
public static class LocalResourceBoundary
{
    public static void ValidateResources(HostResources resources)
    {
        if (resources.Roots.Any(r => r.Id == Guid.Empty) || resources.Executables.Any(e => e.Id == Guid.Empty)
            || resources.Roots.Select(r => r.Id).Distinct().Count() != resources.Roots.Length
            || resources.Executables.Select(e => e.Id).Distinct().Count() != resources.Executables.Length)
            throw new RunnerFault(ErrorCode.PathOutsideRoot);
        foreach (var root in resources.Roots)
        {
            ValidateRootPath(root);
            if (!Directory.Exists(root.Path)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
        }
        foreach (var executable in resources.Executables)
        {
            ValidateLocalPath(executable.Path);
            if (!File.Exists(executable.Path) || !Enum.IsDefined(executable.Kind)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
        }
    }

    public static void ValidateInventory(InventorySnapshot inventory, HostResources resources)
    {
        ValidateResources(resources);
        var directories = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var server in inventory.Servers)
        {
            var root = resources.Roots.SingleOrDefault(r => r.Id == server.ManagedRootId)
                ?? throw new RunnerFault(ErrorCode.PathOutsideRoot);
            if (root.BackupOnly) throw new RunnerFault(ErrorCode.PathOutsideRoot);
            var executable = resources.Executables.SingleOrDefault(e => e.Id == server.ExecutableId);
            if (executable is null || executable.Kind != server.Kind) throw new RunnerFault(ErrorCode.PathOutsideRoot);
            var directory = Resolve(root.Path, server.ServerDirectory);
            if (File.Exists(directory) || !directories.Add(directory)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
            for (var index = 0; index < server.ArgumentList.Length; index++)
            {
                var argument = server.ArgumentList[index];
                if (argument == "-jar")
                {
                    if (++index >= server.ArgumentList.Length) throw new RunnerFault(ErrorCode.PathOutsideRoot);
                    Resolve(root.Path, Join(server.ServerDirectory, server.ArgumentList[index]));
                }
                else if (argument.StartsWith('@')) Resolve(root.Path, Join(server.ServerDirectory, argument[1..]));
                else if (argument.EndsWith(".jar", StringComparison.OrdinalIgnoreCase)) Resolve(root.Path, Join(server.ServerDirectory, argument));
            }
        }
    }

    private static string Join(string directory, string argument)
    {
        if (!ControlProtocol.IsRelativePath(argument)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
        return directory + "/" + argument;
    }

    public static string Resolve(string root, string relativePath)
    {
        if (!ControlProtocol.IsRelativePath(relativePath)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
        ValidateLocalPath(root);
        var normalizedRoot = Path.TrimEndingDirectorySeparator(Path.GetFullPath(root));
        var path = Path.GetFullPath(Path.Combine(normalizedRoot, relativePath.Replace('/', Path.DirectorySeparatorChar)));
        if (!path.StartsWith(normalizedRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw new RunnerFault(ErrorCode.PathOutsideRoot);
        ValidateLocalPath(path);
        return path;
    }

    public static string ResolveManagedRoot(ManagedRoot root, string relativePath)
    {
        if (!ControlProtocol.IsRelativePath(relativePath)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
        ValidateRootPath(root);
        var normalizedRoot = Path.TrimEndingDirectorySeparator(Path.GetFullPath(root.Path));
        var path = Path.GetFullPath(Path.Combine(normalizedRoot, relativePath.Replace('/', Path.DirectorySeparatorChar)));
        if (!path.StartsWith(normalizedRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
        ValidatePath(path, root.BackupOnly);
        return path;
    }

    public static void ValidateLocalPath(string path)
        => ValidatePath(path, false);

    public static void ValidateRootPath(ManagedRoot root)
        => ValidatePath(root.Path, root.BackupOnly);

    private static void ValidatePath(string path, bool allowBackupUnc)
    {
        var unc = path?.StartsWith("\\\\", StringComparison.Ordinal) == true;
        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path)
            || (unc && (!allowBackupUnc || path.StartsWith("\\\\?\\", StringComparison.Ordinal) || path.StartsWith("\\\\.\\", StringComparison.Ordinal) || path.Split('\\').Length < 4))
            || path.StartsWith("//", StringComparison.Ordinal) || (!unc && path.AsSpan(Math.Min(2, path.Length)).Contains(':')))
            throw new RunnerFault(ErrorCode.PathOutsideRoot);
        var current = Path.GetFullPath(path);
        var leaf = true;
        while (!string.IsNullOrEmpty(current))
        {
            try
            {
                var attributes = File.GetAttributes(current);
                if ((attributes & FileAttributes.ReparsePoint) != 0 || (!leaf && (attributes & FileAttributes.Directory) == 0)) throw new RunnerFault(ErrorCode.PathOutsideRoot);
            }
            catch (FileNotFoundException) { }
            catch (DirectoryNotFoundException) { }
            current = Path.GetDirectoryName(current);
            leaf = false;
        }
    }
}
