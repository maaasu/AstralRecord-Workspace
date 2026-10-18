using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using AstralRecordControl.Web.Transfers;
using Microsoft.EntityFrameworkCore;
using Microsoft.Win32.SafeHandles;
using Xunit;

namespace AstralRecordControl.Integration.Tests;

public sealed partial class LiveTransportTests
{
    // This deliberately exercises every byte of a real HTTPS relay response. The
    // fixture stage is sparse so the test does not allocate 20 GiB on disk.
    [Fact]
    [Trait("Category", "LargeTransfer")]
    public async Task TwentyGiBSparseArtifactStreamsThroughEveryHttpsRange()
    {
        const long size = 20L * 1024 * 1024 * 1024;
        const int chunkSize = TransferRelay.ChunkSize;
        await using var fixture = await LiveFixture.StartAsync();
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);

        var storage = new ControlStorage(Path.Combine(fixture.Root, "Web"));
        await using var db = new ControlDbContext(new DbContextOptionsBuilder<ControlDbContext>()
            .UseSqlite(storage.ConnectionString + ";Pooling=False").Options);
        var hostId = Guid.NewGuid();
        var serverId = Guid.NewGuid();
        db.Hosts.Add(new InventoryHost { HostId = hostId, HostKey = "large-transfer-host", DisplayName = "Large transfer fixture" });
        db.Servers.Add(new InventoryServer { ServerId = serverId, HostId = hostId, ServerKey = "large-transfer-server", DisplayName = "Large transfer fixture" });
        await db.SaveChangesAsync();
        var relay = new TransferRelay(db, storage, new InventoryWriteGate(), TimeProvider.System);

        // Hash the logical zero-filled source in a fixed 4 MiB buffer.
        var zeros = new byte[chunkSize];
        var expectedChunkSha = Convert.ToHexString(SHA256.HashData(zeros)).ToLowerInvariant();
        using var expectedHasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        for (var offset = 0L; offset < size; offset += chunkSize) expectedHasher.AppendData(zeros);
        var expectedSha = Convert.ToHexString(expectedHasher.GetHashAndReset()).ToLowerInvariant();
        var transfer = await relay.CreateAsync(new TransferCreateRequest
        {
            SourceHostId = hostId, OperationId = Guid.NewGuid(), ServerId = serverId,
            SizeBytes = size, Sha256 = expectedSha
        }, Guid.NewGuid(), CancellationToken.None);
        var stage = Path.Combine(storage.DataRoot, "transfers", transfer.TransferId.ToString("N") + ".stage");
        Directory.CreateDirectory(Path.GetDirectoryName(stage)!);
        await using (var file = new FileStream(stage, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None, 64 * 1024, FileOptions.Asynchronous))
        {
            if (!DeviceIoControl(file.SafeFileHandle, 0x000900C4, IntPtr.Zero, 0, IntPtr.Zero, 0, out _, IntPtr.Zero))
                throw new InvalidOperationException("The temporary filesystem does not support the sparse-file fixture.");
            file.SetLength(size);
            file.Flush(true);
        }
        Assert.True(new FileInfo(stage).Length == size);
        // Seed only the fixture's upload journal. Production CompleteAsync still
        // reads and hashes the actual 20 GiB sparse file before enabling download.
        var row = await db.Transfers.SingleAsync(x => x.TransferId == transfer.TransferId);
        row.CommittedOffset = size;
        await db.SaveChangesAsync();
        Assert.Equal(TransferStatus.Verified, (await relay.CompleteAsync(transfer.TransferId, hostId,
            new TransferCompleteRequest(size, expectedSha), CancellationToken.None)).Status);

