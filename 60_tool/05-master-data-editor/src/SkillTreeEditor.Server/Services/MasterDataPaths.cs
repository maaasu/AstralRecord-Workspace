namespace SkillTreeEditor.Server.Services;

/// <summary>列挙で得たファイルの絶対パス・長さ・更新時刻。</summary>
public readonly record struct MasterDataEntry(string FullPath, long Length, DateTime LastWriteTimeUtc);

// Both user paths and discovered filesystem entries pass the same boundary checks.
public sealed class MasterDataPaths(WorkspacePaths workspace)
{
    public string Root { get; } = Path.GetFullPath(Path.Combine(workspace.WorkspaceRoot, "40_filebase"));
    internal string ResourcePackRoot { get; } = Path.Combine(workspace.WorkspaceRoot, "50_resourcepack");
    internal string BuffHudIconRegistry { get; } = Path.Combine(workspace.WorkspaceRoot,
        "10_plugin", "AstralRecord", "src", "main", "resources", "buff-hud-icons.json");
    public static StringComparison Comparison => OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
    public static StringComparer Comparer => OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;

    public string Resolve(string relative, bool directory = false)
    {
        if (!IsRelativeSyntax(relative))
            throw new ArgumentException(RelativeSyntaxMessage);
        var full = SafePath.UnderRoot(Root, relative);
        if (!directory && !IsData(full))
            throw new ArgumentException("編集できるファイルは .yml / .yaml / .json です。");
        RequireNoLinks(full);
        RequireCanonicalCase(relative);
        return full;
    }

    internal const string RelativeSyntaxMessage = "Filebase 内の相対パスを / 区切りで指定してください。";

    /// <summary>ファイルシステムへ触れずに判定できる、相対パスの書式だけを確認する。</summary>
    internal static bool IsRelativeSyntax(string relative) =>
        !(string.IsNullOrWhiteSpace(relative) || Path.IsPathRooted(relative)
          || relative.Contains(':') || relative.Contains('\\')
          || relative.Split('/').Any(part => part is "" or "." or ".." || part.EndsWith('.') || part.EndsWith(' ')));

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
            FileAttributes attributes;
            try { attributes = File.GetAttributes(candidate); }
            catch (FileNotFoundException) { continue; }
            catch (DirectoryNotFoundException) { continue; }
            if ((attributes & FileAttributes.ReparsePoint) != 0)
                throw new UnauthorizedAccessException("シンボリックリンク / junction は編集できません。");
        }
    }

    public IEnumerable<string> Enumerate(string? directory = null) =>
        EnumerateEntries(directory)
            .Where(entry => (File.GetAttributes(entry.FullPath) & FileAttributes.ReparsePoint) == 0)
            .Select(entry => entry.FullPath);

    /// <summary>
    /// ファイルを、列挙時に得られる長さと更新時刻つきで返す。属性は列挙結果から読むため、
    /// ファイルごとの追加の問い合わせを行わない。
    /// </summary>
    public IEnumerable<MasterDataEntry> EnumerateEntries(string? directory = null)
    {
        var root = directory is null ? Root : Resolve(directory, true);
        RequireNoLinks(root);
        if (!Directory.Exists(root)) yield break;
        var stack = new Stack<string>();
        stack.Push(root);
        while (stack.TryPop(out var current))
        {
            RequireNoLinks(current);
            foreach (var entry in new DirectoryInfo(current).EnumerateFileSystemInfos()
                         .OrderBy(entry => entry.FullName, StringComparer.Ordinal))
            {
                // Do not traverse links, including links pointing back into Filebase.
                if ((entry.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                if ((entry.Attributes & FileAttributes.Directory) != 0) stack.Push(entry.FullName);
                else if (entry is FileInfo file) yield return new(file.FullName, file.Length, file.LastWriteTimeUtc);
            }
        }
    }

    public string Relative(string full) => Path.GetRelativePath(Root, full).Replace('\\', '/');
    public static bool IsData(string path) => Path.GetExtension(path).ToLowerInvariant() is ".yml" or ".yaml" or ".json";
    internal static bool IsSchema(string path) => path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase);
    public static string Format(string path) => path.EndsWith(".json", StringComparison.OrdinalIgnoreCase) ? "json" : "yaml";
    public static bool IsReadOnly(string path) => path.Equals("35.features.skilltree/node-id-sequence.json", Comparison)
        || IsSchema(path);
}
