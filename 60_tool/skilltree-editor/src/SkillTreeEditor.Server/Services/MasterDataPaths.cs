namespace SkillTreeEditor.Server.Services;

// Both user paths and discovered filesystem entries pass the same boundary checks.
public sealed class MasterDataPaths(WorkspacePaths workspace)
{
    public string Root { get; } = Path.GetFullPath(Path.Combine(workspace.WorkspaceRoot, "40_filebase"));
    public static StringComparison Comparison => OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
    public static StringComparer Comparer => OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;

    public string Resolve(string relative, bool directory = false)
    {
        if (string.IsNullOrWhiteSpace(relative) || Path.IsPathRooted(relative)
            || relative.Contains(':') || relative.Contains('\\')
            || relative.Split('/').Any(part => part is "" or "." or ".." || part.EndsWith('.') || part.EndsWith(' ')))
            throw new ArgumentException("Filebase 内の相対パスを / 区切りで指定してください。");
        var full = SafePath.UnderRoot(Root, relative);
        if (!directory && !IsData(full))
            throw new ArgumentException("編集できるファイルは .yml / .yaml / .json です。");
        RequireNoLinks(full);
        RequireCanonicalCase(relative);
        return full;
    }

    private void RequireCanonicalCase(string relative)
    {
        if (!OperatingSystem.IsWindows()) return;
        var current = Root;
        foreach (var segment in relative.Split('/'))
        {
            if (!Directory.Exists(current)) break;
            var existing = Directory.EnumerateFileSystemEntries(current, segment, SearchOption.TopDirectoryOnly)
                .Select(Path.GetFileName)
                .FirstOrDefault(name => string.Equals(name, segment, StringComparison.OrdinalIgnoreCase));
            if (existing is not null && !string.Equals(existing, segment, StringComparison.Ordinal))
                throw new ArgumentException($"パスの大文字小文字を実ファイル名 '{existing}' に合わせてください。");
            current = Path.Combine(current, segment);
        }
    }

    public void RequireNoLinks(string path)
    {
        var current = new DirectoryInfo(workspace.WorkspaceRoot);
        if ((current.Attributes & FileAttributes.ReparsePoint) != 0)
            throw new UnauthorizedAccessException("リンクされた workspace は編集できません。");
        var relative = Path.GetRelativePath(workspace.WorkspaceRoot, path);
        var candidate = workspace.WorkspaceRoot;
        foreach (var part in relative.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar))
        {
            candidate = Path.Combine(candidate, part);
            if ((File.Exists(candidate) || Directory.Exists(candidate))
                && (File.GetAttributes(candidate) & FileAttributes.ReparsePoint) != 0)
                throw new UnauthorizedAccessException("シンボリックリンク / junction は編集できません。");
        }
    }

    public IEnumerable<string> Enumerate(string? directory = null)
    {
        var root = directory is null ? Root : Resolve(directory, true);
        RequireNoLinks(root);
        if (!Directory.Exists(root)) yield break;
        var stack = new Stack<string>();
        stack.Push(root);
        while (stack.TryPop(out var current))
        {
            foreach (var entry in Directory.EnumerateFileSystemEntries(current).Order(StringComparer.Ordinal))
            {
                // Do not traverse links, including links pointing back into Filebase.
                if ((File.GetAttributes(entry) & FileAttributes.ReparsePoint) != 0) continue;
                if (Directory.Exists(entry)) stack.Push(entry);
                else yield return entry;
            }
        }
    }

    public string Relative(string full) => Path.GetRelativePath(Root, full).Replace('\\', '/');
    public static bool IsData(string path) => Path.GetExtension(path).ToLowerInvariant() is ".yml" or ".yaml" or ".json";
    public static string Format(string path) => path.EndsWith(".json", StringComparison.OrdinalIgnoreCase) ? "json" : "yaml";
    public static bool IsReadOnly(string path) => path.Equals("35.features.skilltree/node-id-sequence.json", Comparison)
        || path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase);
}
