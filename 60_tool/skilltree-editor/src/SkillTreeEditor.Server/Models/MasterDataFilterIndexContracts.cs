namespace SkillTreeEditor.Server.Models;

public sealed record MasterDataDropSource(string Path, string Id, string Name, string Kind,
    IReadOnlyList<string> ItemIds);

public sealed record MasterDataSkillTags(string Path, IReadOnlyList<string> Tags);

public sealed record MasterDataFilterTag(string Id, string Name);

public sealed record MasterDataFilterEntry(string Path, IReadOnlyDictionary<string, IReadOnlyList<string>> Facets);

public sealed record MasterDataFilterIndex(IReadOnlyList<MasterDataDropSource> Sources,
    IReadOnlyList<MasterDataSkillTags> SkillTags, IReadOnlyList<MasterDataFilterTag> Tags,
    IReadOnlyList<MasterDataFilterEntry> Entries);
