using System.Diagnostics;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Tests;

public sealed class WorkspaceBoundaryTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "editor-boundary-" + Guid.NewGuid().ToString("N"));
    private readonly List<string> _links = [];
    private readonly WorkspacePaths _paths;
    private readonly FilebaseRepository _repository;
    private readonly BackupService _backups;

    public WorkspaceBoundaryTests()
    {
        var workspace = Path.Combine(_root, "workspace");
        foreach (var name in new[] { "40_filebase", "10_plugin", "60_tool" }) Directory.CreateDirectory(Path.Combine(workspace, name));
        _paths = new(workspace);
        Directory.CreateDirectory(_paths.SkillTreeRoot);
        File.WriteAllText(_paths.NodeIdSequence, "{\"$schema\":\"./schemas/node-id-sequence.v1.schema.json\",\"schemaVersion\":1,\"lastIssuedNodeId\":\"999\"}");
        _backups = new(_paths);
        _repository = new(_paths, _backups);
    }

    [Fact]
    public async Task LinkedNodesCannotBeReadSavedDeletedOrAllocated()
    {
        var external = Outside("nodes");
        var target = Path.Combine(external, "1000.json");
        var original = "{\"nodeId\":\"1000\",\"name\":\"external\"}";
        File.WriteAllText(target, original);
        Link(_paths.Nodes, external);
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.ReadNodesAsync(CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.SaveNodeAsync("1000", new JsonObject { ["nodeId"] = "1000" }, CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.DeleteNodeAsync("1000", CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.CreateNodeAsync(new JsonObject(), CancellationToken.None));
        Assert.Equal(original, await File.ReadAllTextAsync(target));
        Assert.Contains("\"999\"", await File.ReadAllTextAsync(_paths.NodeIdSequence));
    }

    [Fact]
    public async Task LinkedStructuresCannotBeReadSavedOrCreated()
    {
        var external = Outside("structures");
        var target = Path.Combine(external, "main.json");
        const string original = "{\"structureId\":\"main\"}";
        File.WriteAllText(target, original);
        Link(_paths.Structures, external);
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.ReadStructuresAsync(CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.SaveStructureAsync("main", new JsonObject { ["structureId"] = "main" }, CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.CreateStructureAsync(new JsonObject { ["structureId"] = "new" }, CancellationToken.None));
        Assert.Equal(original, await File.ReadAllTextAsync(target));
        Assert.False(File.Exists(Path.Combine(external, "new.json")));
    }

    [Fact]
    public async Task LinkedPluginConfigDirectoryCannotBeReadOrSaved()
    {
        var external = Outside("plugin-config");
        var target = Path.Combine(external, "config.yml");
        const string original = "skilltree:\n  worldName: external\n  structureId: main\n  center:\n    x: 1\n    y: 2\n    z: 3\n";
        File.WriteAllText(target, original);
        Link(Path.GetDirectoryName(_paths.PluginConfig)!, external);
        var service = new PluginConfigService(_paths, _backups);
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => service.ReadAsync(CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => service.SaveAsync(new("changed", "main", 4, 5, 6), CancellationToken.None));
        Assert.Equal(original, await File.ReadAllTextAsync(target));
    }

    [Fact]
    public async Task LinkedBackupsCannotReceiveCopiesOrAllocationLocks()
    {
        var external = Outside("backups");
        Link(_paths.Backups, external);
        Directory.CreateDirectory(_paths.Nodes);
        var target = Path.Combine(_paths.Nodes, "1000.json");
        File.WriteAllText(target, "{\"nodeId\":\"1000\"}");
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _backups.BackupAsync(target, "nodes", CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.GetNextNodeIdAsync(CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(async () => { await using var lease = await new WorkspaceMutationGate(_paths).EnterAsync(CancellationToken.None); });
        Assert.Empty(Directory.EnumerateFileSystemEntries(external));
    }

    [Fact]
    public async Task LinkedBackupCategoryAndLockDirectoryCannotReceiveWrites()
    {
        var copies = Outside("category");
        var locks = Outside("locks");
        Link(Path.Combine(_paths.Backups, "nodes"), copies);
        Link(Path.Combine(_paths.Backups, ".locks"), locks);
        Directory.CreateDirectory(_paths.Nodes);
        var target = Path.Combine(_paths.Nodes, "1000.json");
        File.WriteAllText(target, "{\"nodeId\":\"1000\"}");
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _backups.BackupAsync(target, "nodes", CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.GetNextNodeIdAsync(CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(async () => { await using var lease = await new WorkspaceMutationGate(_paths).EnterAsync(CancellationToken.None); });
        Assert.Empty(Directory.EnumerateFileSystemEntries(copies));
        Assert.Empty(Directory.EnumerateFileSystemEntries(locks));
    }

    [Fact]
    public async Task LinkedSkillTreeRootCannotAllocateIds()
    {
        var external = Outside("skilltree");
        File.WriteAllText(Path.Combine(external, "node-id-sequence.json"), "{\"$schema\":\"./schemas/node-id-sequence.v1.schema.json\",\"schemaVersion\":1,\"lastIssuedNodeId\":\"999\"}");
        Directory.Delete(_paths.SkillTreeRoot, recursive: true);
        Link(_paths.SkillTreeRoot, external);
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.GetNextNodeIdAsync(CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _repository.CreateNodeAsync(new JsonObject(), CancellationToken.None));
        Assert.Contains("\"999\"", await File.ReadAllTextAsync(Path.Combine(external, "node-id-sequence.json")));
        Assert.False(Directory.Exists(Path.Combine(external, "nodes")));
    }

    private string Outside(string name)
    {
        var path = Path.Combine(_root, "external", name);
        Directory.CreateDirectory(path);
        return path;
    }

    private void Link(string link, string target)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(link)!);
        if (OperatingSystem.IsWindows())
        {
            var start = new ProcessStartInfo("cmd.exe") { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
            foreach (var argument in new[] { "/c", "mklink", "/J", link, target }) start.ArgumentList.Add(argument);
            using var process = Process.Start(start)!;
            process.WaitForExit();
            Assert.True(process.ExitCode == 0, process.StandardError.ReadToEnd());
        }
        else Directory.CreateSymbolicLink(link, target);
        _links.Add(link);
    }

    public void Dispose()
    {
        foreach (var link in _links) Directory.Delete(link);
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
