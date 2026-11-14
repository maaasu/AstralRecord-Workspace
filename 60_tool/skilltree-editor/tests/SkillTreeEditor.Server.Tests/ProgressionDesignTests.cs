using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class ProgressionDesignTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "progression-design-" + Guid.NewGuid().ToString("N"));
    private readonly ProgressionDesignService _service;

    public ProgressionDesignTests()
    {
        Directory.CreateDirectory(Path.Combine(_root, "40_filebase"));
        Directory.CreateDirectory(Path.Combine(_root, "10_plugin"));
        Directory.CreateDirectory(Path.Combine(_root, "60_tool"));
        var paths = new MasterDataPaths(new WorkspacePaths(_root));
        _service = new ProgressionDesignService(new FilebaseSnapshotCache(paths, new MasterDataCodec()), paths);
    }

    [Fact]
    public async Task ShopUsesOnlyShopRecipesAndKeepsEveryEditableCostLocation()
    {
        const string shopPath = "45.features.shop/v1.shop.yml";
        const string recipePath = "85.shared.recipe/v1.exchange.yml";
        Write(recipePath, """
            id: exchange
            category: SHOP
            result: { itemId: { ref: "item:result" }, amount: 3 }
            requiredCurrency: 12
            ingredients:
              - { itemId: { ref: "item:ore" }, amount: 4 }
            """);
        Write("85.shared.recipe/v1.alchemy.yml", """
            id: alchemy
            category: ALCHEMY
            result: { itemId: "item:potion", amount: 3 }
            """);
        Write(shopPath, """
            id: shop
            mode: EXCHANGE
            access: PUBLIC
            items:
              - itemId: { ref: "item:result" }
                recipeId: { ref: "recipe:exchange" }
                priceGold: 20
                amount: 1
                requiredItems:
                  - { itemId: "item:ore", amount: 2 }
              - itemId: "item:potion"
                recipeId: "recipe:alchemy"
            """);
        var result = await _service.ReadAsync(default);

        var shop = Assert.Single(result.Sources, route => route.Kind == "shop" && route.ItemId == "result");
        Assert.Equal("configured", shop.Availability);
        Assert.Equal("/items/0/itemId/ref", shop.ItemPointer);
        Assert.Equal("/items/0/requiredItems", shop.IngredientContainer!.Pointer);
        Assert.Equal([shopPath, recipePath], shop.Ingredients.Select(ingredient => ingredient.Path));
        Assert.Equal(["/items/0/requiredItems/0/itemId", "/ingredients/0/itemId/ref"],
            shop.Ingredients.Select(ingredient => ingredient.ItemPointer));
        Assert.Equal([20, 12], shop.Costs.Select(cost => JsonValueReader.Number(cost.Value)));
        Assert.Equal(recipePath, Assert.Single(shop.Context).Path);
        Assert.Equal("definitionOnly", Assert.Single(result.Sources, route => route.Kind == "shop" && route.ItemId == "potion").Availability);
        Assert.All(result.Sources.Where(route => route.Kind == "recipe"), route => Assert.Equal("definitionOnly", route.Availability));
        Assert.Contains(result.Usages, usage => usage.ItemId == "ore" && usage.OutputItemId == "result");
    }

    [Fact]
    public async Task LevelOverridesKeepInheritedLootLocationsAndRespectExplicitNull()
    {
        const string poolPath = "80.shared.loot/pool/v1.common.yml";
        const string tablePath = "80.shared.loot/table/v1.common.yml";
        const string mobPath = "40.features.mob/enemy/v1.boar.yml";
        Write(poolPath, """
            id: common
            pick: 1
            contents:
              - itemId: { ref: "item:orb" }
                rate: 40
                amount: "1~3"
            """);
        Write(tablePath, """
            id: common
            rolls: "1~2"
            pools:
              - ref: "loot_pool:common"
            """);
        Write(mobPath, """
            id: boar
            drops:
              lootTable: { ref: "loot_table:common" }
              items:
                - { itemId: "item:meat", rate: 50, amount: 1 }
            levels:
              - level: 3
                drops:
                  exp: 250
              - level: 4
                drops:
                  lootTable: null
              - level: 5
                drops: null
            """);
        Write("41.features.mob.spawner/v1.boar.yml", """
            id: boar
            spawnMobs:
              - { mobId: "mob:boar", level: 3 }
            """);
        var result = await _service.ReadAsync(default);
        var mobOrb = Assert.Single(result.Sources, route => route.Kind == "mob" && route.ItemId == "orb");
        Assert.Equal(3, mobOrb.Level);
        Assert.Equal("configured", mobOrb.Availability);
        Assert.Equal(poolPath, mobOrb.ItemPath);
        Assert.Equal("/contents/0/itemId/ref", mobOrb.ItemPointer);
        Assert.Equal("1~3", Assert.Single(mobOrb.Quantity).Value.GetValue<string>());
        Assert.Equal([tablePath, poolPath, poolPath], mobOrb.Rates.Select(rate => rate.Path));
        Assert.Contains(mobOrb.Context, context => context.Path == mobPath && context.Pointer == "/drops/lootTable/ref");
        Assert.Contains(mobOrb.Context, context => context.Kind == "mobSpawner");
        Assert.Equal([3, 4], result.Sources.Where(route => route.Kind == "mob" && route.ItemId == "meat").Select(route => route.Level));
        Assert.Contains(result.AddTargets, target => target.Path == mobPath && target.Pointer == "/levels/1/drops/items");
        Assert.All(result.Sources.Where(route => route.Kind is "lootPool" or "lootTable"),
            route => Assert.Equal("definitionOnly", route.Availability));
    }

    [Fact]
    public async Task PreviewRebuildsReferencesAndCompatibleEquipmentWithoutSaving()
    {
        const string path = "36.features.progression_preset/v1.build.yml";
        Write(path, """
            id: build
            classId: swordsman
            buildId: sword
            levels:
              - level: 7
                classLevel: 12
                equipment:
                  - { slot: MAIN_HAND, item: "item:sword" }
            """);
        Write("20.features.class/v1.swordsman.yml", """
            id: swordsman
            unlockClassLevel:
              - { class: adventurer, level: 10 }
            """);
        Write("10.features.item/20.equipment/v1.sword.yml", """
            id: sword
            equipment:
              slot: WEAPON
              requiredLevel: 9
              requiredClasses:
                - { classId: "class:swordsman", level: 3 }
              enhance:
                maxLevel: 3
                levels:
                  - { level: 1 }
                  - { level: 3 }
            """);
        var saved = await _service.ReadAsync(default);
        var item = Assert.Single(saved.Items);
        Assert.Equal(["MAIN_HAND"], item.CompatibleSlots);
        Assert.Equal(["swordsman"], item.RequiredClasses);
        Assert.Equal(9, item.RequiredLevel);
        Assert.Equal(1, item.MaxEnhanceLevel);
        Assert.Equal(["adventurer"], Assert.Single(saved.Classes).PrerequisiteClassIds);
        var draft = JsonNode.Parse("""
            {"id":"build","classId":"swordsman","buildId":"sword","levels":[{"level":8,"classLevel":12,
            "equipment":[{"slot":"MAIN_HAND","item":"item:axe","enhanceLevel":2}]}]}
            """)!.AsObject();
        var preview = await _service.PreviewAsync(new([new(path, draft)]), default);
        var checkpoint = Assert.Single(Assert.Single(preview.Presets).Checkpoints);
        Assert.Equal(8, checkpoint.Level);
        Assert.Equal("axe", Assert.Single(checkpoint.Equipment).ItemId);
        Assert.Equal(2, Assert.Single(checkpoint.Equipment).EnhanceLevel);
        Assert.Equal("/levels/0/equipment/0", Assert.Single(preview.Usages).Pointer);

        var persisted = await _service.ReadAsync(default);
        Assert.Equal("sword", Assert.Single(persisted.Usages).ItemId);
        await Assert.ThrowsAsync<ArgumentException>(() =>
            _service.PreviewAsync(new([new("../build.yml", draft)]), default));
        await Assert.ThrowsAsync<ArgumentException>(() =>
            _service.PreviewAsync(new([new(path, draft), new(path, draft)]), default));
    }

    [Fact]
    public async Task RouteIdsDistinguishProductsDropRowsAndInheritedMobLevels()
    {
        const string shopPath = "45.features.shop/v1.shop.yml";
        Write(shopPath, """
            id: shop
            items:
              - { id: first, itemId: "item:sword", amount: 1 }
              - { id: second, itemId: "item:sword", amount: 2 }
              - { id: third, itemId: "item:shield", amount: 1 }
            """);
        Write("40.features.mob/enemy/v1.boar.yml", """
            id: boar
            drops:
              items:
                - { itemId: "item:sword", rate: 50, amount: 1 }
                - { itemId: "item:sword", rate: 60, amount: 2 }
            levels:
              - { level: 3 }
              - { level: 4 }
            """);

        var saved = await _service.ReadAsync(default);
        Assert.Equal(3, saved.Sources.Count(route => route.Kind == "shop"));
        Assert.Equal(4, saved.Sources.Count(route => route.Kind == "mob"));
        Assert.Equal(7, saved.Sources.Select(route => route.Id).Distinct(StringComparer.Ordinal).Count());

        var draft = JsonNode.Parse("""
            {"id":"shop","items":[{"id":"first","itemId":"item:sword","amount":9},
            {"id":"second","itemId":"item:sword","amount":2},{"id":"third","itemId":"item:shield","amount":1}]}
            """)!.AsObject();
        var preview = await _service.PreviewAsync(new([new(shopPath, draft, saved.Revisions[shopPath])]), default);
        Assert.Equal(saved.Sources.Select(route => route.Id), preview.Sources.Select(route => route.Id));
        Assert.Equal(9, JsonValueReader.Number(Assert.Single(preview.Sources.First(route => route.Path == shopPath).Quantity).Value));
    }

    [Fact]
    public async Task PreviewRejectsRevisionOfDocumentChangedSinceCatalogRead()
    {
        const string path = "42.features.gathering/harvesting/v1.herb.yml";
        Write(path, "id: herb\ndrops: { exp: 1 }\n");
        var saved = await _service.ReadAsync(default);

        Write(path, "id: herb\ndrops: { exp: 123 }\n");
        var changed = await _service.ReadAsync(default);
        Assert.NotEqual(saved.Revisions[path], changed.Revisions[path]);
        var draft = JsonNode.Parse("""{"id":"herb","drops":{"exp":9}}""")!.AsObject();
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            _service.PreviewAsync(new([new(path, draft, saved.Revisions[path])]), default));
        var preview = await _service.PreviewAsync(new([new(path, draft, changed.Revisions[path])]), default);
        Assert.Equal(changed.Revisions[path], preview.Revisions[path]);
    }

    [Theory]
    [InlineData("0", "1", "definitionOnly")]
    [InlineData("1", "0", "definitionOnly")]
    [InlineData("'0~0'", "1", "definitionOnly")]
    [InlineData("1", "'0~0'", "definitionOnly")]
    [InlineData("'0~1'", "'1~0'", "configured")]
    public async Task LootRequiresPositiveRollsAndPicks(string rolls, string pick, string availability)
    {
        Write("80.shared.loot/table/v1.common.yml", $"""
            id: common
            rolls: {rolls}
            pools: ["loot_pool:common"]
            """);
        Write("80.shared.loot/pool/v1.common.yml", $"""
            id: common
            pick: {pick}
            contents:
              - itemId: "item:orb"
                rate: 100
                amount: 1
            """);
        Write("65.features.dungeon/v1.ruin.yml", """
            id: ruin
            clearRewards:
              lootTable: "loot_table:common"
            """);

        var result = await _service.ReadAsync(default);
        var route = Assert.Single(result.Sources, source => source.Kind == "dungeon");
        Assert.Equal(availability, route.Availability);
        Assert.Equal(availability == "definitionOnly", route.Warnings.Count > 0);
        Assert.Equal(["/rolls", "/pick", "/contents/0/rate"], route.Rates.Select(field => field.Pointer));
    }

    [Fact]
    public async Task LinksDungeonRewardToWorldEntranceRequirement()
    {
        const string path = "60.features.world/v1.verdia.yml";
        Write(path, """
            id: verdia
            displayName: Verdia
            requiredItemId:
              ref: item:verdia_token
            """);
        Write("65.features.dungeon/v1.ruin.yml", """
            id: ruin
            clearRewards:
              items:
                - { itemId: "item:verdia_token", rate: 100, amount: 1 }
            """);

        var result = await _service.ReadAsync(default);
        var source = Assert.Single(result.Sources);
        Assert.Equal("dungeon", source.Kind);
        var usage = Assert.Single(result.Usages, usage => usage.ItemId == source.ItemId);
        Assert.Equal("world", usage.Kind);
        Assert.Contains("入場条件", usage.Name);
        Assert.Equal(path, usage.Path);
        Assert.Equal("/requiredItemId/ref", usage.Pointer);
        Assert.Null(usage.OutputItemId);
        Assert.Empty(usage.Quantity);
    }

    private void Write(string path, string content)
    {
        var fullPath = Path.Combine(_root, "40_filebase", path);
        Directory.CreateDirectory(Path.GetDirectoryName(fullPath)!);
        File.WriteAllText(fullPath, content);
    }

    public void Dispose() => Directory.Delete(_root, true);
}
