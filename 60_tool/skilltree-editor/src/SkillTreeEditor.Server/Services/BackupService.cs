namespace SkillTreeEditor.Server.Services;

public sealed class BackupService(WorkspacePaths paths)
{
    public async Task<string?> BackupAsync(string sourcePath, string category, CancellationToken cancellationToken)
    {
        if (!File.Exists(sourcePath))
            return null;

        SafePath.RequireIdentifier(category, nameof(category));
        var destinationDirectory = SafePath.UnderRoot(paths.Backups, category);
        Directory.CreateDirectory(destinationDirectory);

        var timestamp = DateTimeOffset.Now.ToString("yyyyMMdd-HHmmssfff");
        var baseName = $"{Path.GetFileName(sourcePath)}.{timestamp}.bak";
        var destination = SafePath.UnderRoot(destinationDirectory, baseName);
        var suffix = 1;
        while (File.Exists(destination))
        {
            destination = SafePath.UnderRoot(destinationDirectory, $"{baseName}.{suffix++}");
        }

        await using var source = File.Open(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read);
        await using var target = File.Open(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        await source.CopyToAsync(target, cancellationToken);
        return destination;
    }
}
