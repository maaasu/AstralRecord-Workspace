using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class JapaneseValidationTests : IDisposable
{
    private readonly string _workspace = Path.Combine(Path.GetTempPath(), $"skilltree-japanese-tests-{Guid.NewGuid():N}");

    [Theory]
    [InlineData("required", "{\"required\":[\"name\"]}", "{}")]
    [InlineData("type", "{\"properties\":{\"name\":{\"type\":\"string\"}}}", "{\"name\":5}")]
    [InlineData("const", "{\"properties\":{\"name\":{\"const\":\"固定\"}}}", "{\"name\":\"別の値\"}")]
    public async Task SchemaErrorsHaveJapaneseExplanationAndKeepTechnicalContext(
        string keyword, string schemaText, string instanceText)
    {
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.Schemas);
        var schema = JsonNode.Parse(schemaText)!.AsObject();
        await File.WriteAllTextAsync(Path.Combine(paths.Schemas, "node.test.schema.json"), schema.ToJsonString());
        var instance = JsonNode.Parse(instanceText)!.AsObject();
        instance["$schema"] = "node.test.schema.json";

        var issues = await new SchemaCatalog(paths).ValidateAsync("node", instance, "1000.json", CancellationToken.None);

        Assert.NotEmpty(issues);
        Assert.All(issues, issue =>
        {
            Assert.Equal("JSON_SCHEMA", issue.Code);
            Assert.Equal("1000.json", issue.File);
            Assert.NotNull(issue.Path);
            Assert.Matches("[ぁ-んァ-ヶ一-龯]", issue.Message.Split("技術詳細", StringSplitOptions.None)[0]);
            Assert.Contains("技術詳細", issue.Message);
        });
        Assert.Contains(issues, issue => issue.Message.Contains($"（{keyword}）", StringComparison.Ordinal));
    }

    [Fact]
    public void SemanticErrorsHaveJapaneseExplanationAndKeepIdentifiers()
    {
        var report = new ValidationReport();
        var structure = new JsonObject
        {
            ["$schema"] = "structure.test.schema.json",
            ["schemaVersion"] = 1,
            ["structureId"] = "main",
            ["name"] = "テスト",
            ["rootNodeId"] = "missing",
            ["nodes"] = new JsonArray(new JsonObject { ["nodeId"] = "unknown", ["x"] = 0.15, ["y"] = 0, ["z"] = 0 }),
            ["edges"] = new JsonArray(new JsonObject { ["sourceNodeId"] = "unknown", ["targetNodeId"] = "unknown" })
        };

        ValidationService.ValidateStructureShape(structure, "main.json", new HashSet<string>(), report);
        ValidationService.ValidateNodeShape(new JsonObject(), "1000.json", report);

        Assert.NotEmpty(report.Issues);
        Assert.All(report.Issues, issue => Assert.Matches("[ぁ-んァ-ヶ一-龯]", issue.Message));
        var unknown = Assert.Single(report.Issues, issue => issue.Code == "UNKNOWN_NODE_ID" && issue.Path == "/nodes/0/nodeId");
        Assert.Equal("main.json", unknown.File);
        Assert.Contains("unknown", unknown.Message);
    }

    [Fact]
    public async Task MalformedJsonHasJapaneseExplanationBeforeParserDetails()
    {
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.Nodes);
        Directory.CreateDirectory(paths.Structures);
        await File.WriteAllTextAsync(Path.Combine(paths.Nodes, "1000.json"), "{");
        await File.WriteAllTextAsync(paths.NodeIdSequence, "{");
        var validation = new ValidationService(paths, new SchemaCatalog(paths),
            new PluginConfigService(paths, new BackupService(paths)), new MasterTagCatalog(paths));

        var report = await validation.ValidateAllAsync(CancellationToken.None);

        foreach (var code in new[] { "NODE_JSON_INVALID", "NODE_ID_SEQUENCE_INVALID" })
        {
            var issue = Assert.Single(report.Issues, issue => issue.Code == code);
            Assert.Matches("[ぁ-んァ-ヶ一-龯]", issue.Message.Split("詳細:", StringSplitOptions.None)[0]);
            Assert.Contains("詳細:", issue.Message);
            Assert.NotNull(issue.File);
        }
    }

    public void Dispose()
    {
        if (Directory.Exists(_workspace))
            Directory.Delete(_workspace, recursive: true);
    }
}
