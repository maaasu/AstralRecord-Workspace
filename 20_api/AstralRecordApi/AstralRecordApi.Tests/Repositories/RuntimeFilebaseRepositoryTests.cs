using System.Text;
using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class RuntimeFilebaseRepositoryTests
{
    private static readonly string[] Directories =
    [
        "35.features.skilltree/nodes",
        "35.features.skilltree/structures",
        "45.features.shop",
        "85.shared.recipe",
        "51.features.quest",
        "52.features.quest_board",
        "42.features.gathering",
        "43.features.gathering.spawner",
        "41.features.mob.spawner",
        "65.features.dungeon",
    ];

    [Fact]
    public async Task GetSnapshot_IncludesOnlyRuntimeDefinitionsWithOriginalText()
    {
        using var fixture = new FilebaseFixture();
        var expected = new Dictionary<string, string>();
        foreach (var directory in Directories)
        {
            var extension = directory.StartsWith("35.features.skilltree/", StringComparison.Ordinal)
                ? ".json" : ".yml";
            var relativePath = directory + "/definition" + extension;
            var content = extension == ".json" ? "{\r\n  \"id\": 1\r\n}\r\n" : "id: test\r\nname: テスト\r\n";
            fixture.Write(relativePath, content);
            expected.Add(relativePath, content);
        }
        fixture.Write("42.features.gathering/mining/deep.yml", "id: deep\n");
        expected.Add("42.features.gathering/mining/deep.yml", "id: deep\n");
        fixture.Write("35.features.skilltree/schemas/private.json", "{}\n");
        fixture.Write("35.features.skilltree/node-id-sequence.json", "{}\n");
        fixture.Write("45.features.shop/docs.shop.yml", "not a runtime definition\n");
        fixture.Write("45.features.shop/other.json", "{}\n");
        fixture.Write("45.features.shop/nested/hidden.yml", "id: hidden\n");
        fixture.Write("99.work/private.yml", "private\n");
        fixture.Write("45.features.shop/ignored.YML", "id: ignored\n");
        fixture.Write("35.features.skilltree/nodes/uppercase.JSON", "{}\n");
        expected.Add("35.features.skilltree/nodes/uppercase.JSON", "{}\n");

        var snapshot = await fixture.Repository.GetSnapshotAsync();

        Assert.Equal(1, snapshot.SchemaVersion);
        Assert.Equal(expected.Keys.Order(StringComparer.Ordinal), snapshot.Files.Select(file => file.Path));
        foreach (var file in snapshot.Files)
            Assert.Equal(expected[file.Path], file.Content);
    }

    [Fact]
    public async Task GetSnapshot_RejectsMissingRuntimeDirectory()
    {
        using var fixture = new FilebaseFixture();
        Directory.Delete(Path.Combine(fixture.Root, "65.features.dungeon"));

        await Assert.ThrowsAsync<DirectoryNotFoundException>(() => fixture.Repository.GetSnapshotAsync());
    }

    [Fact]
    public async Task GetSnapshot_RejectsUnreadableUtf8()
    {
        using var fixture = new FilebaseFixture();
        File.WriteAllBytes(Path.Combine(fixture.Root, "45.features.shop", "bad.yml"), [0xff, 0xfe]);

        await Assert.ThrowsAsync<DecoderFallbackException>(() => fixture.Repository.GetSnapshotAsync());
    }

    private sealed class FilebaseFixture : IDisposable
    {
        public string Root { get; } = Path.Combine(Path.GetTempPath(), "astral-runtime-filebase-" + Guid.NewGuid().ToString("N"));

        public RuntimeFilebaseRepository Repository { get; }

        public FilebaseFixture()
        {
            foreach (var directory in Directories)
                Directory.CreateDirectory(Path.Combine(Root, directory.Replace('/', Path.DirectorySeparatorChar)));
            Repository = new RuntimeFilebaseRepository(
                Microsoft.Extensions.Options.Options.Create(new FileDatabaseOptions { RootPath = Root }));
        }

        public void Write(string relativePath, string content)
        {
            var path = Path.Combine(Root, relativePath.Replace('/', Path.DirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, content, new UTF8Encoding(false));
        }

        public void Dispose() => Directory.Delete(Root, recursive: true);
    }
}
