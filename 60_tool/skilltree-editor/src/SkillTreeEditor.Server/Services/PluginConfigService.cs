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
        => (await ReadVersionedAsync(cancellationToken)).Content;

    public async Task<VersionedDocument<PluginSkillTreeSettings>> ReadVersionedAsync(CancellationToken cancellationToken)
    {
        if (!File.Exists(paths.PluginConfig))
            throw new FileNotFoundException("プラグインの config.yml が見つかりません。", paths.PluginConfig);

        var bytes = await File.ReadAllBytesAsync(paths.PluginConfig, cancellationToken);
        var text = Encoding.UTF8.GetString(bytes);
        if (text.StartsWith('\uFEFF')) text = text[1..];
        var revision = EditorRevision.Hash(bytes);
        var block = ExtractSkillTreeBlock(text);
        if (block is null)
            return new(new PluginSkillTreeSettings("world", string.Empty, 0, 0, 0), revision);

        return new(new PluginSkillTreeSettings(
            ReadScalar(block, "worldName") ?? "world",
            ReadScalar(block, "structureId") ?? string.Empty,
            ReadNumber(block, "x"),
            ReadNumber(block, "y"),
            ReadNumber(block, "z")), revision);
    }

    public async Task SaveAsync(PluginSkillTreeSettings settings, CancellationToken cancellationToken)
    {
        Validate(settings);
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            if (!File.Exists(paths.PluginConfig))
                throw new FileNotFoundException("プラグインの config.yml が見つかりません。", paths.PluginConfig);

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
        var lines = block.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        var indent = lines.Skip(1).Where(IsSettingLine).Select(CountIndent).DefaultIfEmpty(2).Min();
        var match = Regex.Match(
            block,
            $@"(?m)^[ \t]{{{indent}}}(?:{Regex.Escape(key)})[ \t]*:[ \t]*(?<value>[^\r\n]*)\r?$",
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
    {
        var lines = block.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n');
        var indent = lines.Skip(1).Where(IsSettingLine).Select(CountIndent).DefaultIfEmpty(2).Min();
        var centerIndex = Array.FindIndex(lines, line => CountIndent(line) == indent
            && Regex.IsMatch(line, @"^[ \t]+center[ \t]*:"));
        if (centerIndex < 0) return 0;
        var end = FindIndentedBlockEnd(lines, centerIndex);
        var centerBlock = string.Join("\n", lines[centerIndex..end]);
        return int.TryParse(ReadScalar(centerBlock, key), NumberStyles.Integer, CultureInfo.InvariantCulture, out var value) ? value : 0;
    }

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
        var lines = existingBlock.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n').ToList();
        var childIndent = lines.Skip(1).Where(IsSettingLine).Select(CountIndent).DefaultIfEmpty(2).Min();
        EnsureScalar(lines, "worldName", Quote(settings.WorldName), 1, lines.Count, childIndent);
        EnsureScalar(lines, "structureId", Quote(settings.StructureId), 1, lines.Count, childIndent);

        var centerIndex = lines.FindIndex(line => CountIndent(line) == childIndent
            && Regex.IsMatch(line, @"^[ \t]+center[ \t]*:"));
        if (centerIndex < 0)
        {
            centerIndex = TrailingBlankStart(lines, lines.Count);
            lines.Insert(centerIndex, new string(' ', childIndent) + "center:");
        }
        var centerValue = lines[centerIndex][(lines[centerIndex].IndexOf(':') + 1)..].Trim();
        if (centerValue.Length > 0 && !centerValue.StartsWith('#'))
            throw new ArgumentException("center は複数行のYAMLオブジェクトで記載してください。既存設定は変更していません。");
        foreach (var (key, value) in new[] { ("x", settings.CenterX), ("y", settings.CenterY), ("z", settings.CenterZ) })
        {
            var snapshot = lines.ToArray();
            var centerEnd = FindIndentedBlockEnd(snapshot, centerIndex);
            var coordinateIndent = lines.Skip(centerIndex + 1).Take(centerEnd - centerIndex - 1)
                .Where(IsSettingLine).Select(CountIndent).DefaultIfEmpty(childIndent + 2).Min();
            EnsureScalar(lines, key, value.ToString(CultureInfo.InvariantCulture), centerIndex + 1, centerEnd, coordinateIndent);
        }
        return string.Join(newline, lines);
    }

    private static bool IsSettingLine(string line) => !string.IsNullOrWhiteSpace(line) && !line.TrimStart().StartsWith('#');

    private static int TrailingBlankStart(List<string> lines, int end)
    {
        while (end > 1 && string.IsNullOrWhiteSpace(lines[end - 1])) end--;
        return end;
    }

    private static void EnsureScalar(List<string> lines, string key, string value, int start, int end, int indent)
    {
        var snapshot = lines.ToArray();
        if (ReplaceFirstScalar(snapshot, key, value, start, end, indent))
        {
            for (var index = start; index < end; index++) lines[index] = snapshot[index];
        }
        else lines.Insert(TrailingBlankStart(lines, end), new string(' ', indent) + key + ": " + value);
    }

    private static bool ReplaceFirstScalar(
        string[] lines,
        string key,
        string replacement,
        int start,
        int end,
        int indent)
    {
        var pattern = new Regex(
            $@"^(?<prefix>[ \t]+{Regex.Escape(key)}[ \t]*:[ \t]*)(?<value>.*)$",
            RegexOptions.CultureInvariant);
        for (var index = start; index < end; index++)
        {
            if (CountIndent(lines[index]) != indent) continue;
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
            throw new ArgumentException("worldNameは必須です。制御文字や改行を含まないYAMLで有効な文字を指定してください。");
        if (!System.Text.RegularExpressions.Regex.IsMatch(
                settings.StructureId,
                "^[a-z0-9][a-z0-9_-]*$",
                RegexOptions.CultureInvariant))
            throw new ArgumentException("structureIdは小文字英数字・_・-だけを使用してください。");
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
