using System.Security.Cryptography;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Transfers;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordControl.Integration.Tests;

public sealed class TransferRelayTests
{
    [Fact]
    public async Task UploadReplaysOnlyTheSameChunkAndVerifiesWholeArtifact()
    {
        await using var f = await Fixture.StartAsync();
        var bytes = RandomNumberGenerator.GetBytes(TransferRelay.ChunkSize + 17);
        var sha = Hash(bytes);
        var request = f.Request(bytes.Length, sha);
        var key = Guid.NewGuid();
        var transfer = await f.Relay.CreateAsync(request, key, default);
        Assert.Equal(transfer.TransferId, (await f.Relay.CreateAsync(request, key, default)).TransferId);
        await Fault("OperationConflict", () => f.Relay.CreateAsync(request with { OperationId = Guid.NewGuid() }, key, default));
        await Fault("HostScopeMismatch", () => f.Relay.PutChunkAsync(transfer.TransferId, Guid.NewGuid(), 0, Hash(bytes.AsSpan(0, 10)), new MemoryStream(bytes[..10]), default));
        await Fault("TransferOffsetMismatch", () => f.Relay.PutChunkAsync(transfer.TransferId, null, 1, Hash(bytes.AsSpan(0, 10)), new MemoryStream(bytes[..10]), default));
        var first = bytes[..TransferRelay.ChunkSize];
        var firstSha = Hash(first);
        transfer = await f.Relay.PutChunkAsync(transfer.TransferId, null, 0, firstSha, new MemoryStream(first), default);
        Assert.Equal(TransferRelay.ChunkSize, transfer.CommittedOffset);
        Assert.Equal(transfer.CommittedOffset, (await f.Relay.PutChunkAsync(transfer.TransferId, null, 0, firstSha, new MemoryStream(first), default)).CommittedOffset);
        await Fault("TransferOffsetMismatch", () => f.Relay.PutChunkAsync(transfer.TransferId, null, 0, Hash(new byte[first.Length]), new MemoryStream(new byte[first.Length]), default));
        await Fault("TransferOffsetMismatch", () => f.Relay.CompleteAsync(transfer.TransferId, null, new(bytes.Length, sha), default));
        var tail = bytes[TransferRelay.ChunkSize..];
        transfer = await f.Relay.PutChunkAsync(transfer.TransferId, null, transfer.CommittedOffset, Hash(tail), new MemoryStream(tail), default);
        await Fault("OperationConflict", () => f.Relay.CompleteAsync(transfer.TransferId, null, new(bytes.Length, new string('0', 64)), default));
        transfer = await f.Relay.CompleteAsync(transfer.TransferId, null, new(bytes.Length, sha), default);
        Assert.Equal(TransferStatus.Verified, transfer.Status);
        await Fault("HostScopeMismatch", () => f.Relay.OpenRangeAsync(transfer.TransferId, Guid.NewGuid(), 0, 9, default));
        await Fault("InvalidRange", () => f.Relay.OpenRangeAsync(transfer.TransferId, f.HostId, 0, TransferRelay.ChunkSize, default));
        var range = await f.Relay.OpenRangeAsync(transfer.TransferId, f.HostId, 0, 9, default);
        await using (range.Stream)
        {
            var actual = new byte[10];
            await range.Stream.ReadExactlyAsync(actual);
            Assert.Equal(bytes[..10], actual);
            Assert.Equal(Hash(actual), range.ChunkSha256);
        }
        var consumer = Guid.NewGuid();
        await f.Relay.GetVerifiedForConsumeAsync(transfer.TransferId, f.HostId, f.ServerId, consumer, default);
        await Fault("OperationConflict", () => f.Relay.GetVerifiedForConsumeAsync(transfer.TransferId, f.HostId, f.ServerId, Guid.NewGuid(), default));
        await f.Relay.FinalizeConsumedAsync(transfer.TransferId, consumer, default);
        Assert.Equal(TransferStatus.Consumed, (await f.Relay.GetAsync(transfer.TransferId, null, default)).Status);
    }

