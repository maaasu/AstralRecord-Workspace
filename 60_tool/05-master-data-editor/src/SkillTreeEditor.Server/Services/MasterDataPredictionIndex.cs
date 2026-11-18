using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

internal static partial class MasterDataPredictionIndex
{
    private const int MaxTextLength = 8_000;
    private const int MaxPointerLength = 512;
    private const int MaxRows = 50_000;
    private const int MaxIndexCharacters = 4_000_000;

    private static readonly HashSet<string> ProseFields = new(StringComparer.OrdinalIgnoreCase)
        { "name", "displayName", "title", "label", "description", "lore", "summary", "text", "message" };

    [GeneratedRegex(@"\A(?:\$.*|.*ids?|schemaVersion|version|.*path|.*url|.*uri|.*uuid|.*key|.*type|.*material|icon|iconTexture|iconModel|tags?|.*refs?|.*references?|from|to|parent|source|target)\z",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex MetadataField();

    [GeneratedRegex(@"\A(?:[a-z_]+:[a-z0-9_.\-]+|[a-z][a-z0-9+.-]*://\S+)\z",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex ReferenceText();

    public static IReadOnlyList<MasterDataPredictionEntry> Build(FilebaseSnapshot snapshot, CancellationToken token)
    {
        var entries = new List<MasterDataPredictionEntry>();
        var characters = 0;
        foreach (var document in snapshot.Documents)
        {
            token.ThrowIfCancellationRequested();
            if (document.Content is not JsonObject root || document.IsReadOnly || document.PathError is not null ||
                document.DecodeError is not null || document.Issues.Any(issue => issue.Severity == "error") ||
                !document.Path.Contains('/') || document.Path.StartsWith("00.meta/", StringComparison.OrdinalIgnoreCase))
                continue;

            foreach (var (pointer, text) in TextFields(root, "", false, token))
            {
                characters += document.Path.Length + pointer.Length + text.Length;
                if (characters > MaxIndexCharacters || entries.Count == MaxRows) return entries;
                entries.Add(new(document.Path, pointer, text));
            }
        }
        return entries;
    }

    private static IEnumerable<(string Pointer, string Text)> TextFields(
        JsonNode? node, string pointer, bool prose, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        if (pointer.Length > MaxPointerLength) yield break;

        if (node is JsonObject obj)
        {
            foreach (var (key, child) in obj)
            {
                if (MetadataField().IsMatch(key)) continue;
                var escaped = key.Replace("~", "~0").Replace("/", "~1");
                var childIsProse = ProseFields.Contains(key) || prose && key.Length > 0 && key.All(char.IsAsciiDigit);
                foreach (var field in TextFields(child, pointer + "/" + escaped, childIsProse, token))
                    yield return field;
            }
        }
        else if (node is JsonArray array)
        {
            if (prose && JoinLines(array) is { } joined) yield return (pointer, joined);
            for (var index = 0; index < array.Count; index++)
                foreach (var field in TextFields(array[index], pointer + "/" + index.ToString(CultureInfo.InvariantCulture), prose, token))
                    yield return field;
        }
        else if (prose && node is JsonValue scalar && scalar.TryGetValue<string>(out var text) &&
            !string.IsNullOrWhiteSpace(text) && IsUsable(text))
        {
            yield return (pointer, text);
        }
    }

    private static bool IsUsable(string text) => text.Length <= MaxTextLength &&
        !text.Any(character => char.IsControl(character) && character is not ('\n' or '\t')) &&
        !ReferenceText().IsMatch(text.Trim());

    private static string? JoinLines(JsonArray array)
    {
        if (array.Count == 0 || array.Count > MaxTextLength) return null;
        var lines = new List<string>();
        var length = array.Count - 1;
        foreach (var entry in array)
        {
            if (entry is not JsonValue scalar || !scalar.TryGetValue<string>(out var line) ||
                line.Contains('\n') || !IsUsable(line))
                return null;
            length += line.Length;
            if (length > MaxTextLength) return null;
            lines.Add(line);
        }
        return lines.All(string.IsNullOrWhiteSpace) ? null : string.Join('\n', lines);
    }
}
