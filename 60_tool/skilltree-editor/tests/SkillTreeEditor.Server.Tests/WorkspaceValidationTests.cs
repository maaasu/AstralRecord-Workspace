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
        var validation = new ValidationService(paths, schemas, pluginConfig);

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
}
