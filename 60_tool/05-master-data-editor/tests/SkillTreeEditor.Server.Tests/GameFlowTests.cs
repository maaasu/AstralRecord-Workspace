using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class GameFlowTests : IDisposable
{
    private const string FlowPath = "95.design.game_flow/v1.sample.yml";
    private readonly string _root = Path.Combine(Path.GetTempPath(), "game-flow-tests-" + Guid.NewGuid().ToString("N"));
    private readonly MasterDataPaths _paths;
    private readonly MasterDataValidation _validation;
    private readonly MasterDataCatalog _catalog;

    public GameFlowTests()
    {
        Directory.CreateDirectory(Path.Combine(_root, "40_filebase"));
        Directory.CreateDirectory(Path.Combine(_root, "10_plugin"));
        Directory.CreateDirectory(Path.Combine(_root, "60_tool"));
        var workspace = new WorkspacePaths(_root);
        _paths = new(workspace);
        var backups = new BackupService(workspace);
        var skillTree = new ValidationService(workspace, new SchemaCatalog(workspace), new PluginConfigService(workspace, backups), new MasterTagCatalog(workspace));
        _catalog = new(_paths);
        _validation = new(_paths, new MasterDataCodec(), _catalog, skillTree);

        Write("config.yml", """
            referenceResolver:
              - prefix: "item:"
                database: item
              - prefix: "shop:"
                database: shop
                aliases: ["sh"]
              - prefix: "class:"
                database: class
            """);
        Write("10.features.item/20.equipment/v1.sword.yml", """
            id: sword
            name: "剣"
            equipment:
              slot: WEAPON
              requiredClasses:
                - { classId: swordsman, level: 1 }
                - { classId: swordsman, level: 5 }
            """);
        Write("10.features.item/20.equipment/v1.bow.yml", "id: bow\nname: \"弓\"\nequipment:\n  slot: WEAPON\n");
        Write("45.features.shop/v1.weapon.yml", """
            id: weapon
            items:
              - itemId: { ref: item:sword }
              - itemId: item:bow
              - itemId: { ref: item:sword }
            """);
        Write("20.features.class/v1.11.swordsman.yml", "id: swordsman\n");
        Write("95.design.game_flow/schemas/game-flow.v1.schema.json", File.ReadAllText(RepositorySchema()));
    }

    private static string RepositorySchema()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        return Path.Combine(root, "40_filebase", "95.design.game_flow", "schemas", "game-flow.v1.schema.json");
    }

    private const string Valid = """
        schemaVersion: 1
        type: GAME_FLOW
        id: sample
        name: "サンプル"
        classes: [class:swordsman]
        nodes:
          - { id: shop, ref: sh:weapon, x: 0, y: 0 }
          - { id: sword, ref: item:sword, classes: [class:swordsman], x: 300, y: 0 }
          - { id: memo, label: "Lv10", x: 0, y: 100 }
        edges:
          - { from: shop, to: sword, label: "購入", classes: [class:swordsman] }
          - { from: memo, to: shop }
        """;

    [Fact]
    public async Task ValidFlowPassesSchemaReferencesAndStructure()
    {
        var report = await _validation.ValidateAsync(FlowPath, Valid, CancellationToken.None);
        Assert.True(report.IsValid, string.Join("\n", report.Issues.Select(issue => $"{issue.Code} {issue.Path} {issue.Message}")));
    }

    [Theory]
    [InlineData("- { from: memo, to: shop }", "- { from: memo, to: missing }", "GAME_FLOW_EDGE_NODE", "/edges/1/to")]
    [InlineData("- { from: memo, to: shop }", "- { from: memo, to: memo }", "GAME_FLOW_EDGE_SELF", "/edges/1")]
    [InlineData("- { from: memo, to: shop }", "- { from: shop, to: sword }", "GAME_FLOW_EDGE_DUPLICATE", "/edges/1")]
    [InlineData("{ id: memo, label: \"Lv10\"", "{ id: shop, label: \"Lv10\"", "GAME_FLOW_DUPLICATE_NODE", "/nodes/2/id")]
    [InlineData("classes: [class:swordsman] }", "classes: [class:mage] }", "REFERENCE_NOT_FOUND", "/edges/0/classes/0")]
    [InlineData("ref: item:sword", "ref: item:missing", "REFERENCE_NOT_FOUND", "/nodes/1/ref")]
    public async Task StructuralAndReferenceErrorsPointAtTheEntry(string from, string to, string code, string pointer)
    {
        Assert.Contains(from, Valid);
        var report = await _validation.ValidateAsync(FlowPath, Valid.Replace(from, to), CancellationToken.None);
        Assert.False(report.IsValid);
        Assert.Contains(report.Issues, issue => issue.Code == code && issue.Path == pointer);
    }

    [Fact]
    public async Task NodeWithoutReferenceOrLabelAndMismatchedFileNameAreRejected()
    {
        var report = await _validation.ValidateAsync("95.design.game_flow/v1.other.yml",
            Valid.Replace("{ id: memo, label: \"Lv10\", x: 0, y: 100 }", "{ id: memo, x: 0, y: 100 }"), CancellationToken.None);
        Assert.Contains(report.Issues, issue => issue.Code == "GAME_FLOW_NODE_EMPTY" && issue.Path == "/nodes/2");
        Assert.Contains(report.Issues, issue => issue.Code == "GAME_FLOW_FILENAME");
    }

    [Fact]
    public async Task CatalogLabelsCategoryAndUsesSchemaFields()
    {
        Write(FlowPath, Valid);
        var category = await _catalog.GetAsync("95.design.game_flow", CancellationToken.None);
        Assert.Equal("ゲーム進行フロー", category.Label);
        Assert.Contains(category.Fields, field => field.Path == "/nodes/*/ref");
    }

    [Fact]
    public async Task ContextListsShopItemsOnceAndEquipmentClasses()
    {
        var context = await new GameFlowService(new FilebaseSnapshotCache(_paths, new MasterDataCodec())).ReadContextAsync(CancellationToken.None);
        var shop = Assert.Single(context.Shops);
        Assert.Equal("weapon", shop.Id);
        Assert.Equal(["sword", "bow"], shop.Items);
        Assert.Equal(["swordsman"], context.ItemClasses["sword"]);
        Assert.False(context.ItemClasses.ContainsKey("bow"));
    }

    [Fact]
    public async Task CommittedFlowsPassValidation()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var workspace = new WorkspacePaths(root);
        var paths = new MasterDataPaths(workspace);
        var backups = new BackupService(workspace);
        var skillTree = new ValidationService(workspace, new SchemaCatalog(workspace), new PluginConfigService(workspace, backups), new MasterTagCatalog(workspace));
        var validation = new MasterDataValidation(paths, new MasterDataCodec(), new MasterDataCatalog(paths), skillTree);
        var flows = paths.Enumerate("95.design.game_flow").Where(MasterDataPaths.IsData).Select(paths.Relative).Where(GameFlowValidation.IsFlow).ToArray();
        Assert.NotEmpty(flows);
        foreach (var flow in flows)
        {
            var report = await validation.ValidateAsync(flow, await File.ReadAllTextAsync(paths.Resolve(flow)), CancellationToken.None);
            Assert.True(report.IsValid, flow + "\n" + string.Join("\n", report.Issues.Select(issue => $"{issue.Code} {issue.Path} {issue.Message}")));
        }
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
