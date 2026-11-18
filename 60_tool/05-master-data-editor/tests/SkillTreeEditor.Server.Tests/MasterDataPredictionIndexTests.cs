using System.Text;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class MasterDataPredictionIndexTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), $"master-prediction-tests-{Guid.NewGuid():N}");
    private const string ItemPath = "10.features.item/v1.sword.yml";

    [Fact]
    public async Task IndexContainsApprovedProseWithExactTextAndEscapedPointers()
    {
        Write(ItemPath, """
            id: sword
            name: ' 星渡りの剣 '
            lore:
              - 星の光を蓄える
              - ''
              - 旅人の道を照らす
            settings/~notes:
              - displayName: 星明かり
                customText: 対象外
            """);

        var snapshot = await Snapshot();
        var results = MasterDataPredictionIndex.Build(snapshot, CancellationToken.None);

        Assert.Equal(new MasterDataPredictionEntry[]
        {
            new(ItemPath, "/name", " 星渡りの剣 "),
            new(ItemPath, "/lore", "星の光を蓄える\n\n旅人の道を照らす"),
            new(ItemPath, "/lore/0", "星の光を蓄える"),
            new(ItemPath, "/lore/2", "旅人の道を照らす"),
            new(ItemPath, "/settings~1~0notes/0/displayName", "星明かり"),
        }, results);
    }

    [Fact]
    public async Task IndexSkipsSchemasUnreadableDocumentsMetadataAndUnusableText()
    {
        Write("00.meta/generated.yml", "title: メタデータ\n");
        Write("config.yml", "name: 設定\n");
        Write("10.features.item/item.schema.json", """{"title":"スキーマ"}""");
        Write("10.features.item/a.invalid.yml", "name: [");
        File.WriteAllBytes(FullPath("10.features.item/b.invalid.yml"),
            [.. Encoding.UTF8.GetBytes("name: 候補"), 0xFF]);

        var usablePath = "10.features.item/c.text.json";
        Write(usablePath, new JsonObject
        {
            ["name"] = "minecraft:diamond_sword",
            ["title"] = "https://example.test/path",
            ["description"] = new string('文', 8_001),
            ["message"] = " \t",
            ["lore"] = new JsonArray("記録に残る一文", true),
            ["sourceRef"] = new JsonObject { ["name"] = "参照オブジェクト" },
            ["$meta"] = new JsonObject { ["name"] = "非表示のメタデータ" },
            ["LABEL"] = "星のしるし",
        }.ToJsonString());

        var results = MasterDataPredictionIndex.Build(await Snapshot(), CancellationToken.None);

        Assert.Equal(new MasterDataPredictionEntry[]
        {
            new(usablePath, "/lore/0", "記録に残る一文"),
            new(usablePath, "/LABEL", "星のしるし"),
        }, results);
    }

    [Fact]
    public async Task LatestSnapshotReflectsEditsAndRemovals()
    {
        Write(ItemPath, "name: 初版\n");
        var cache = Cache();
        Assert.Equal("初版", Assert.Single(MasterDataPredictionIndex.Build(
            await cache.GetAsync(CancellationToken.None), CancellationToken.None)!).Text);

        Write(ItemPath, "name: 新版\n");
        Assert.Equal("新版", Assert.Single(MasterDataPredictionIndex.Build(
            await cache.GetAsync(CancellationToken.None), CancellationToken.None)!).Text);

        File.Delete(FullPath(ItemPath));
        Assert.Empty(MasterDataPredictionIndex.Build(await cache.GetAsync(CancellationToken.None), CancellationToken.None)!);
    }

    [Fact]
    public void AnOversizedIndexIsNotReturnedAsACompleteSearch()
    {
        var content = new JsonObject { ["lore"] = new string('文', 8_000) };
        var document = new FilebaseDocument(ItemPath, FullPath(ItemPath), 0, DateTime.UtcNow,
            "", "test", content, [], null, null);
        var snapshot = new FilebaseSnapshot([], Enumerable.Repeat(document, 501).ToArray());
        Assert.Null(MasterDataPredictionIndex.Build(snapshot, CancellationToken.None));
    }

    private FilebaseSnapshotCache Cache() => new(new MasterDataPaths(new WorkspacePaths(_root)), new MasterDataCodec());

    private Task<FilebaseSnapshot> Snapshot() => Cache().GetAsync(CancellationToken.None);

    private string FullPath(string path) => Path.Combine(_root, "40_filebase", path);

    private void Write(string path, string raw)
    {
        var fullPath = FullPath(path);
        Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
        File.WriteAllText(fullPath, raw);
    }

    public void Dispose()
    {
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
