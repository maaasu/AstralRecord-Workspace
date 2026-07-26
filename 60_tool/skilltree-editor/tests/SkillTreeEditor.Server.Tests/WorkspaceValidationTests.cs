using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class WorkspaceValidationTests
{
    [Fact]
    public async Task CommittedSkillTreeMastersPassSchemaAndSemanticValidation()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var paths = new WorkspacePaths(root);
        var backups = new BackupService(paths);
        var schemas = new SchemaCatalog(paths);
        var pluginConfig = new PluginConfigService(paths, backups);
        var validation = new ValidationService(paths, schemas, pluginConfig, new MasterTagCatalog(paths));

        var report = await validation.ValidateAllAsync(CancellationToken.None);

        Assert.True(
            report.IsValid,
            string.Join(Environment.NewLine, report.Issues.Select(issue => $"{issue.Code}: {issue.File} {issue.Path} {issue.Message}")));
    }

    [Fact]
    public async Task CommittedMasterJsonAlreadyUsesStableCanonicalFormatting()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var paths = new WorkspacePaths(root);
        var files = Directory.EnumerateFiles(paths.Nodes, "*.json")
            .Concat(Directory.EnumerateFiles(paths.Structures, "*.json"))
            .Append(paths.NodeIdSequence);

        foreach (var file in files)
        {
            var text = await File.ReadAllTextAsync(file);
            var parsed = System.Text.Json.Nodes.JsonNode.Parse(text)!;
            Assert.Equal(text, StableJson.Serialize(parsed));
        }
    }

    [Fact]
    public async Task ClassCatalogExposesTheCommittedGameClassHierarchy()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var classes = await new ClassMasterCatalog(new WorkspacePaths(root)).ReadAllAsync(CancellationToken.None);

        Assert.DoesNotContain(classes, entry => entry.Id == "acolyte");
        foreach (var classId in new[] { "swordsman", "hunter", "mage" })
        {
            var entry = Assert.Single(classes, value => value.Id == classId);
            Assert.Contains("adventurer", entry.ParentClassIds);
        }
    }

    [Fact]
    public async Task SkillCatalogExposesJapaneseSkillInformation()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var skills = await new SkillMasterCatalog(new WorkspacePaths(root)).ReadAllAsync(CancellationToken.None);

        var ironWill = Assert.Single(skills, value => value.Id == "iron_will");
        Assert.Contains("アイアンウィル", ironWill.Name);
        Assert.Contains("被ダメージ", ironWill.Description);
        Assert.Equal("SKILL", ironWill.Type);
    }

    [Fact]
    public async Task MasterTagCatalogExposesJapaneseSkillTreeTagInformation()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var tags = await new MasterTagCatalog(new WorkspacePaths(root)).ReadAllAsync(CancellationToken.None);

        var primary = Assert.Single(tags, value => value.Id == "primary");
        Assert.Equal("基本能力", primary.DisplayName);
        Assert.Contains("SKILLTREE_NODE", primary.AppliesTo);
    }
}