    [Fact]
    public async Task GeneratedPrepareIsImmutableAndZeroByteArtifactCompletes()
    {
        await using var f = await Fixture.StartAsync();
        var source = f.HostId;
        var transfer = await f.Relay.CreateAsync(new TransferCreateRequest
        {
            SourceHostId = source, OperationId = Guid.NewGuid(), ServerId = f.ServerId
        }, Guid.NewGuid(), default);
        Assert.Equal(TransferStatus.Created, transfer.Status);
        await Fault("OperationConflict", () => f.Relay.CompleteAsync(transfer.TransferId, source, new(0, Hash([])), default));
        transfer = await f.Relay.PrepareAsync(transfer.TransferId, source, new(0, Hash([])), default);
        Assert.Equal(TransferStatus.Uploading, transfer.Status);
        Assert.Equal(transfer.Sha256, (await f.Relay.PrepareAsync(transfer.TransferId, source, new(0, Hash([])), default)).Sha256);
        await Fault("OperationConflict", () => f.Relay.PrepareAsync(transfer.TransferId, source, new(1, Hash([1])), default));
        transfer = await f.Relay.CompleteAsync(transfer.TransferId, source, new(0, Hash([])), default);
        Assert.Equal(TransferStatus.Verified, transfer.Status);
    }

    [Fact]
    public async Task OversizedStreamStopsAtChunkBoundAndCapacityRejectsTwentyGiBExcess()
    {
        await using var f = await Fixture.StartAsync();
        await Fault("InvalidInput", () => f.Relay.CreateAsync(f.Request(TransferRelay.MaxTransferBytes + 1, Hash([])), Guid.NewGuid(), default));
        var transfer = await f.Relay.CreateAsync(f.Request(TransferRelay.ChunkSize + 1, Hash([])), Guid.NewGuid(), default);
        await Fault("TransferTooLarge", () => f.Relay.PutChunkAsync(transfer.TransferId, null, 0, Hash([]), new GeneratedStream(20L * 1024 * 1024 * 1024), default));
        Assert.Equal(0, (await f.Relay.GetAsync(transfer.TransferId, null, default)).CommittedOffset);
        Assert.Equal(0, new FileInfo(Path.Combine(f.Root, "transfers", transfer.TransferId.ToString("N") + ".stage")).Length);
    }

    private static string Hash(ReadOnlySpan<byte> bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    private static async Task Fault(string code, Func<Task> run) => Assert.Equal(code, (await Assert.ThrowsAsync<InventoryFault>(run)).Code);

    private sealed class GeneratedStream(long length) : Stream
    {
        private long offset;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => length;
        public override long Position { get => offset; set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int start, int count) { var size = (int)Math.Min(count, length - offset); Array.Clear(buffer, start, size); offset += size; return size; }
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        { var size = (int)Math.Min(buffer.Length, length - offset); buffer.Span[..size].Clear(); offset += size; return ValueTask.FromResult(size); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    private sealed class Fixture : IAsyncDisposable
    {
        public string Root { get; } = Path.Combine(Path.GetTempPath(), "control-transfer-" + Guid.NewGuid().ToString("N"));
        public Guid HostId { get; } = Guid.NewGuid();
        public Guid ServerId { get; } = Guid.NewGuid();
        private ControlDbContext Db { get; set; } = null!;
        public TransferRelay Relay { get; private set; } = null!;
        public static async Task<Fixture> StartAsync()
        {
            var f = new Fixture();
            Directory.CreateDirectory(f.Root);
            var storage = new ControlStorage(f.Root);
            f.Db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>().UseSqlite(storage.ConnectionString + ";Pooling=False").Options);
            await ControlDatabase.InitializeAsync(f.Db);
            f.Db.Hosts.Add(new InventoryHost { HostId = f.HostId, HostKey = "host", DisplayName = "host", Enabled = true });
            f.Db.Servers.Add(new InventoryServer { ServerId = f.ServerId, HostId = f.HostId, ServerKey = "server", DisplayName = "server" });
            await f.Db.SaveChangesAsync();
            f.Relay = new TransferRelay(f.Db, storage, new InventoryWriteGate(), TimeProvider.System);
            return f;
        }
        public TransferCreateRequest Request(long size, string sha) => new()
        { DestinationHostId = HostId, OperationId = Guid.NewGuid(), ServerId = ServerId, SizeBytes = size, Sha256 = sha };
        public async ValueTask DisposeAsync()
        {
            await Db.DisposeAsync();
            Directory.Delete(Root, true);
        }
    }
}
