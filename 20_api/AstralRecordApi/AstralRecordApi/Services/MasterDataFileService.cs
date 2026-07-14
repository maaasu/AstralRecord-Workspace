using AstralRecordApi.Models;
using AstralRecordApi.Options;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Services;

public sealed class MasterDataFileService(
    IOptions<FileDatabaseOptions> options) : IMasterDataFileService
{
    private readonly string rootPath = Path.GetFullPath(options.Value.RootPath);

    public IReadOnlyList<MasterDataFileSummaryResponse> List(string? directory)
    {
        var directoryPath = Resolve(directory ?? string.Empty, allowMissing: true);
        if (!Directory.Exists(directoryPath)) return [];

        return Directory.EnumerateFiles(directoryPath, "*.yml", SearchOption.AllDirectories)
            .Select(ToSummary)
            .OrderBy(file => file.Path, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    public MasterDataFileResponse? Get(string relativePath)
    {
        var path = Resolve(relativePath, allowMissing: true);
        if (!File.Exists(path)) return null;
        var info = new FileInfo(path);
        return new MasterDataFileResponse
        {
            Path = ToRelative(path),
            Content = File.ReadAllText(path),
            LastWriteTimeUtc = info.LastWriteTimeUtc,
            Length = info.Length,
        };
    }

    public MasterDataFileResponse Put(string relativePath, string content)
    {
        var path = Resolve(relativePath, allowMissing: true);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, content ?? string.Empty);
        return Get(relativePath)!;
    }

    public bool Delete(string relativePath)
    {
        var path = Resolve(relativePath, allowMissing: true);
        if (!File.Exists(path)) return false;
        File.Delete(path);
        return true;
    }

    private string Resolve(string relativePath, bool allowMissing)
    {
        if (string.IsNullOrWhiteSpace(options.Value.RootPath))
            throw new InvalidOperationException("FileDatabase:RootPath is not configured.");

        var normalized = relativePath.Replace('\\', '/').Trim('/');
        if (normalized.Contains("..", StringComparison.Ordinal)
            || !string.IsNullOrEmpty(normalized) && !normalized.EndsWith(".yml", StringComparison.OrdinalIgnoreCase)
                && !Directory.Exists(Path.Combine(rootPath, normalized)))
            throw new ArgumentException("Only safe .yml paths under filebase are allowed.", nameof(relativePath));

        var fullPath = Path.GetFullPath(Path.Combine(rootPath, normalized));
        if (!fullPath.StartsWith(rootPath.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
            && !string.Equals(fullPath, rootPath, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Path is outside the configured filebase root.", nameof(relativePath));
        if (!allowMissing && !File.Exists(fullPath)) throw new FileNotFoundException("filebase file was not found.", fullPath);
        return fullPath;
    }

    private MasterDataFileSummaryResponse ToSummary(string path)
    {
        var info = new FileInfo(path);
        return new MasterDataFileSummaryResponse
        {
            Path = ToRelative(path),
            LastWriteTimeUtc = info.LastWriteTimeUtc,
            Length = info.Length,
        };
    }

    private string ToRelative(string path) => Path.GetRelativePath(rootPath, path).Replace('\\', '/');
}
