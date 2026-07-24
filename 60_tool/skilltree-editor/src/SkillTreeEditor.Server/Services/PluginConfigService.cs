using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

public sealed class PluginConfigService(WorkspacePaths paths, BackupService backupService)
{
    private static readonly Regex SkillTreeHeader = new(
        @"(?m)^skilltree:\s*(?:#.*)?\r?$",
        RegexOptions.CultureInvariant);
    private static readonly Regex NextTopLevelKey = new(
        @"(?m)^(?=[^\s#][^:\r\n]*:)",
        RegexOptions.CultureInvariant);
    private readonly SemaphoreSlim _writeLock = new(1, 1);

    public async Task<PluginSkillTreeSettings> ReadAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(paths.PluginConfig))
            throw new FileNotFoundException("Plugin config.yml was not found.", paths.PluginConfig);

        var text = await File.ReadAllTextAsync(paths.PluginConfig, Encoding.UTF8, cancellationToken);
        var block = ExtractSkillTreeBlock(text);
        if (block is null)
            return new PluginSkillTreeSettings("world", string.Empty, 0, 0, 0);

        return new PluginSkillTreeSettings(
            ReadScalar(block, "worldName") ?? "world",
            ReadScalar(block, "structureId") ?? string.Empty,
            ReadNumber(block, "x"),
            ReadNumber(block, "y"),
            ReadNumber(block, "z"));
    }

    public async Task SaveAsync(PluginSkillTreeSettings settings, CancellationToken cancellationToken)
    {
        Validate(settings);
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            if (!File.Exists(paths.PluginConfig))
                throw new FileNotFoundException("Plugin config.yml was not found.", paths.PluginConfig);

            var original = await File.ReadAllTextAsync(paths.PluginConfig, Encoding.UTF8, cancellationToken);
            var newline = original.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
            var header = SkillTreeHeader.Match(original);
            string updated;
            if (header.Success)
            {
                var next = NextTopLevelKey.Match(original, header.Index + header.Length);
                var end = next.Success ? next.Index : original.Length;
                var replacement = UpdateBlock(original[header.Index..end], settings, newline);
                updated = original[..header.Index] + replacement + original[end..];
            }
            else
            {
                var replacement = BuildBlock(settings, newline);
                var separator = original.Length == 0
                    ? string.Empty
                    : original.EndsWith(newline, StringComparison.Ordinal)
                        ? newline
                        : newline + newline;
                updated = original + separator + replacement;
            }

            if (string.Equals(updated, original, StringComparison.Ordinal))
                return;

            await backupService.BackupAsync(paths.PluginConfig, "plugin-config", cancellationToken);
            var directory = Path.GetDirectoryName(paths.PluginConfig)!;
            var temporaryPath = SafePath.UnderRoot(
                directory,
                $".{Path.GetFileName(paths.PluginConfig)}.{Guid.NewGuid():N}.tmp");
            try
            {
                await File.WriteAllTextAsync(
                    temporaryPath,
                    updated,
                    new UTF8Encoding(encoderShouldEmitUTF8Identifier: false),
                    cancellationToken);
                File.Move(temporaryPath, paths.PluginConfig, overwrite: true);
            }
            finally
            {
                if (File.Exists(temporaryPath))
                    File.Delete(temporaryPath);
            }
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private static string? ExtractSkillTreeBlock(string text)
    {
        var header = SkillTreeHeader.Match(text);
        if (!header.Success)
            return null;

        var next = NextTopLevelKey.Match(text, header.Index + header.Length);
        var end = next.Success ? next.Index : text.Length;
        return text[header.Index..end];
    }

    private static string? ReadScalar(string block, string key)
    {
        var match = Regex.Match(
            block,
            $@"(?m)^[ \t]+(?:{Regex.Escape(key)})[ \t]*:[ \t]*(?<value>[^\r\n]*)\r?$",
            RegexOptions.CultureInvariant);
        if (!match.Success)
            return null;

        var rawValue = match.Groups["value"].Value;
        var commentIndex = FindYamlCommentStart(rawValue);
        var value = (commentIndex >= 0 ? rawValue[..commentIndex] : rawValue).Trim();
        if (value.Length >= 2 && value[0] == '"' && value[^1] == '"')
        {
            value = value[1..^1];
            return value.Replace("\\\"", "\"", StringComparison.Ordinal)
                .Replace("\\\\", "\\", StringComparison.Ordinal);
        }

        if (value.Length >= 2 && value[0] == '\'' && value[^1] == '\'')
            return value[1..^1].Replace("''", "'", StringComparison.Ordinal);

        return value;
    }

    private static int ReadNumber(string block, string key)
        => int.TryParse(ReadScalar(block, key), NumberStyles.Integer, CultureInfo.InvariantCulture, out var value)
            ? value
            : 0;

    private static string BuildBlock(PluginSkillTreeSettings settings, string newline)
    {
        return string.Join(newline,
        [
            "skilltree:",
            $"  worldName: {Quote(settings.WorldName)}",
            $"  structureId: {Quote(settings.StructureId)}",
            "  center:",
            $"    x: {settings.CenterX.ToString(CultureInfo.InvariantCulture)}",
            $"    y: {settings.CenterY.ToString(CultureInfo.InvariantCulture)}",
            $"    z: {settings.CenterZ.ToString(CultureInfo.InvariantCulture)}",
            string.Empty
        ]);
    }

    private static string UpdateBlock(
        string existingBlock,
        PluginSkillTreeSettings settings,
        string newline)
    {
        var lines = existingBlock.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        var worldNameFound = ReplaceFirstScalar(lines, "worldName", Quote(settings.WorldName), 0, lines.Length);
        var structureIdFound = ReplaceFirstScalar(lines, "structureId", Quote(settings.StructureId), 0, lines.Length);

        var centerIndex = FindKeyLine(lines, "center", 0, lines.Length);
        var centerEnd = centerIndex < 0 ? -1 : FindIndentedBlockEnd(lines, centerIndex);
        var xFound = centerIndex >= 0
                     && ReplaceFirstScalar(
                         lines,
                         "x",
                         settings.CenterX.ToString(CultureInfo.InvariantCulture),
                         centerIndex + 1,
                         centerEnd);
        var yFound = centerIndex >= 0
                     && ReplaceFirstScalar(
                         lines,
                         "y",
                         settings.CenterY.ToString(CultureInfo.InvariantCulture),
                         centerIndex + 1,
                         centerEnd);
        var zFound = centerIndex >= 0
                     && ReplaceFirstScalar(
                         lines,
                         "z",
                         settings.CenterZ.ToString(CultureInfo.InvariantCulture),
                         centerIndex + 1,
                         centerEnd);

        return worldNameFound && structureIdFound && xFound && yFound && zFound
            ? string.Join(newline, lines)
            : BuildBlock(settings, newline);
    }

    private static bool ReplaceFirstScalar(
        string[] lines,
        string key,
        string replacement,
        int start,
        int end)
    {
        var pattern = new Regex(
            $@"^(?<prefix>[ \t]+{Regex.Escape(key)}[ \t]*:[ \t]*)(?<value>.*)$",
            RegexOptions.CultureInvariant);
        for (var index = start; index < end; index++)
        {
            var match = pattern.Match(lines[index]);
            if (!match.Success)
                continue;

            var existingValue = match.Groups["value"].Value;
            var commentIndex = FindYamlCommentStart(existingValue);
            var suffix = string.Empty;
            if (commentIndex >= 0)
            {
                var suffixStart = commentIndex;
                while (suffixStart > 0 && existingValue[suffixStart - 1] is ' ' or '\t')
                    suffixStart--;
                suffix = existingValue[suffixStart..];
            }

            lines[index] = match.Groups["prefix"].Value + replacement + suffix;
            return true;
        }

        return false;
    }

    private static int FindKeyLine(string[] lines, string key, int start, int end)
    {
        var pattern = new Regex(
            $@"^[ \t]+{Regex.Escape(key)}[ \t]*:",
            RegexOptions.CultureInvariant);
        for (var index = start; index < end; index++)
        {
            if (pattern.IsMatch(lines[index]))
                return index;
        }

        return -1;
    }

    private static int FindIndentedBlockEnd(string[] lines, int headerIndex)
    {
        var headerIndent = CountIndent(lines[headerIndex]);
        for (var index = headerIndex + 1; index < lines.Length; index++)
        {
            var trimmed = lines[index].TrimStart(' ', '\t');
            if (trimmed.Length == 0 || trimmed.StartsWith('#'))
                continue;
            if (CountIndent(lines[index]) <= headerIndent)
                return index;
        }

        return lines.Length;
    }

    private static int CountIndent(string value)
    {
        var count = 0;
        while (count < value.Length && value[count] is ' ' or '\t')
            count++;
        return count;
    }

    private static int FindYamlCommentStart(string value)
    {
        var inSingleQuotes = false;
        var inDoubleQuotes = false;
        var escaped = false;
        for (var index = 0; index < value.Length; index++)
        {
            var character = value[index];
            if (inDoubleQuotes)
            {
                if (escaped)
                {
                    escaped = false;
                    continue;
                }
                if (character == '\\')
                {
                    escaped = true;
                    continue;
                }
                if (character == '"')
                    inDoubleQuotes = false;
                continue;
            }

            if (inSingleQuotes)
            {
                if (character != '\'')
                    continue;
                if (index + 1 < value.Length && value[index + 1] == '\'')
                {
                    index++;
                    continue;
                }
                inSingleQuotes = false;
                continue;
            }

            if (character == '"')
                inDoubleQuotes = true;
            else if (character == '\'')
                inSingleQuotes = true;
            else if (character == '#' && (index == 0 || char.IsWhiteSpace(value[index - 1])))
                return index;
        }

        return -1;
    }

    private static string Quote(string value) => "\"" + value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("\"", "\\\"", StringComparison.Ordinal) + "\"";

    private static void Validate(PluginSkillTreeSettings settings)
    {
        if (string.IsNullOrWhiteSpace(settings.WorldName) || ContainsYamlForbiddenCharacter(settings.WorldName))
            throw new ArgumentException("worldName is required and must contain only valid single-line YAML characters.");
        if (!System.Text.RegularExpressions.Regex.IsMatch(
                settings.StructureId,
                "^[a-z0-9][a-z0-9_-]*$",
                RegexOptions.CultureInvariant))
            throw new ArgumentException("structureId may contain only lowercase letters, digits, '_' and '-'.");
    }

    private static bool ContainsYamlForbiddenCharacter(string value)
    {
        for (var index = 0; index < value.Length; index++)
        {
            var character = value[index];
            if (char.IsControl(character)
                || character is '\u2028' or '\u2029' or '\uFFFE' or '\uFFFF')
            {
                return true;
            }

            if (!char.IsSurrogate(character))
                continue;
            if (!char.IsHighSurrogate(character)
                || index + 1 >= value.Length
                || !char.IsLowSurrogate(value[index + 1]))
            {
                return true;
            }
            index++;
        }

        return false;
    }
}
