using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using AstralRecordWeb.Models;
using AstralRecordWeb.Options;
using Markdig;
using Microsoft.Extensions.Options;

namespace AstralRecordWeb.Services;

public sealed class ReleaseNoteCatalog(
    IWebHostEnvironment environment,
    IOptions<ReleaseNoteOptions> options,
    ILogger<ReleaseNoteCatalog> logger)
{
    private static readonly Regex SlugRegex = new("^[a-z0-9]+(?:-[a-z0-9]+)*$", RegexOptions.Compiled | RegexOptions.CultureInvariant);
    private static readonly Regex UnsafeUrlRegex = new(
        @"(?<prefix>\b(?:href|src)\s*=\s*[""'])(?:javascript|vbscript|data):",
        RegexOptions.Compiled | RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
    private static readonly MarkdownPipeline MarkdownPipeline = new MarkdownPipelineBuilder()
        .UseAdvancedExtensions()
        .DisableHtml()
        .Build();

    public async Task<IReadOnlyList<ReleaseNoteDocument>> GetPublishedAsync(
        CancellationToken cancellationToken)
    {
        var directory = ResolveContentDirectory();
        if (!Directory.Exists(directory))
        {
            logger.LogWarning("Release note directory does not exist: {Directory}", directory);
            return [];
        }

        var documents = new List<ReleaseNoteDocument>();
        foreach (var filePath in Directory.EnumerateFiles(directory, "*.md", SearchOption.AllDirectories))
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (string.Equals(Path.GetFileName(filePath), "README.md", StringComparison.OrdinalIgnoreCase))
                continue;

            try
            {
                var document = await ParseAsync(filePath, directory, cancellationToken);
                if (document is not null)
                    documents.Add(document);
            }
            catch (Exception ex) when (ex is InvalidDataException or IOException or UnauthorizedAccessException)
            {
                logger.LogError(ex, "Release note could not be loaded: {FilePath}", filePath);
            }
        }

        return documents
            .OrderByDescending(document => document.PublishedAt)
            .ThenByDescending(document => document.Version, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    private async Task<ReleaseNoteDocument?> ParseAsync(
        string filePath,
        string contentDirectory,
        CancellationToken cancellationToken)
    {
        var rawContent = await File.ReadAllTextAsync(filePath, Encoding.UTF8, cancellationToken);
        var normalizedContent = NormalizeLineEndings(rawContent);
        var frontMatter = ParseFrontMatter(normalizedContent, filePath);

        if (!frontMatter.TryGetValue("status", out var statusValue)
            || !Enum.TryParse<ReleaseNoteStatus>(statusValue, true, out var status))
            throw new InvalidDataException("status must be Draft or Published.");

        if (status != ReleaseNoteStatus.Published)
            return null;

        var slug = Required(frontMatter, "slug", filePath).ToLowerInvariant();
        if (!SlugRegex.IsMatch(slug))
            throw new InvalidDataException("slug is invalid.");

        var publishedAtText = Required(frontMatter, "publishedAt", filePath);
        if (!DateTimeOffset.TryParse(
                publishedAtText,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
                out var publishedAt))
            throw new InvalidDataException("publishedAt is invalid.");

        if (publishedAt > DateTimeOffset.UtcNow)
            return null;

        var body = ExtractBody(normalizedContent, filePath);
        var html = SanitizeRenderedHtml(Markdown.ToHtml(body, MarkdownPipeline));
        var relativePath = Path.GetRelativePath(contentDirectory, filePath).Replace('\\', '/');
        var sourcePath = $"00_docs/70_リリースノート/{relativePath}";
        var publicBaseUrl = options.Value.PublicBaseUrl.TrimEnd('/');
        return new ReleaseNoteDocument
        {
            Slug = slug,
            Version = Required(frontMatter, "version", filePath),
            Title = Required(frontMatter, "title", filePath),
            Summary = Required(frontMatter, "summary", filePath),
            PublishedAt = publishedAt,
            Status = status,
            NotifyDiscord = ParseBoolean(frontMatter, "notifyDiscord", filePath),
            SourcePath = sourcePath,
            ContentSha256 = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(normalizedContent))),
            ReleaseUrl = $"{publicBaseUrl}/releases/{slug}",
            Html = html,
        };
    }

    private string ResolveContentDirectory()
    {
        var configuredPath = options.Value.ContentRootRelativePath;
        if (Path.IsPathRooted(configuredPath))
            return configuredPath;

        var contentRootPath = Path.Combine(environment.ContentRootPath, configuredPath);
        if (Directory.Exists(contentRootPath))
            return contentRootPath;

        return Path.Combine(AppContext.BaseDirectory, configuredPath);
    }

    private static Dictionary<string, string> ParseFrontMatter(string content, string filePath)
    {
        using var reader = new StringReader(content);
        if (!string.Equals(reader.ReadLine(), "---", StringComparison.Ordinal))
            throw new InvalidDataException("front matter must start with ---.");

        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        string? line;
        var closed = false;
        while ((line = reader.ReadLine()) is not null)
        {
            if (line == "---")
            {
                closed = true;
                break;
            }

            if (string.IsNullOrWhiteSpace(line))
                continue;

            var separator = line.IndexOf(':');
            if (separator <= 0)
                throw new InvalidDataException($"front matter line is invalid: {filePath}");

            var key = line[..separator].Trim();
            var value = Unquote(line[(separator + 1)..].Trim());
            if (!key.Equals("slug", StringComparison.OrdinalIgnoreCase)
                && !key.Equals("version", StringComparison.OrdinalIgnoreCase)
                && !key.Equals("title", StringComparison.OrdinalIgnoreCase)
                && !key.Equals("summary", StringComparison.OrdinalIgnoreCase)
                && !key.Equals("publishedAt", StringComparison.OrdinalIgnoreCase)
                && !key.Equals("status", StringComparison.OrdinalIgnoreCase)
                && !key.Equals("notifyDiscord", StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException($"front matter key is unknown: {key}");

            result[key] = value;
        }

        if (!closed)
            throw new InvalidDataException("front matter is not closed.");

        return result;
    }

    private static string ExtractBody(string content, string filePath)
    {
        var firstSeparator = content.IndexOf("---", StringComparison.Ordinal);
        var secondSeparator = content.IndexOf("---", firstSeparator + 3, StringComparison.Ordinal);
        if (firstSeparator != 0 || secondSeparator < 0)
            throw new InvalidDataException($"front matter is invalid: {filePath}");

        var bodyStart = secondSeparator + 3;
        return content[bodyStart..].TrimStart('\r', '\n');
    }

    private static string Required(IReadOnlyDictionary<string, string> values, string key, string filePath)
    {
        if (!values.TryGetValue(key, out var value) || string.IsNullOrWhiteSpace(value))
            throw new InvalidDataException($"front matter key is required: {key} ({filePath})");

        return value.Trim();
    }

    private static bool ParseBoolean(IReadOnlyDictionary<string, string> values, string key, string filePath)
    {
        var value = Required(values, key, filePath);
        if (!bool.TryParse(value, out var result))
            throw new InvalidDataException($"front matter boolean is invalid: {key} ({filePath})");

        return result;
    }

    private static string Unquote(string value)
    {
        if (value.Length >= 2
            && ((value[0] == '\"' && value[^1] == '\"') || (value[0] == '\'' && value[^1] == '\'')))
            return value[1..^1];

        return value;
    }

    private static string NormalizeLineEndings(string content)
        => content.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n');

    private static string SanitizeRenderedHtml(string html)
        => UnsafeUrlRegex.Replace(html, "${prefix}#blocked:");
}
