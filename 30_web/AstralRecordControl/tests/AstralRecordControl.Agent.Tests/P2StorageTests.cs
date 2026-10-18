using System.IO.Compression;
using System.Text;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class P2StorageTests
{
    [Fact]
    public async Task EditingPreservesBomNewlineAndRejectsStaleEtag()
    {
        using var f = new Fixture();
        var path = Path.Combine(f.ServerPath, "server.properties");
        File.WriteAllBytes(path, [.. Encoding.UTF8.Preamble, .. Encoding.UTF8.GetBytes("a=1\r\nb=2\r\n")]);
        var target = f.Target("server.properties");
        var old = await f.Store.ReadFileAsync(f.Server, new(target));
        Assert.Equal("utf-8-bom", old.Encoding);
        Assert.Equal("crlf", old.Newline);
        var saved = await f.Store.WriteFileAsync(f.Server, new(target, old.Etag, "a=3\nb=4\n"), Guid.NewGuid(), Guard);
        Assert.Equal("a=3\r\nb=4\r\n", Encoding.UTF8.GetString(File.ReadAllBytes(path)[3..]));
        Assert.NotNull(saved.TrashPath);
        Assert.Equal("a=1\r\nb=2\r\n", Encoding.UTF8.GetString(File.ReadAllBytes(saved.TrashPath!)[3..]));
        await Assert.ThrowsAsync<InvalidOperationException>(() => f.Store.WriteFileAsync(f.Server, new(target, old.Etag, "stale"), Guid.NewGuid(), Guard));
    }

    [Fact]
    public async Task RejectsTraversalReparseAndZipBombBeforeTargetChange()
    {
        using var f = new Fixture();
        Assert.Throws<ArgumentException>(() => FileOperationValidation.ValidateTarget(new(f.RootId, "srv/../outside")));
        Assert.Throws<ArgumentException>(() => FileOperationValidation.ValidateTarget(new(f.RootId, "srv/CON.txt")));
        Assert.Throws<ArgumentException>(() => FileOperationValidation.ValidateTarget(new(f.RootId, "srv/file:stream")));
        var outside = Path.Combine(f.Root, "outside");
        Directory.CreateDirectory(outside);
        try
        {
            Directory.CreateSymbolicLink(Path.Combine(f.ServerPath, "link"), outside);
            await Assert.ThrowsAsync<InvalidOperationException>(() => f.Store.ListFilesAsync(f.Server, new(f.Target("link"))));
        }
        catch (UnauthorizedAccessException) { /* Windows without symlink privilege. */ }
        var archive = Path.Combine(f.Root, "bad.zip");
        using (var zip = ZipFile.Open(archive, ZipArchiveMode.Create))
        {
            using var writer = new StreamWriter(zip.CreateEntry("../outside/pwn.txt").Open());
            writer.Write("bad");
        }
        var bytes = File.ReadAllBytes(archive);
        var artifact = new Artifact(Guid.NewGuid(), Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes)), bytes.Length);
        await Assert.ThrowsAsync<InvalidDataException>(() => f.Store.ImportArtifactAsync(f.Server, new(artifact, f.Target("imported"), ArtifactImportMode.ExtractZip, "absent"), archive, Guid.NewGuid(), Guard));
        Assert.False(File.Exists(Path.Combine(outside, "pwn.txt")));
        Assert.False(Directory.Exists(Path.Combine(f.ServerPath, "imported")));
    }

    [Theory]
    [InlineData("Prepared")]
    [InlineData("OriginalMoved")]
    [InlineData("Installed")]
    [InlineData("Completed")]
    public async Task RestoreResumesAtEveryDurableBoundary(string interruptAt)
    {
        using var f = new Fixture();
        File.WriteAllText(Path.Combine(f.ServerPath, "keep.txt"), "backup");
        Directory.CreateDirectory(Path.Combine(f.ServerPath, "empty"));
        var backupId = Guid.NewGuid();
        var manifest = await f.Store.CreateBackupAsync(f.Server, f.Profile(), new(backupId, f.ProfileId, 1), Guid.NewGuid(), Guard);
        Assert.Equal("ServerFiles", manifest.Scope);
        Assert.Equal("Stopped", manifest.Consistency);
        Assert.Equal(f.Server.HostId, manifest.HostId);
        File.WriteAllText(Path.Combine(f.ServerPath, "keep.txt"), "changed");
        File.WriteAllText(Path.Combine(f.ServerPath, "extra.txt"), "remove");
        var operationId = Guid.NewGuid();
        f.Store.JournalBoundary = journal => { if (journal.Phase == interruptAt) throw new IOException("simulated interruption"); };
        await Assert.ThrowsAsync<IOException>(() => f.Store.RestoreBackupAsync(f.Server, new(backupId, RestoreMode.Replace), operationId, Guard));
        Assert.NotNull(f.Store.InspectRecovery(f.Server));
        await Assert.ThrowsAsync<InvalidOperationException>(() => f.Store.MutateAsync(f.Server, new(FileMutationAction.CreateDirectory, f.Target("blocked"), null, "absent"), Guid.NewGuid(), Guard));
        f.Store.JournalBoundary = null;
        var resumed = await f.Store.ResumeRestoreAsync(f.Server, operationId, Guard);
        Assert.Equal(backupId, resumed.BackupId);
        Assert.Equal("backup", File.ReadAllText(Path.Combine(f.ServerPath, "keep.txt")));
        Assert.False(File.Exists(Path.Combine(f.ServerPath, "extra.txt")));
        Assert.True(Directory.Exists(Path.Combine(f.ServerPath, "empty")));
        Assert.Null(f.Store.InspectRecovery(f.Server));
    }

    [Fact]
    public async Task CorruptBackupAndGuardFailureNeverExchangeLiveDirectory()
    {
        using var f = new Fixture();
        var live = Path.Combine(f.ServerPath, "world.dat");
        File.WriteAllText(live, "old");
        var id = Guid.NewGuid();
        await f.Store.CreateBackupAsync(f.Server, f.Profile(), new(id, f.ProfileId, 1), Guid.NewGuid(), Guard);
        File.WriteAllText(live, "new");
        var path = Path.Combine(f.BackupRoot, "control-backups", f.Server.ServerId.ToString("N"), id.ToString("N"), "files", "world.dat");
        File.WriteAllText(path, "corrupt");
        await Assert.ThrowsAsync<InvalidDataException>(() => f.Store.RestoreBackupAsync(f.Server, new(id, RestoreMode.Replace), Guid.NewGuid(), Guard));
        Assert.Equal("new", File.ReadAllText(live));
        Assert.Null(f.Store.InspectRecovery(f.Server));
    }

    [Fact]
    public async Task BackupProtectionBlocksDeleteAndMergeRetainsOtherFiles()
    {
        using var f = new Fixture();
        File.WriteAllText(Path.Combine(f.ServerPath, "a.txt"), "saved");
        var id = Guid.NewGuid();
        await f.Store.CreateBackupAsync(f.Server, f.Profile(), new(id, f.ProfileId, 1), Guid.NewGuid(), Guard);
        await f.Store.SetBackupProtectionAsync(f.Server, new(id, true), Guard);
        await Assert.ThrowsAsync<InvalidOperationException>(() => f.Store.DeleteBackupAsync(f.Server, new(id, "fixture"), Guard));
        File.WriteAllText(Path.Combine(f.ServerPath, "a.txt"), "changed");
        File.WriteAllText(Path.Combine(f.ServerPath, "other.txt"), "keep");
        await f.Store.RestoreBackupAsync(f.Server, new(id, RestoreMode.Merge), Guid.NewGuid(), Guard);
        Assert.Equal("saved", File.ReadAllText(Path.Combine(f.ServerPath, "a.txt")));
        Assert.Equal("keep", File.ReadAllText(Path.Combine(f.ServerPath, "other.txt")));
    }

    [Fact]
    public async Task ZipBackupRestoresAndMissingSpaceGuardLeavesLiveFilesUntouched()
    {
        using var f = new Fixture();
        var profile = f.Profile() with { Format = BackupFormat.Zip };
        File.WriteAllText(Path.Combine(f.ServerPath, "plain.txt"), "original");
        Directory.CreateDirectory(Path.Combine(f.ServerPath, "empty"));
        var id = Guid.NewGuid();
        await f.Store.CreateBackupAsync(f.Server, profile, new(id, f.ProfileId, 1), Guid.NewGuid(), Guard);
        File.WriteAllText(Path.Combine(f.ServerPath, "plain.txt"), "current");
        await Assert.ThrowsAsync<IOException>(() => f.Store.RestoreBackupAsync(f.Server, new(id, RestoreMode.Replace), Guid.NewGuid(), _ => throw new IOException("guard failed")));
        Assert.Equal("current", File.ReadAllText(Path.Combine(f.ServerPath, "plain.txt")));
        Assert.Null(f.Store.InspectRecovery(f.Server));
        await f.Store.RestoreBackupAsync(f.Server, new(id, RestoreMode.Replace), Guid.NewGuid(), Guard);
        Assert.Equal("original", File.ReadAllText(Path.Combine(f.ServerPath, "plain.txt")));
        Assert.True(Directory.Exists(Path.Combine(f.ServerPath, "empty")));
    }

    [Fact]
    public async Task DirectoryCopyUsesStageAndConflictDoesNotLeavePartialDestination()
    {
        using var f = new Fixture();
        Directory.CreateDirectory(Path.Combine(f.ServerPath, "source"));
        File.WriteAllText(Path.Combine(f.ServerPath, "source", "data.txt"), "content");
        var result = await f.Store.MutateAsync(f.Server, new(FileMutationAction.Copy, f.Target("source"), f.Target("copy"), "absent"), Guid.NewGuid(), Guard);
        Assert.Null(result.Etag);
        Assert.Equal("content", File.ReadAllText(Path.Combine(f.ServerPath, "copy", "data.txt")));
        await Assert.ThrowsAsync<IOException>(() => f.Store.MutateAsync(f.Server, new(FileMutationAction.Copy, f.Target("source"), f.Target("copy"), "absent"), Guid.NewGuid(), Guard));
        Assert.Equal("content", File.ReadAllText(Path.Combine(f.ServerPath, "copy", "data.txt")));
    }

    [Fact]
    public async Task DestinationIoFailurePreservesServerAndRecordsFailedBackup()
    {
        using var f = new Fixture();
        File.WriteAllText(Path.Combine(f.ServerPath, "world.dat"), "unchanged");
        File.WriteAllText(Path.Combine(f.BackupRoot, "control-backups"), "blocks directory creation");
        var backupId = Guid.NewGuid();
        await Assert.ThrowsAnyAsync<IOException>(() => f.Store.CreateBackupAsync(f.Server, f.Profile(), new(backupId, f.ProfileId, 1), Guid.NewGuid(), Guard));
        Assert.Equal("unchanged", File.ReadAllText(Path.Combine(f.ServerPath, "world.dat")));
        Assert.Contains(f.Store.ListBackups(f.Server), x => x.BackupId == backupId && x.State == BackupState.Failed);
    }

    [Fact]
    public async Task BackupOnlyRootCannotBeUsedAsServerSource()
    {
        using var f = new Fixture();
        var config = f.Server with { ManagedRootId = f.BackupRootId, ServerDirectory = "srv" };
        var store = new P2StorageService(Path.Combine(f.Root, "other-agent"), new HostResources { Roots = [new ManagedRoot { Id = f.BackupRootId, Path = f.BackupRoot, BackupOnly = true }] });
        await Assert.ThrowsAsync<InvalidOperationException>(() => store.ListFilesAsync(config, new(new(f.BackupRootId, "srv"))));
    }

    [Fact]
    public async Task ExcludedDirectoryAndChildrenAreAbsentFromSnapshot()
    {
        using var f = new Fixture();
        Directory.CreateDirectory(Path.Combine(f.ServerPath, "logs"));
        File.WriteAllText(Path.Combine(f.ServerPath, "logs", "private.log"), "excluded");
        File.WriteAllText(Path.Combine(f.ServerPath, "safe.txt"), "retained");
        var id = Guid.NewGuid();
        var profile = f.Profile() with { ExcludePatterns = ["logs"] };
        var manifest = await f.Store.CreateBackupAsync(f.Server, profile, new(id, f.ProfileId, 1), Guid.NewGuid(), Guard);
        Assert.Single(manifest.Entries);
        Assert.Equal("safe.txt", manifest.Entries[0].RelativePath);
        Assert.False(Directory.Exists(Path.Combine(f.BackupRoot, "control-backups", f.Server.ServerId.ToString("N"), id.ToString("N"), "files", "logs")));
    }

    private static Task Guard(CancellationToken _) => Task.CompletedTask;

    private sealed class Fixture : IDisposable
    {
        public string Root { get; } = Path.Combine(Path.GetTempPath(), "control-p2-storage-" + Guid.NewGuid().ToString("N"));
        public Guid RootId { get; } = Guid.NewGuid();
        public Guid BackupRootId { get; } = Guid.NewGuid();
        public Guid ProfileId { get; } = Guid.NewGuid();
        public string ServerPath => Path.Combine(Root, "managed", "srv");
        public string BackupRoot => Path.Combine(Root, "backup");
        public ServerConfig Server { get; }
        public P2StorageService Store { get; }
        public Fixture()
        {
            Directory.CreateDirectory(ServerPath);
            Directory.CreateDirectory(BackupRoot);
            Server = new ServerConfig { ServerId = Guid.NewGuid(), HostId = Guid.NewGuid(), Revision = 1, Kind = ServerKind.Java, ManagedRootId = RootId, ServerDirectory = "srv" };
            Store = new P2StorageService(Path.Combine(Root, "agent"), new HostResources { Roots = [new ManagedRoot { Id = RootId, Path = Path.Combine(Root, "managed") }, new ManagedRoot { Id = BackupRootId, Path = BackupRoot }] });
        }
        public FileTarget Target(string leaf) => new(RootId, "srv/" + leaf);
        public BackupProfile Profile() => new() { BackupProfileId = ProfileId, ServerId = Server.ServerId, Revision = 1, DestinationRootId = BackupRootId };
        public void Dispose() { if (Directory.Exists(Root)) Directory.Delete(Root, true); }
    }
}
