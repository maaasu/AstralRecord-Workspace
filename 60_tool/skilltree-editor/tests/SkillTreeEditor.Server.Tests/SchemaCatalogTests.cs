using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class SchemaCatalogTests : IDisposable
{
    private readonly string _workspace = Path.Combine(Path.GetTempPath(), $"skilltree-schema-tests-{Guid.NewGuid():N}");

    [Fact]
    public async Task ValidationUsesSchemaReferencedByDocumentAndLatestOnlyAsDefault()
    {
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.Schemas);
        await File.WriteAllTextAsync(Path.Combine(paths.Schemas, "node.v1.schema.json"), Schema(1, "v1"));
        await File.WriteAllTextAsync(Path.Combine(paths.Schemas, "node.v2.schema.json"), Schema(2, "v2"));
        var catalog = new SchemaCatalog(paths);
        var document = JsonNode.Parse("""
            {
              "$schema": "../schemas/node.v1.schema.json",
              "schemaVersion": 1,
              "nodeId": "1000",
              "effects": [],
              "marker": "v2"
            }
            """)!.AsObject();

        var issues = await catalog.ValidateAsync("node", document, "1000.json", CancellationToken.None);
        var schemas = await catalog.ListAsync(CancellationToken.None);

        Assert.Contains(issues, issue => issue.Code == "JSON_SCHEMA");
        Assert.True(schemas.Single(schema => schema.FileName == "node.v2.schema.json").IsDefault);
        Assert.False(schemas.Single(schema => schema.FileName == "node.v1.schema.json").IsDefault);
    }

    [Fact]
    public async Task MissingReferencedSchemaIsAnError()
    {
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.Schemas);
        var catalog = new SchemaCatalog(paths);
        var document = new JsonObject { ["$schema"] = "../../schemas/node.v9.schema.json" };

        var issues = await catalog.ValidateAsync("node", document, "1000.json", CancellationToken.None);

        Assert.Contains(issues, issue => issue.Code == "SCHEMA_NOT_FOUND" && issue.Severity == "error");
    }

    [Fact]
    public async Task MalformedUnrelatedSchemaDoesNotBlockReferencedSchemaValidationOrCatalogListing()
    {
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.Schemas);
        await File.WriteAllTextAsync(Path.Combine(paths.Schemas, "node.v1.schema.json"), Schema(1, "v1"));
        await File.WriteAllTextAsync(Path.Combine(paths.Schemas, "unrelated.schema.json"), "{");
        var catalog = new SchemaCatalog(paths);
        var document = JsonNode.Parse("""
            {
              "$schema": "../schemas/node.v1.schema.json",
              "schemaVersion": 1,
              "nodeId": "1000",
              "effects": [],
              "marker": "v1"
            }
            """)!.AsObject();

        var issues = await catalog.ValidateAsync("node", document, "1000.json", CancellationToken.None);
        var schemas = await catalog.ListAsync(CancellationToken.None);
        var catalogIssues = await catalog.ValidateCatalogAsync(CancellationToken.None);

        Assert.Empty(issues);
        Assert.Equal("node.v1.schema.json", Assert.Single(schemas).FileName);
        var catalogIssue = Assert.Single(catalogIssues);
        Assert.Equal("SCHEMA_INVALID", catalogIssue.Code);
        Assert.Equal("unrelated.schema.json", catalogIssue.File);
    }

    [Fact]
    public async Task MalformedReferencedSchemaReturnsSchemaInvalidIssue()
    {
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.Schemas);
        await File.WriteAllTextAsync(Path.Combine(paths.Schemas, "node.broken.schema.json"), "{");
        var catalog = new SchemaCatalog(paths);
        var document = new JsonObject
        {
            ["$schema"] = "../schemas/node.broken.schema.json"
        };

        var issues = await catalog.ValidateAsync("node", document, "1000.json", CancellationToken.None);

        var issue = Assert.Single(issues);
        Assert.Equal("SCHEMA_INVALID", issue.Code);
        Assert.Equal("error", issue.Severity);
    }

    public void Dispose()
    {
        if (Directory.Exists(_workspace))
            Directory.Delete(_workspace, recursive: true);
    }

    private static string Schema(int version, string marker) => $$"""
        {
          "$id": "https://astralrecord.local/{{version}}",
          "type": "object",
          "required": ["$schema", "schemaVersion", "nodeId", "effects", "marker"],
          "properties": {
            "$schema": { "type": "string" },
            "schemaVersion": { "const": {{version}} },
            "nodeId": { "type": "string" },
            "effects": { "type": "array" },
            "marker": { "const": "{{marker}}" }
          }
        }
        """;
}
