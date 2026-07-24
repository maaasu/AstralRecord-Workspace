using System.Text.RegularExpressions;

namespace SkillTreeEditor.Server.Services;

public static partial class SafePath
{
    [GeneratedRegex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", RegexOptions.CultureInvariant)]
    private static partial Regex IdentifierRegex();

    public static string RequireIdentifier(string value, string parameterName)
    {
        if (string.IsNullOrWhiteSpace(value) || !IdentifierRegex().IsMatch(value))
            throw new ArgumentException(
                $"{parameterName} may contain only letters, digits, '.', '_' and '-' and must not be empty.",
                parameterName);

        return value;
    }

    public static string UnderRoot(string root, string fileName)
    {
        var rootPath = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var candidate = Path.GetFullPath(Path.Combine(rootPath, fileName));
        if (!candidate.StartsWith(rootPath, StringComparison.OrdinalIgnoreCase))
            throw new UnauthorizedAccessException("The requested path is outside the configured root.");

        return candidate;
    }
}
