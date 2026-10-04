using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class MasterDataFilterIndexTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "master-filter-index-tests-" + Guid.NewGuid().ToString("N"));
    private readonly MasterDataPaths _paths;
    private readonly MasterDataFilterIndexService _service;

    public MasterDataFilterIndexTests()
    {
        Directory.CreateDirectory(Path.Combine(_root, "40_filebase"));
        Directory.CreateDirectory(Path.Combine(_root, "10_plugin"));
        Directory.CreateDirectory(Path.Combine(_root, "60_tool"));
        var workspace = new WorkspacePaths(_root);
        _paths = new MasterDataPaths(workspace);
        _service = new MasterDataFilterIndexService(_paths, new MasterDataCodec(), new MasterTagCatalog(workspace));
        Write("76.shared.tag/v1.tags.yml", """
            schemaVersion: 1
            categories:
              - id: ACTIVITY
            targets:
              - id: SKILL
            tags:
              - id: fire
                displayName: 炎
                description: 炎属性
                category: ACTIVITY
                appliesTo: [SKILL]
              - id: unused
                displayName: 未使用
                description: 未使用タグ
                category: ACTIVITY
                appliesTo: [SKILL]
            """);
    }

    [Fact]
    public async Task IndexCombinesMobLevelAndSharedLootAndGatheringDrops()
    {
        Write("80.shared.loot/table/v1.common.yml", """
            id: common
            pools:
              - ref: loot_pool:shared
              - ref: loot_pool:missing
            """);
        Write("80.shared.loot/pool/v1.shared.yml", """
            id: shared
            contents:
              - itemId: { ref: item:40a00003 }
              - itemId: item:20a00001
              - itemId: item:10a00001
            """);
        Write("40.features.mob/enemy/v1.boar.yml", """
            id: boar
            name: '&aグラスボア'
            drops:
              items:
                - itemId: { ref: item:10a00001 }
              lootTable: { ref: loot_table:common }
            levels:
              - level: 2
                drops:
                  items:
                    - itemId: item:20a00002
                  lootTable: lt:common
            """);
        Write("40.features.mob/npc/v1.merchant.yml", "id: merchant\nname: 商人\n");
        Write("42.features.gathering/harvesting/v1.herb.yml", """
            id: herb
            name: '&a若葉'
            drops:
              items:
                - itemId: item:10a00003
                - itemId: { ref: im:10a00004 }
            """);

        var result = await _service.ReadAsync(CancellationToken.None);

        Assert.Equal(3, result.Sources.Count);
        var mob = Assert.Single(result.Sources, source => source.Id == "boar");
        Assert.Equal("mob", mob.Kind);
        Assert.Equal("&aグラスボア", mob.Name);
        Assert.Equal("40.features.mob/enemy/v1.boar.yml", mob.Path);
        Assert.Equal(["10a00001", "20a00001", "20a00002", "40a00003"], mob.ItemIds);
        Assert.Empty(Assert.Single(result.Sources, source => source.Id == "merchant").ItemIds);
        var gathering = Assert.Single(result.Sources, source => source.Id == "herb");
        Assert.Equal("gathering", gathering.Kind);
        Assert.Equal(["10a00003", "10a00004"], gathering.ItemIds);
    }

    [Fact]
    public async Task IndexReturnsSkillTagsWithCatalogNamesAndReflectsFileEdits()
    {
        Write("30.features.skill/v1.flame.yml", "id: flame\ntags: [fire, fire, custom]\n");
        var first = await _service.ReadAsync(CancellationToken.None);
        var skill = Assert.Single(first.SkillTags);
        Assert.Equal("30.features.skill/v1.flame.yml", skill.Path);
        Assert.Equal(["custom", "fire"], skill.Tags);
        Assert.Equal(["custom", "fire"], first.Tags.Select(tag => tag.Id));
        Assert.Equal("炎", Assert.Single(first.Tags, tag => tag.Id == "fire").Name);
        Assert.Equal("custom", Assert.Single(first.Tags, tag => tag.Id == "custom").Name);
        Assert.DoesNotContain(first.Tags, tag => tag.Id == "unused");

        Write("30.features.skill/v1.flame.yml", "id: flame\ntags: [fire]\n");
        var updated = await _service.ReadAsync(CancellationToken.None);
        Assert.Equal(["fire"], Assert.Single(updated.SkillTags).Tags);
        Assert.Equal("fire", Assert.Single(updated.Tags).Id);
    }

    private void Write(string path, string raw)
    {
        var full = Path.Combine(_paths.Root, path.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        File.WriteAllText(full, raw);
    }

    public void Dispose()
    {
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
