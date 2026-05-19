using System.Collections.Concurrent;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace AstralRecordApi.Utilities;

public sealed class FileDatabaseConfigResolver
{
    private static readonly ConcurrentDictionary<string, FileDatabaseConfigResolver> Cache =
        new(StringComparer.OrdinalIgnoreCase);

    private static readonly StringComparer Comparer = StringComparer.OrdinalIgnoreCase;

    private readonly string _rootPath;
    private readonly IReadOnlyDictionary<string, string> _databasePathByName;
    private readonly IReadOnlySet<string> _referencePrefixes;

    private FileDatabaseConfigResolver(
        string rootPath,
        IReadOnlyDictionary<string, string> databasePathByName,
        IReadOnlySet<string> referencePrefixes)
    {
        _rootPath = rootPath;
        _databasePathByName = databasePathByName;
        _referencePrefixes = referencePrefixes;
    }

    public static FileDatabaseConfigResolver Load(string rootPath)
        => Cache.GetOrAdd(rootPath, Create);

    public bool TryGetDatabaseDirectory(string databaseName, out string directoryPath)
    {
        if (_databasePathByName.TryGetValue(databaseName, out var relativePath))
        {
            directoryPath = Path.Combine(_rootPath, relativePath);
            return true;
        }

        directoryPath = string.Empty;
        return false;
    }

    public bool TryResolveReferenceId(string referenceValue, out string id)
    {
        id = string.Empty;
        if (string.IsNullOrWhiteSpace(referenceValue))
            return false;

        var value = referenceValue.Trim();
        var separatorIndex = value.IndexOf(':');
        if (separatorIndex <= 0 || separatorIndex >= value.Length - 1)
            return false;

        var prefix = value[..separatorIndex].Trim();
        if (!_referencePrefixes.Contains(prefix))
            return false;

        id = value[(separatorIndex + 1)..].Trim();
        return !string.IsNullOrWhiteSpace(id);
    }

    private static FileDatabaseConfigResolver Create(string rootPath)
    {
        var configPath = Path.Combine(rootPath, "config.yml");
        if (!File.Exists(configPath))
            return new FileDatabaseConfigResolver(rootPath, new Dictionary<string, string>(Comparer), new HashSet<string>(Comparer));

        var yaml = File.ReadAllText(configPath);
        var deserializer = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build();

        FileDatabaseConfigDocument? document;
        try
        {
            document = deserializer.Deserialize<FileDatabaseConfigDocument>(yaml);
        }
        catch
        {
            return new FileDatabaseConfigResolver(rootPath, new Dictionary<string, string>(Comparer), new HashSet<string>(Comparer));
        }

        var databasePathByName = new Dictionary<string, string>(Comparer);
        foreach (var entry in document?.Database ?? [])
        {
            if (string.IsNullOrWhiteSpace(entry.Name) || string.IsNullOrWhiteSpace(entry.Path))
                continue;

            databasePathByName[entry.Name.Trim()] = entry.Path.Trim();
        }

        var referencePrefixes = new HashSet<string>(Comparer);
        foreach (var resolver in document?.ReferenceResolver ?? [])
        {
            AddPrefixToken(referencePrefixes, resolver.Prefix);
            foreach (var alias in resolver.Aliases ?? [])
                AddPrefixToken(referencePrefixes, alias);
        }

        return new FileDatabaseConfigResolver(rootPath, databasePathByName, referencePrefixes);
    }

    private static void AddPrefixToken(ISet<string> prefixes, string? rawValue)
    {
        if (string.IsNullOrWhiteSpace(rawValue))
            return;

        var token = rawValue.Trim().TrimEnd(':');
        if (token.Length == 0)
            return;

        prefixes.Add(token);
    }

    private sealed class FileDatabaseConfigDocument
    {
        public List<FileDatabaseEntry>? Database { get; init; }

        public List<ReferenceResolverEntry>? ReferenceResolver { get; init; }
    }

    private sealed class FileDatabaseEntry
    {
        public string? Name { get; init; }

        public string? Path { get; init; }
    }

    private sealed class ReferenceResolverEntry
    {
        public string? Prefix { get; init; }

        public List<string>? Aliases { get; init; }
    }
}