using System.Collections.Concurrent;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Options;

namespace SkillTreeEditor.Server.Services;

public sealed partial class MinecraftIconService(HttpClient httpClient, WorkspacePaths paths, IOptions<EditorOptions>? options = null)
{
    private readonly MasterDataPaths _safePaths = new(paths);

    private const long MaximumIconBytes = 2 * 1024 * 1024;
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _locks = new(StringComparer.Ordinal);
    private readonly Uri? _fallbackBaseUri = CreateFallbackBaseUri(options?.Value.MinecraftIconsFallbackBaseUrl);

    public async Task<string?> GetIconPathAsync(
        string material,
        bool refresh,
        CancellationToken cancellationToken)
    {
        var iconId = NormalizeMaterial(material);
        var path = Path.Combine(paths.MinecraftIconCache, $"{iconId}.png");
        if (Directory.Exists(paths.WorkspaceRoot))
            _safePaths.RequireNoLinks(path);
        if (!refresh && File.Exists(path))
            return path;

        var gate = _locks.GetOrAdd(iconId, static _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(cancellationToken);
        try
        {
            if (Directory.Exists(paths.WorkspaceRoot))
                _safePaths.RequireNoLinks(path);
            if (!refresh && File.Exists(path))
                return path;

            try
            {
                var primaryPath = await DownloadIconAsync(
                    new Uri($"download/{Uri.EscapeDataString(iconId)}/thumb", UriKind.Relative), path, cancellationToken);
                if (primaryPath is not null)
                    return primaryPath;
            }
            catch (HttpRequestException) when (_fallbackBaseUri is not null && !cancellationToken.IsCancellationRequested) { }
            catch (HttpIOException) when (_fallbackBaseUri is not null && !cancellationToken.IsCancellationRequested) { }
            catch (InvalidDataException) when (_fallbackBaseUri is not null && !cancellationToken.IsCancellationRequested) { }
            catch (OperationCanceledException) when (_fallbackBaseUri is not null && !cancellationToken.IsCancellationRequested) { }

            if (_fallbackBaseUri is not null)
            {
                cancellationToken.ThrowIfCancellationRequested();
                return await DownloadIconAsync(
                    new Uri(_fallbackBaseUri, iconId.ToUpperInvariant() + ".png"), path, cancellationToken);
            }
            return null;
        }
        finally
        {
            gate.Release();
        }
    }

    private async Task<string?> DownloadIconAsync(Uri sourceUri, string path, CancellationToken cancellationToken)
    {
        using var response = await httpClient.GetAsync(sourceUri, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (response.StatusCode == System.Net.HttpStatusCode.NotFound)
            return null;
        response.EnsureSuccessStatusCode();

        var mediaType = response.Content.Headers.ContentType?.MediaType;
        if (!string.Equals(mediaType, "image/png", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException($"Minecraft アイコンの取得元が未対応のコンテンツ形式 '{mediaType ?? "不明"}' を返しました。");
        if (response.Content.Headers.ContentLength is > MaximumIconBytes)
            throw new InvalidDataException("Minecraft アイコンの応答が上限の2 MiBを超えています。");

        Directory.CreateDirectory(paths.WorkspaceRoot);
        _safePaths.RequireNoLinks(path);
        Directory.CreateDirectory(paths.MinecraftIconCache);
        var temporaryPath = $"{path}.{Guid.NewGuid():N}.tmp";
        try
        {
            _safePaths.RequireNoLinks(temporaryPath);
            await using (var source = await response.Content.ReadAsStreamAsync(cancellationToken))
            await using (var destination = new FileStream(
                temporaryPath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                81920,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await CopyWithLimitAsync(source, destination, cancellationToken);
            }
            _safePaths.RequireNoLinks(path);
            File.Move(temporaryPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporaryPath))
            {
                _safePaths.RequireNoLinks(temporaryPath);
                File.Delete(temporaryPath);
            }
        }
        return path;
    }

    private static Uri? CreateFallbackBaseUri(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;
        var uri = new Uri(value.TrimEnd('/') + "/", UriKind.Absolute);
        if (uri.Scheme is not "https" and not "http")
            throw new ArgumentException("Minecraft アイコンの代替画像には HTTP または HTTPS のURLを指定してください。", nameof(value));
        return uri;
    }

    public static string NormalizeMaterial(string material)
    {
        var value = material.Trim();
        if (value.StartsWith("minecraft:", StringComparison.OrdinalIgnoreCase))
            value = value["minecraft:".Length..];
        value = value.ToLowerInvariant();
        if (!IconIdPattern().IsMatch(value))
            throw new ArgumentException("Material には英字・数字・アンダースコアのみ使用できます。", nameof(material));
        return value;
    }

    private static async Task CopyWithLimitAsync(Stream source, Stream destination, CancellationToken cancellationToken)
    {
        var buffer = new byte[81920];
        long total = 0;
        while (true)
        {
            var read = await source.ReadAsync(buffer, cancellationToken);
            if (read == 0)
                break;
            total += read;
            if (total > MaximumIconBytes)
                throw new InvalidDataException("MC Icons の応答が上限の2 MiBを超えています。");
            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
        }
        await destination.FlushAsync(cancellationToken);
    }

    [GeneratedRegex("^[a-z0-9_]+$", RegexOptions.CultureInvariant)]
    private static partial Regex IconIdPattern();
}
