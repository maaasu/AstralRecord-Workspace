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

    [Fact]
    public async Task IndexesFacetsFromTheirDefinedFieldsAcrossCategories()
    {
        Write("10.features.item/20.equipment/v1.sword.yml", """
            id: sword
            category: equipment
            rarity: EPIC
            equipment:
              slot: WEAPON
            tags: [fire, fire]
            """);
        Write("20.features.class/v1.guard.yml", """
            id: guard
            type: CLASS
            role: TANK
            classGui: { slot: 20 }
            tags: [tier1]
            """);
        Write("30.features.skill/v1.blessing.yml", "id: blessing\ntype: SKILL\npassive: { bindRequired: false }\ntags: [fire]\n");
        Write("30.features.skill/v1.slash.yml", "id: slash\ntype: SKILL\n");
        Write("40.features.mob/enemy/v1.boar.yml", "id: boar\ncategory: ENEMY\nentityType: PIG\ntags: [beast]\n");
        Write("42.features.gathering/mining/v1.ore.yml", "id: ore\ncategory: MINING\nrequiredToolTags: [PICKAXE]\n");
        Write("60.features.world/v1.wood.yml", "id: wood\nworldType: OVERWORLD\nautoLoad: false\n");
        Write("65.features.dungeon/v1.ruin.yml", "id: ruin\nentry: { worldRef: 'world:midgard_greenfields' }\n");
        Write("45.features.shop/v1.closed.yml", "id: closed\nenabled: false\n");
        Write("45.features.shop/v1.open.yml", "id: open\nenabled: true\n");

        var result = await _service.ReadAsync(CancellationToken.None);
        IReadOnlyDictionary<string, IReadOnlyList<string>> Facets(string suffix) =>
            Assert.Single(result.Entries, entry => entry.Path.EndsWith(suffix, StringComparison.Ordinal)).Facets;

        Assert.Equal(["equipment"], Facets("v1.sword.yml")["category"]);
        Assert.Equal(["EPIC"], Facets("v1.sword.yml")["rarity"]);
        Assert.Equal(["WEAPON"], Facets("v1.sword.yml")["slot"]);
        Assert.Equal(["fire"], Facets("v1.sword.yml")["tags"]);
        Assert.Equal(["TANK"], Facets("v1.guard.yml")["role"]);
        Assert.False(Facets("v1.guard.yml").ContainsKey("slot"));
        Assert.Equal(["true"], Facets("v1.blessing.yml")["passiveConfigured"]);
        Assert.Equal(["false"], Facets("v1.slash.yml")["passiveConfigured"]);
        Assert.Equal(["PIG"], Facets("v1.boar.yml")["entityType"]);
        Assert.Equal(["PICKAXE"], Facets("v1.ore.yml")["requiredToolTags"]);
        Assert.Equal(["OVERWORLD"], Facets("v1.wood.yml")["worldType"]);
        Assert.False(Facets("v1.wood.yml").ContainsKey("enabled"));
        Assert.Equal(["midgard_greenfields"], Facets("v1.ruin.yml")["world"]);
        Assert.Equal(["false"], Facets("v1.closed.yml")["enabled"]);
        Assert.Equal(["true"], Facets("v1.open.yml")["enabled"]);
        Assert.Empty(Facets("v1.tags.yml"));
        Assert.Equal(result.Entries.Select(entry => entry.Path).Order(StringComparer.Ordinal),
            result.Entries.Select(entry => entry.Path));
    }

    [Fact]
    public async Task TagsComeFromTopLevelStringArraysInEveryCategory()
    {
        Write("10.features.item/10.material/v1.wood.yml", "id: wood\ntags: [fire, custom]\n");
        Write("40.features.mob/enemy/v1.boar.yml", "id: boar\ntags: [beast, beast]\n");
        Write("45.features.shop/v1.shop.yml", "id: shop\ntags: [{ id: nested }]\n");

        var result = await _service.ReadAsync(CancellationToken.None);

        Assert.Equal(["beast", "custom", "fire"], result.Tags.Select(tag => tag.Id));
        Assert.Equal("炎", Assert.Single(result.Tags, tag => tag.Id == "fire").Name);
        Assert.Equal("beast", Assert.Single(result.Tags, tag => tag.Id == "beast").Name);
        Assert.Equal(["beast"], Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.boar.yml", StringComparison.Ordinal)).Facets["tags"]);
        Assert.False(Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.shop.yml", StringComparison.Ordinal)).Facets.ContainsKey("tags"));
        Assert.DoesNotContain(result.Tags, tag => tag.Id is "unused" or "nested" or "ACTIVITY");
    }

    [Fact]
    public async Task IndexesExactMobLevelValuesWithoutFillingProfileGaps()
    {
        Write("40.features.mob/enemy/v1.profiles.yml", """
            id: profiles
            level: 1
            levels:
              - level: 1
              - level: 20
              - level: 20
            """);
        Write("40.features.mob/npc/v1.no-level.yml", "id: no-level\n");
        Write("40.features.mob/enemy/v1.invalid.yml", "id: invalid\nlevel: '5'\nlevels: [{level: invalid}]\n");

        var result = await _service.ReadAsync(CancellationToken.None);

        Assert.Equal([1d, 20d], Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.profiles.yml", StringComparison.Ordinal)).Numbers!["mobLevel"]);
        Assert.DoesNotContain(result.Entries.Where(entry => entry.Path.EndsWith("v1.no-level.yml", StringComparison.Ordinal)
            || entry.Path.EndsWith("v1.invalid.yml", StringComparison.Ordinal)), entry => entry.Numbers!.ContainsKey("mobLevel"));
    }

    [Fact]
    public async Task ItemFiltersUseMasterDefaultsAndKeepEquipmentConditionsSeparate()
    {
        Write("10.features.item/10.material/v1.material.yml", "id: material\ncategory: material\n");
        Write("10.features.item/20.equipment/v1.default-equipment.yml", "id: default-equipment\nequipment: {slot: WEAPON}\n");
        Write("10.features.item/20.equipment/v1.restricted.yml", """
            id: restricted
            unTradeable: true
            unSellable: true
            maxStack: 1
            saleValue: 350
            equipment:
              slot: WEAPON
              handType: TWO
              tag: SPEAR
              requiredLevel: 15
              requiredClasses:
                - classId: swordsman
                  level: 10
                - classId: 'class:hunter'
              setId: sample-set
              enhance: {maxLevel: 10}
              enchant: {}
              durability: {max: 250}
              transcendence:
                - rank: 1
                  overrides:
                    enhance: {maxLevel: 30}
                    enchant: {maxSlots: 3}
            """);
        Write("10.features.item/20.equipment/v1.disabled.yml", "id: disabled\nequipment: {slot: CHEST, enchant: {maxSlots: 0}, enhance: {maxLevel: 0}}\n");
        Write("10.features.item/20.equipment/set_effect/v1.effect.yml", "id: effect\n");
        Write("10.features.item/20.equipment/v1.invalid-values.yml", """
            id: invalid-values
            unTradeable: invalid
            maxStack: invalid
            equipment:
              requiredLevel: invalid
              enchant: {maxSlots: invalid}
              enhance: {maxLevel: invalid}
            """);

        var result = await _service.ReadAsync(CancellationToken.None);
        var material = Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.material.yml", StringComparison.Ordinal));
        Assert.Equal(["false"], material.Facets["unTradeable"]);
        Assert.Equal(["false"], material.Facets["unSellable"]);
        Assert.Equal([64d], material.Numbers!["maxStack"]);
        Assert.Equal([0d], material.Numbers["saleValue"]);
        Assert.False(material.Numbers.ContainsKey("requiredLevel"));
        Assert.False(material.Facets.ContainsKey("enchantEnabled"));
        var defaultEquipment = Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.default-equipment.yml", StringComparison.Ordinal));
        Assert.Equal([0d], defaultEquipment.Numbers!["requiredLevel"]);
        Assert.Equal([0d], defaultEquipment.Numbers["enhanceMaxLevel"]);
        Assert.Equal([0d], defaultEquipment.Numbers["enchantMaxSlots"]);
        Assert.False(defaultEquipment.Numbers.ContainsKey("durabilityMax"));
        Assert.Equal(["ONE"], defaultEquipment.Facets["handType"]);
        Assert.Equal(["false"], defaultEquipment.Facets["classRestricted"]);
        Assert.Equal(["false"], defaultEquipment.Facets["enchantEnabled"]);
        var restricted = Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.restricted.yml", StringComparison.Ordinal));
        Assert.Equal(["true"], restricted.Facets["unTradeable"]);
        Assert.Equal(["true"], restricted.Facets["unSellable"]);
        Assert.Equal(["TWO"], restricted.Facets["handType"]);
        Assert.Equal(["SPEAR"], restricted.Facets["equipmentTag"]);
        Assert.Equal(["sample-set"], restricted.Facets["setId"]);
        Assert.Equal(["hunter", "swordsman"], restricted.Facets["requiredClasses"]);
        Assert.Equal(["true"], restricted.Facets["classRestricted"]);
        Assert.Equal(["true"], restricted.Facets["enchantEnabled"]);
        Assert.Equal(["true"], restricted.Facets["enhanceEnabled"]);
        Assert.Equal([15d], restricted.Numbers!["requiredLevel"]);
        Assert.Equal([350d], restricted.Numbers["saleValue"]);
        Assert.Equal([1d], restricted.Numbers["maxStack"]);
        Assert.Equal([10d], restricted.Numbers["enhanceMaxLevel"]);
        Assert.Equal([1d], restricted.Numbers["enchantMaxSlots"]);
        Assert.Equal([250d], restricted.Numbers["durabilityMax"]);
        var disabled = Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.disabled.yml", StringComparison.Ordinal));
        Assert.Equal(["false"], disabled.Facets["enchantEnabled"]);
        Assert.Equal(["false"], disabled.Facets["enhanceEnabled"]);
        Assert.False(disabled.Facets.ContainsKey("handType"));
        var setEffect = Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.effect.yml", StringComparison.Ordinal));
        Assert.Empty(setEffect.Facets);
        Assert.Empty(setEffect.Numbers!);
        var invalid = Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.invalid-values.yml", StringComparison.Ordinal));
        Assert.False(invalid.Facets.ContainsKey("unTradeable"));
        Assert.False(invalid.Facets.ContainsKey("enchantEnabled"));
        Assert.False(invalid.Facets.ContainsKey("enhanceEnabled"));
        Assert.False(invalid.Numbers!.ContainsKey("requiredLevel"));
        Assert.False(invalid.Numbers.ContainsKey("maxStack"));
        Assert.False(invalid.Numbers.ContainsKey("enhanceMaxLevel"));
        Assert.False(invalid.Numbers.ContainsKey("enchantMaxSlots"));
    }

    [Fact]
    public async Task CommittedMastersProvideFacetsAndDropSources()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var workspace = new WorkspacePaths(root);
        var service = new MasterDataFilterIndexService(new MasterDataPaths(workspace), new MasterDataCodec(), new MasterTagCatalog(workspace));

        var result = await service.ReadAsync(CancellationToken.None);

        Assert.Equal(["WEAPON"], Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.20a00031.anarion_spear.yml", StringComparison.Ordinal)).Facets["slot"]);
        Assert.Equal(["PICKAXE"], Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.box_cave_coal_vein.yml", StringComparison.Ordinal)).Facets["requiredToolTags"]);
        Assert.Equal(["midgard_greenfields"], Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.middle_earth_ruins.yml", StringComparison.Ordinal)).Facets["world"]);
        Assert.Equal(["true"], Assert.Single(result.Entries, entry => entry.Path == "30.features.skill/v1.adventurer_meditation.yml").Facets["passiveConfigured"]);
        Assert.Equal(["TANK"], Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.11.swordsman.yml", StringComparison.Ordinal)).Facets["role"]);
        Assert.Contains("10a00001", Assert.Single(result.Sources, source => source.Id == "midgard_grassboar").ItemIds);
        Assert.Equal("炎", Assert.Single(result.Tags, tag => tag.Id == "fire").Name);
        var spear = Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.20a00031.anarion_spear.yml", StringComparison.Ordinal));
        Assert.Equal([1000d], spear.Numbers!["saleValue"]);
        Assert.Equal(["false"], spear.Facets["unTradeable"]);
        Assert.Equal(["true"], Assert.Single(result.Entries, entry => entry.Path.EndsWith("v1.99a00022.verdia_waystone.yml", StringComparison.Ordinal)).Facets["unTradeable"]);
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
