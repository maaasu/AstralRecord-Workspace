using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Repositories;

/// <summary>Plugin が実行時に利用する filebase 定義だけを読み取る。</summary>
public sealed class RuntimeFilebaseRepository(IOptions<FileDatabaseOptions> options) : IRuntimeFilebaseRepository
{
    // Plugin の各 definition repository が読むディレクトリと拡張子に限定する。
    private static readonly (string Directory, string Extension, bool Recursive)[] Sources =
    [
        ("35.features.skilltree/nodes", ".json", false),
        ("35.features.skilltree/structures", ".json", false),
        ("45.features.shop", ".yml", false),
        ("85.shared.recipe", ".yml", false),
        ("51.features.quest", ".yml", false),
        ("52.features.quest_board", ".yml", false),
        ("42.features.gathering", ".yml", true),
        ("43.features.gathering.spawner", ".yml", false),
        ("41.features.mob.spawner", ".yml", false),
        ("65.features.dungeon", ".yml", false),
    ];

    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    public async Task<RuntimeFilebaseSnapshotResponse> GetSnapshotAsync(CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(options.Value.RootPath))
            throw new InvalidOperationException("FileDatabase:RootPath is not configured.");

        var root = new DirectoryInfo(Path.GetFullPath(options.Value.RootPath));
        RequireSafeDirectory(root);

        var before = EnumerateFiles(root);
        var files = new List<RuntimeFilebaseFileResponse>(before.Count);
        foreach (var entry in before)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RequireSafeFile(entry.File);
            var bytes = await File.ReadAllBytesAsync(entry.File.FullName, cancellationToken);
            if (!MetadataMatches(entry.File, entry.Length, entry.LastWriteTimeUtc))
                throw new IOException($"Runtime filebase changed while reading: {entry.Path}");
            files.Add(new RuntimeFilebaseFileResponse(entry.Path, StrictUtf8.GetString(bytes)));
        }

        var after = EnumerateFiles(root);
        if (before.Count != after.Count || before.Where((entry, index) =>
                entry.Path != after[index].Path
                || entry.Length != after[index].Length
                || entry.LastWriteTimeUtc != after[index].LastWriteTimeUtc).Any())
            throw new IOException("Runtime filebase changed while creating the snapshot.");

        return new RuntimeFilebaseSnapshotResponse(1, files);
    }

    private static List<FileEntry> EnumerateFiles(DirectoryInfo root)
    {
        var files = new List<FileEntry>();
        foreach (var source in Sources)
        {
            var directory = root;
            foreach (var component in source.Directory.Split('/'))
            {
                directory = new DirectoryInfo(Path.Combine(directory.FullName, component));
                RequireSafeDirectory(directory);
            }
            Collect(directory, source.Extension, source.Recursive, root.FullName, files);
        }
        files.Sort((left, right) => StringComparer.Ordinal.Compare(left.Path, right.Path));
        return files;
    }

    private static void Collect(
        DirectoryInfo directory,
        string extension,
        bool recursive,
        string rootPath,
        List<FileEntry> files)
    {
        foreach (var entry in directory.EnumerateFileSystemInfos())
        {
            if (entry.Attributes.HasFlag(FileAttributes.ReparsePoint))
                throw new IOException($"Runtime filebase contains a link: {entry.FullName}");
            if (entry is DirectoryInfo child)
            {
                if (recursive)
                    Collect(child, extension, true, rootPath, files);
                continue;
            }
            if (entry is not FileInfo file
                || !file.Extension.Equals(extension, StringComparison.OrdinalIgnoreCase)
                || file.Name.StartsWith("docs.", StringComparison.OrdinalIgnoreCase)
                || file.Name.Contains("YAML", StringComparison.Ordinal)
                || file.Name.EndsWith("定義.yml", StringComparison.Ordinal))
                continue;
            RequireSafeFile(file);
            files.Add(new FileEntry(
                Path.GetRelativePath(rootPath, file.FullName).Replace('\\', '/'),
                file,
                file.Length,
                file.LastWriteTimeUtc));
        }
    }

    private static void RequireSafeDirectory(DirectoryInfo directory)
    {
        if (!directory.Exists)
            throw new DirectoryNotFoundException($"Runtime filebase directory is missing: {directory.FullName}");
        if (directory.Attributes.HasFlag(FileAttributes.ReparsePoint))
            throw new IOException($"Runtime filebase directory is a link: {directory.FullName}");
    }

    private static void RequireSafeFile(FileInfo file)
    {
        file.Refresh();
        if (!file.Exists || file.Attributes.HasFlag(FileAttributes.ReparsePoint))
            throw new IOException($"Runtime filebase file is missing or linked: {file.FullName}");
    }

    private static bool MetadataMatches(FileInfo file, long length, DateTime lastWriteTimeUtc)
    {
        file.Refresh();
        return file.Exists
            && !file.Attributes.HasFlag(FileAttributes.ReparsePoint)
            && file.Length == length
            && file.LastWriteTimeUtc == lastWriteTimeUtc;
    }

    private sealed record FileEntry(string Path, FileInfo File, long Length, DateTime LastWriteTimeUtc);
}
