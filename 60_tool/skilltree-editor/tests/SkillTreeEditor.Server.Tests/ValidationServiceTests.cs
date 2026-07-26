using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class ValidationServiceTests
{
    [Fact]
    public void StructureValidationDetectsGraphAndReferenceErrors()
    {
        var structure = JsonNode.Parse("""
            {
              "$schema": "../schemas/structure.v1.schema.json",
              "schemaVersion": 1,
              "structureId": "main",
              "name": "Main",
              "rootNodeId": "1000",
              "nodes": [
                { "nodeId": "1000", "x": 0, "y": 0, "z": 0 },
                { "nodeId": "1001", "x": 20, "y": 0, "z": 0 },
                { "nodeId": "1002", "x": 20, "y": 0, "z": 0 },
                { "nodeId": "9999", "x": 40, "y": 0, "z": 0 }
              ],
              "edges": [
                { "sourceNodeId": "1000", "targetNodeId": "1001" },
                { "sourceNodeId": "1001", "targetNodeId": "1000" },
                { "sourceNodeId": "1002", "targetNodeId": "1002" }
              ]
            }
            """)!.AsObject();
        var report = new ValidationReport();

        ValidationService.ValidateStructureShape(
            structure,
            "main.json",
            new HashSet<string>(["1000", "1001", "1002"], StringComparer.Ordinal),
            report);

        var codes = report.Issues.Select(issue => issue.Code).ToHashSet();
        Assert.Contains("DUPLICATE_COORDINATE", codes);
        Assert.Contains("UNKNOWN_NODE_ID", codes);
        Assert.Contains("DUPLICATE_EDGE", codes);
        Assert.Contains("SELF_EDGE", codes);
        Assert.Contains("UNREACHABLE_NODE", codes);
        Assert.False(report.IsValid);
    }

    [Fact]
    public void ValidConnectedStructurePassesSemanticValidation()
    {
        var structure = JsonNode.Parse("""
            {
              "$schema": "../schemas/structure.v1.schema.json",
              "schemaVersion": 1,
              "structureId": "main",
              "name": "Main",
              "rootNodeId": "1000",
              "nodes": [
                { "nodeId": "1000", "x": 0, "y": 0, "z": 0 },
                { "nodeId": "1001", "x": 20, "y": 0, "z": 0 }
              ],
              "edges": [
                { "sourceNodeId": "1000", "targetNodeId": "1001" }
              ]
            }
            """)!.AsObject();
        var report = new ValidationReport();

        ValidationService.ValidateStructureShape(
            structure,
            "main.json",
            new HashSet<string>(["1000", "1001"], StringComparer.Ordinal),
            report);

        Assert.True(report.IsValid);
        Assert.Empty(report.Issues);
    }

    [Fact]
    public async Task SequenceBelowInitialFloorFailsSemanticValidation()
    {
        var workspace = Path.Combine(Path.GetTempPath(), $"skilltree-validation-tests-{Guid.NewGuid():N}");
        try
        {
            var paths = new WorkspacePaths(workspace);
            Directory.CreateDirectory(paths.Nodes);
            Directory.CreateDirectory(paths.Structures);
            Directory.CreateDirectory(paths.Schemas);
            Directory.CreateDirectory(Path.GetDirectoryName(paths.PluginConfig)!);
            await File.WriteAllTextAsync(paths.NodeIdSequence, """
                { "$schema": "./schemas/node-id-sequence.v1.schema.json", "schemaVersion": 1, "lastIssuedNodeId": "0" }
                """);
            await File.WriteAllTextAsync(Path.Combine(paths.Schemas, "node-id-sequence.v1.schema.json"), """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "x-astralrecord-entityKind": "generic",
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["$schema", "schemaVersion", "lastIssuedNodeId"],
                  "properties": {
                    "$schema": { "const": "./schemas/node-id-sequence.v1.schema.json" },
                    "schemaVersion": { "const": 1 },
                    "lastIssuedNodeId": { "type": "string", "pattern": "^(0|[1-9][0-9]*)$", "maxLength": 100 }
                  }
                }
                """);
            await File.WriteAllTextAsync(paths.PluginConfig, """
                skilltree:
                  worldName: "world"
                  structureId: "starter"
                  center:
                    x: 0
                    y: 0
                    z: 0
                """);
            Directory.CreateDirectory(Path.GetDirectoryName(paths.TagCatalog)!);
            var sourceRoot = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
            File.Copy(
                Path.Combine(sourceRoot, "40_filebase", "76.shared.tag", "v1.tags.yml"),
                paths.TagCatalog);
            var backups = new BackupService(paths);
            var validation = new ValidationService(
                paths,
                new SchemaCatalog(paths),
                new PluginConfigService(paths, backups),
                new MasterTagCatalog(paths));

            var report = await validation.ValidateAllAsync(CancellationToken.None);

            Assert.Contains(report.Issues, issue => issue.Code == "NODE_ID_SEQUENCE_BEHIND");
        }
        finally
        {
            if (Directory.Exists(workspace))
                Directory.Delete(workspace, recursive: true);
        }
    }
}
