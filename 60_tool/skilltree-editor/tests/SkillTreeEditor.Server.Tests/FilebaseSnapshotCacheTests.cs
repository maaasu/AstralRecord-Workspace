using System.Text;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class FilebaseSnapshotCacheTests : IDisposable
{
    private static readonly DateTime Settled = DateTime.UtcNow.AddMinutes(-10);
    private readonly string _root = Path.Combine(Path.GetTempPath(), "filebase-snapshot-tests-" + Guid.NewGuid().ToString("N"));
    private readonly WorkspacePaths _workspace;
    private readonly MasterDataPaths _paths;
    private readonly FilebaseSnapshotCache _cache;

    public FilebaseSnapshotCacheTests()
    {
        Directory.CreateDirectory(Path.Combine(_root, "40_filebase"));
        Directory.CreateDirectory(Path.Combine(_root, "10_plugin"));
        Directory.CreateDirectory(Path.Combine(_root, "60_tool"));
        _workspace = new WorkspacePaths(_root);
        _paths = new MasterDataPaths(_workspace);
        _cache = new FilebaseSnapshotCache(_paths, new MasterDataCodec());
    }

    [Fact]
    public async Task UnchangedFilebaseReturnsTheSameSnapshotAndDocuments()
    {
        Write("10.features.item/v1.a.yml", "id: a\n", Settled);
        Write("35.features.skilltree/nodes/1000.json", """{"nodeId":"1000","tags":["root"]}""", Settled);
        Write("10.features.item/README.md", "# 説明\n", Settled);

        var first = await _cache.GetAsync(CancellationToken.None);
        var second = await _cache.GetAsync(CancellationToken.None);

        Assert.Same(first, second);
        Assert.Equal(3, first.Entries.Count);
        Assert.Equal(["10.features.item/v1.a.yml", "35.features.skilltree/nodes/1000.json"],
            first.Documents.Select(document => document.Path).Order(StringComparer.Ordinal));
        var node = first.Documents.Single(document => document.Format == "json");
        Assert.Equal("root", node.Content!["tags"]![0]!.GetValue<string>());
        Assert.Equal(EditorRevision.Hash(File.ReadAllBytes(node.FullPath)), node.Revision);
    }

    [Fact]
    public async Task ConcurrentRequestsShareOneSnapshot()
    {
        for (var index = 0; index < 40; index++) Write($"10.features.item/v1.{index:D2}.yml", $"id: item{index}\n", Settled);

        var snapshots = await Task.WhenAll(Enumerable.Range(0, 16).Select(_ => _cache.GetAsync(CancellationToken.None)));

        Assert.All(snapshots, snapshot => Assert.Same(snapshots[0], snapshot));
        Assert.Equal(40, snapshots[0].Documents.Count);
    }

    [Fact]
    public async Task ChangedAddedAndRemovedFilesAreReflected()
    {
        Write("10.features.item/v1.a.yml", "id: a\n", Settled);
        Write("10.features.item/v1.b.yml", "id: b\n", Settled);
        var first = await _cache.GetAsync(CancellationToken.None);
        var unchanged = first.Documents.Single(document => document.Path.EndsWith("v1.b.yml", StringComparison.Ordinal));

        Write("10.features.item/v1.a.yml", "id: renamed\n", Settled.AddMinutes(1));
        Write("10.features.item/v1.c.yml", "id: c\n", Settled);
        var second = await _cache.GetAsync(CancellationToken.None);

        Assert.NotSame(first, second);
        Assert.Equal(["renamed", "b", "c"], second.Documents.Select(Id));
        // 変わっていないファイルは読み直さず、前回の文書をそのまま使う。
        Assert.Same(unchanged, second.Documents.Single(document => document.Path.EndsWith("v1.b.yml", StringComparison.Ordinal)));

        File.Delete(Full("10.features.item/v1.b.yml"));
        var third = await _cache.GetAsync(CancellationToken.None);
        Assert.Equal(["renamed", "c"], third.Documents.Select(Id));
    }

    [Fact]
    public async Task FileRewrittenWithTheSameLengthAndTimestampIsStillReadAgainWhileRecent()
    {
        var path = Write("10.features.item/v1.a.yml", "id: a\n");
        var written = File.GetLastWriteTimeUtc(path);
        Assert.Equal("a", Id((await _cache.GetAsync(CancellationToken.None)).Documents.Single()));

        // 更新時刻の刻みの中で同じ長さのまま書き換えられた状態を再現する。
        File.WriteAllText(path, "id: b\n", new UTF8Encoding(false));
        File.SetLastWriteTimeUtc(path, written);

        Assert.Equal("b", Id((await _cache.GetAsync(CancellationToken.None)).Documents.Single()));
    }

    [Fact]
    public async Task NonDataFileChangesProduceANewSnapshot()
    {
        Write("10.features.item/v1.a.yml", "id: a\n", Settled);
        var note = Write("10.features.item/docs.item.YAMLスキーマ定義.md", "# 旧\n", Settled);
        var first = await _cache.GetAsync(CancellationToken.None);

        File.WriteAllText(note, "# 新しい定義\n", new UTF8Encoding(false));
        File.SetLastWriteTimeUtc(note, Settled.AddMinutes(1));
        var second = await _cache.GetAsync(CancellationToken.None);

        Assert.NotSame(first, second);
        Assert.Same(first.Documents.Single(), second.Documents.Single());
    }

    [Fact]
    public async Task InvalidUtf8AndParseErrorsAreKeptPerDocument()
    {
        Write("10.features.item/v1.a.yml", "id: a\n", Settled);
        Write("10.features.item/v1.broken.yml", "id: [unterminated\n", Settled);
        var invalid = Full("10.features.item/v1.invalid.yml");
        File.WriteAllBytes(invalid, [0x69, 0x64, 0x3A, 0x20, 0xFF, 0xFE, 0x0A]);
        File.SetLastWriteTimeUtc(invalid, Settled);

        var snapshot = await _cache.GetAsync(CancellationToken.None);

        Assert.Equal("a", Id(snapshot.Documents[0].RequireReadable()));
        var broken = snapshot.Documents.Single(document => document.Path.EndsWith("v1.broken.yml", StringComparison.Ordinal)).RequireReadable();
        Assert.Null(broken.Content);
        Assert.Equal("PARSE_ERROR", Assert.Single(broken.Issues).Code);
        var undecodable = snapshot.Documents.Single(document => document.Path.EndsWith("v1.invalid.yml", StringComparison.Ordinal));
        Assert.NotNull(undecodable.DecodeError);
        Assert.Throws<DecoderFallbackException>(() => undecodable.RequireReadable());
    }

    [Fact]
    public async Task EntriesAndDirectoryScopesFollowTheEnumerationOrder()
    {
        foreach (var path in new[]
                 {
                     "config.yml", "10.features.item/v1.b.yml", "10.features.item/10.material/v1.z.yml",
                     "10.features.item/10.material/v1.a.yml", "10.features.item/20.equipment/set_effect/v1.s.yml",
                     "10.features.item/20.equipment/v1.e.yml", "10.features.item-extra/v1.other.yml",
                     "40.features.mob/enemy/v1.m.yml", "40.features.mob/notes.txt",
                 })
            Write(path, "id: x\n", Settled);

        var snapshot = await _cache.GetAsync(CancellationToken.None);

        Assert.Equal(ReferenceEnumeration(_paths.Root), snapshot.Entries.Select(entry => entry.FullPath));
        Assert.Equal(ReferenceEnumeration(_paths.Root), _paths.Enumerate());
        var items = Full("10.features.item");
        Assert.Equal(ReferenceEnumeration(items), snapshot.Under(items).Select(document => document.FullPath));
        Assert.Equal(ReferenceEnumeration(items), _paths.Enumerate("10.features.item"));
        Assert.All(snapshot.Entries, entry =>
        {
            var info = new FileInfo(entry.FullPath);
            Assert.Equal(info.Length, entry.Length);
            Assert.Equal(info.LastWriteTimeUtc, entry.LastWriteTimeUtc);
        });
    }

    [Fact]
    public async Task ListingObservesAnExternalRewriteImmediately()
    {
        var path = Write("70.shared.buff/v1.haste.yml", "id: haste\nname: 加速\n");
        Write("70.shared.buff/v1.slow.yml", "id: slow\nname: 鈍足\n", Settled);

        var service = CreateService();
        var before = await service.ListAsync("70.shared.buff", null, CancellationToken.None);
        File.WriteAllText(path, "id: haste\nname: 俊足\n", new UTF8Encoding(false));
        var after = await service.ListAsync("70.shared.buff", null, CancellationToken.None);
        var candidates = await service.CandidatesAsync(CancellationToken.None);

        Assert.Equal(["加速", "鈍足"], before.Select(file => file.Name));
        Assert.Equal(["俊足", "鈍足"], after.Select(file => file.Name));
        Assert.NotEqual(before[0].Revision, after[0].Revision);
        Assert.Equal(new FileInfo(path).Length, after[0].Size);
        Assert.Equal(["俊足", "鈍足"], candidates.Select(candidate => candidate.Label).Order(StringComparer.Ordinal));
        Assert.Empty(await service.ListAsync("70.shared.buff", "見つからない語", CancellationToken.None));
        Assert.Single(await service.ListAsync(null, "俊足", CancellationToken.None));
    }

    [Fact]
    public async Task ReloadReadsAnExternalRewriteWithAnUnchangedOldTimestamp()
    {
        var service = CreateService();
        var path = Write("70.shared.buff/v1.haste.yml", "id: haste\nname: 加速\n", Settled);
        var stamp = File.GetLastWriteTimeUtc(path);
        var before = Assert.Single(await service.ListAsync(null, null, CancellationToken.None));

        File.WriteAllText(path, "id: haste\nname: 俊足\n", new UTF8Encoding(false));
        File.SetLastWriteTimeUtc(path, stamp);
        var after = Assert.Single(await service.ListAsync(null, null, CancellationToken.None, reload: true));

        Assert.Equal("俊足", after.Name);
        Assert.NotEqual(before.Revision, after.Revision);
        var candidate = Assert.Single(await service.CandidatesAsync(CancellationToken.None));
        Assert.Equal("俊足", candidate.Label);
    }

    [Fact]
    public async Task DeleteChecksReferencesEvenWhenTheDiskAttributesHaveNotChanged()
    {
        var service = CreateService();
        const string buff = "70.shared.buff/v1.haste.yml";
        var buffPath = Write(buff, "id: haste\nname: 加速\n", Settled);
        var skill = Write("30.features.skill/v1.skill.yml", "id: skill\nbuffId: buff:other\n", Settled);
        var stamp = File.GetLastWriteTimeUtc(skill);
        await service.CandidatesAsync(CancellationToken.None);
        var revision = (await service.ReadAsync(buff, CancellationToken.None)).Revision;

        File.WriteAllText(skill, "id: skill\nbuffId: buff:haste\n", new UTF8Encoding(false));
        File.SetLastWriteTimeUtc(skill, stamp);

        var error = await Assert.ThrowsAsync<InvalidOperationException>(() => service.DeleteAsync(buff, revision, CancellationToken.None));
        Assert.Contains("30.features.skill/v1.skill.yml", error.Message);
        Assert.True(File.Exists(buffPath));
    }

    [Fact]
    public async Task ReferencesAndCandidatesDoNotOpenSchemaFiles()
    {
        if (!OperatingSystem.IsWindows()) return;
        const string buff = "70.shared.buff/v1.haste.yml";
        Write(buff, "id: haste\n", Settled);
        var schema = Write("46.features.fishing.area/schemas/area.schema.json", "{}", Settled);
        using var locked = File.Open(schema, FileMode.Open, FileAccess.ReadWrite, FileShare.None);
        var service = CreateService();

        Assert.Empty(await service.ReferencesAsync(buff, CancellationToken.None));
        Assert.Equal("haste", Assert.Single(await service.CandidatesAsync(CancellationToken.None)).Value);
    }

    [Fact]
    public async Task CategoryListingAndCatalogDoNotOpenUnrelatedData()
    {
        if (!OperatingSystem.IsWindows()) return;
        Write("70.shared.buff/v1.haste.yml", "id: haste\n", Settled);
        for (var index = 1; index <= 3; index++) Write($"10.features.item/v1.{index}.yml", $"id: item{index}\n", Settled);
        var unrelated = Write("10.features.item/v1.4.yml", "id: item4\n", Settled);
        using var locked = File.Open(unrelated, FileMode.Open, FileAccess.ReadWrite, FileShare.None);

        var file = Assert.Single(await CreateService().ListAsync("70.shared.buff", null, CancellationToken.None));
        Assert.Equal("haste", file.Id);
        var catalog = await new MasterDataCatalog(_paths).ReadAsync(_workspace.Backups, CancellationToken.None);
        Assert.Contains(catalog.Categories, category => category.Directory == "10.features.item" && category.FileCount == 4);
    }

    [Fact]
    public async Task SkillTreeRepositoryObservesAnExternalRewriteWithTheSameLengthAndTimestamp()
    {
        var repository = new FilebaseRepository(_workspace, new BackupService(_workspace));
        var path = Write("35.features.skilltree/nodes/1000.json", """{"nodeId":"1000","name":"A"}""");
        var written = File.GetLastWriteTimeUtc(path);
        var first = Assert.Single(await repository.ReadNodesAsync(CancellationToken.None));

        File.WriteAllText(path, """{"nodeId":"1000","name":"B"}""", new UTF8Encoding(false));
        File.SetLastWriteTimeUtc(path, written);
        var second = Assert.Single(await repository.ReadNodesAsync(CancellationToken.None));

        Assert.Equal("A", first.Content["name"]!.GetValue<string>());
        Assert.Equal("B", second.Content["name"]!.GetValue<string>());
        Assert.NotEqual(first.Revision, second.Revision);
    }

    [Fact]
    public async Task SkillTreeRepositoryReturnsIndependentDocumentsFromItsCache()
    {
        var repository = new FilebaseRepository(_workspace, new BackupService(_workspace));
        Write("35.features.skilltree/nodes/1000.json", """{"nodeId":"1000","name":"A"}""", Settled);
        Write("35.features.skilltree/nodes/1001.json", """{"nodeId":"1001","name":"B"}""", Settled);

        var first = await repository.ReadNodesAsync(CancellationToken.None);
        first[0].Content["name"] = "呼出側の変更";
        var second = await repository.ReadNodesAsync(CancellationToken.None);
        File.Delete(Full("35.features.skilltree/nodes/1001.json"));
        var third = await repository.ReadNodesAsync(CancellationToken.None);

        Assert.Equal(["A", "B"], second.Select(document => document.Content["name"]!.GetValue<string>()));
        Assert.Equal(first.Select(document => document.Revision), second.Select(document => document.Revision));
        Assert.Equal("1000.json", Assert.Single(third).FileName);
    }

    public void Dispose()
    {
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }

    private static string? Id(FilebaseDocument document) => (document.Content as JsonObject)?["id"]?.GetValue<string>();

    private string Full(string relative) => Path.Combine(_paths.Root, relative.Replace('/', Path.DirectorySeparatorChar));

    private MasterDataService CreateService()
    {
        var backups = new BackupService(_workspace);
        var tags = new MasterTagCatalog(_workspace);
        var tree = new ValidationService(_workspace, new SchemaCatalog(_workspace), new PluginConfigService(_workspace, backups), tags);
        var codec = new MasterDataCodec();
        var validation = new MasterDataValidation(_paths, codec, new MasterDataCatalog(_paths), tree);
        return new MasterDataService(_paths, _workspace, codec, validation, new WorkspaceMutationGate(_workspace), backups, _cache);
    }

    private string Write(string relative, string content, DateTime? lastWriteUtc = null)
    {
        var path = Full(relative);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, content, new UTF8Encoding(false));
        if (lastWriteUtc is { } time) File.SetLastWriteTimeUtc(path, time);
        return path;
    }

    /// <summary>キャッシュ導入前の列挙手順。順序を変えていないことの基準にする。</summary>
    private static List<string> ReferenceEnumeration(string root)
    {
        var result = new List<string>();
        var stack = new Stack<string>();
        stack.Push(root);
        while (stack.TryPop(out var current))
            foreach (var entry in Directory.EnumerateFileSystemEntries(current).Order(StringComparer.Ordinal))
            {
                if (Directory.Exists(entry)) stack.Push(entry);
                else result.Add(entry);
            }
        return result;
    }
}