        var baselinePrivateBytes = Process.GetCurrentProcess().PrivateMemorySize64;
        var maxPrivateBytes = baselinePrivateBytes;
        using var downloadedHasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var buffer = new byte[64 * 1024];
        var chunkCount = 0;
        for (var offset = 0L; offset < size; offset += chunkSize)
        {
            var end = Math.Min(size - 1, offset + chunkSize - 1);
            using var request = new HttpRequestMessage(HttpMethod.Get, $"/api/control/v1/transfers/{transfer.TransferId}/content");
            request.Headers.Range = new RangeHeaderValue(offset, end);
            using var response = await owner.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
            Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
            Assert.Equal(offset, response.Content.Headers.ContentRange?.From);
            Assert.Equal(end, response.Content.Headers.ContentRange?.To);
            Assert.Equal(size, response.Content.Headers.ContentRange?.Length);
            Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
            var declaredChunkSha = Assert.Single(response.Headers.GetValues("X-Chunk-Sha256"));
            using var chunkHasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using var stream = await response.Content.ReadAsStreamAsync();
            var remaining = end - offset + 1;
            while (remaining > 0)
            {
                var read = await stream.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, remaining)));
                Assert.True(read > 0, "The HTTP range ended before its declared length.");
                chunkHasher.AppendData(buffer, 0, read);
                downloadedHasher.AppendData(buffer, 0, read);
                remaining -= read;
            }
            Assert.Equal(expectedChunkSha, Convert.ToHexString(chunkHasher.GetHashAndReset()).ToLowerInvariant());
            Assert.Equal(expectedChunkSha, declaredChunkSha);
            chunkCount++;
            if (chunkCount % 128 == 0)
            {
                using var process = Process.GetCurrentProcess();
                process.Refresh();
                maxPrivateBytes = Math.Max(maxPrivateBytes, process.PrivateMemorySize64);
            }
        }
        Assert.Equal(size / chunkSize, chunkCount);
        Assert.Equal(expectedSha, Convert.ToHexString(downloadedHasher.GetHashAndReset()).ToLowerInvariant());
        Assert.True(maxPrivateBytes - baselinePrivateBytes < 512L * 1024 * 1024,
            $"Streaming increased private memory by {maxPrivateBytes - baselinePrivateBytes} bytes.");

        var noFreeSpace = new TransferRelay(db, storage, new InventoryWriteGate(), TimeProvider.System,
            new FixedFreeSpace(size - 1));
        var diskFault = await Assert.ThrowsAsync<InventoryFault>(() => noFreeSpace.CreateAsync(new TransferCreateRequest
        {
            SourceHostId = hostId, OperationId = Guid.NewGuid(), ServerId = serverId,
            SizeBytes = size, Sha256 = expectedSha
        }, Guid.NewGuid(), CancellationToken.None));
        Assert.Equal("InsufficientStorage", diskFault.Code);

        // The first 20 GiB artifact occupies one fifth of the configured stage
        // budget. Four metadata reservations fill the rest; the next is rejected.
        for (var i = 0; i < 4; i++)
            await relay.CreateAsync(new TransferCreateRequest
            {
                SourceHostId = hostId, OperationId = Guid.NewGuid(), ServerId = serverId,
                SizeBytes = size, Sha256 = expectedSha
            }, Guid.NewGuid(), CancellationToken.None);
        var capFault = await Assert.ThrowsAsync<InventoryFault>(() => relay.CreateAsync(new TransferCreateRequest
        {
            SourceHostId = hostId, OperationId = Guid.NewGuid(), ServerId = serverId,
            SizeBytes = size, Sha256 = expectedSha
        }, Guid.NewGuid(), CancellationToken.None));
        Assert.Equal("StageCapacityExceeded", capFault.Code);
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeviceIoControl(SafeFileHandle handle, uint code, IntPtr inBuffer, uint inSize,
        IntPtr outBuffer, uint outSize, out uint returned, IntPtr overlapped);

    private sealed record FixedFreeSpace(long Bytes) : ITransferFreeSpaceProbe
    {
        public long AvailableBytes(string stageRoot) => Bytes;
    }
}
