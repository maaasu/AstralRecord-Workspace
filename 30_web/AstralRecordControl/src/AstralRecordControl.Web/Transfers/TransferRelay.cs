using System.Buffers;
using System.Security.Cryptography;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Transfers;

public sealed class TransferRelay(ControlDbContext db, ControlStorage storage, InventoryWriteGate gate, TimeProvider clock)
{
    public const int ChunkSize = 4 * 1024 * 1024;
    public const long MaxTransferBytes = 20L * 1024 * 1024 * 1024;
    public const long MaxStageBytes = 100L * 1024 * 1024 * 1024;
    private string StageRoot => Path.Combine(storage.DataRoot, "transfers");
    private string StagePath(Guid id) => Path.Combine(StageRoot, id.ToString("N") + ".stage");
    private DateTimeOffset Now => clock.GetUtcNow();

    public async Task<TransferInfo> CreateAsync(TransferCreateRequest request, Guid key, CancellationToken ct)
    {
        ValidateMetadata(request.SizeBytes, request.Sha256, request.SourceHostId != null);
        if (key == Guid.Empty || request.OperationId == Guid.Empty || request.ServerId == Guid.Empty ||
            request.SourceHostId == request.DestinationHostId || request.SourceHostId == null && request.DestinationHostId == null)
            throw new InventoryFault(400, "InvalidInput");
        var hash = ControlProtocol.Hash(ControlJson.Serialize(request));
        return await LockedAsync(async () =>
        {
            var prior = await db.Transfers.SingleOrDefaultAsync(x => x.IdempotencyKey == key, ct);
            if (prior != null)
            {
                if (prior.RequestSha256 != hash) throw new InventoryFault(409, "OperationConflict");
                return Info(prior);
            }
            if (await db.InventoryRequests.AnyAsync(x => x.IdempotencyKey == key, ct) ||
                await db.Operations.AnyAsync(x => x.IdempotencyKey == key, ct))
                throw new InventoryFault(409, "OperationConflict");
            var server = await db.Servers.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == request.ServerId, ct) ?? throw new InventoryFault(404, "NotFound");
            if (server.HostId != (request.SourceHostId ?? request.DestinationHostId)) throw new InventoryFault(403, "HostScopeMismatch");
            foreach (var hostId in new[] { request.SourceHostId, request.DestinationHostId }.Where(x => x.HasValue).Distinct())
                if (!await db.Hosts.AnyAsync(x => x.HostId == hostId && x.Enabled, ct)) throw new InventoryFault(404, "NotFound");
            var size = request.SizeBytes ?? 0;
            await RequireCapacityAsync(size, ct);
            Directory.CreateDirectory(StageRoot);
            var row = new ControlTransfer
            {
                TransferId = Guid.NewGuid(), IdempotencyKey = key, RequestSha256 = hash,
                SourceHostId = request.SourceHostId, DestinationHostId = request.DestinationHostId,
                OperationId = request.OperationId, ServerId = request.ServerId,
                SizeBytes = request.SizeBytes, Sha256 = request.Sha256?.ToLowerInvariant(),
                Status = request.SizeBytes.HasValue ? TransferStatus.Uploading : TransferStatus.Created,
                CreatedAtUtc = Now, ExpiresAtUtc = Now.AddHours(2)
            };
            db.Transfers.Add(row);
            db.TransferScopes.Add(new InventoryTransferScope { TransferId = row.TransferId, SourceHostId = row.SourceHostId, DestinationHostId = row.DestinationHostId });
            await db.SaveChangesAsync(ct);
            return Info(row);
        }, ct);
    }

    public async Task<TransferInfo> GetAsync(Guid id, Guid? agentHostId, CancellationToken ct)
    {
        var row = await FindAsync(id, ct);
        if (agentHostId.HasValue && agentHostId != row.SourceHostId && agentHostId != row.DestinationHostId) throw new InventoryFault(403, "HostScopeMismatch");
        return Info(row);
    }

    public async Task<TransferInfo[]> ListAsync(CancellationToken ct) =>
        (await db.Transfers.AsNoTracking().OrderByDescending(x => x.CreatedAtUtc).Take(100).ToArrayAsync(ct)).Select(Info).ToArray();

    public async Task<TransferInfo> PrepareAsync(Guid id, Guid sourceHostId, TransferPrepareRequest request, CancellationToken ct) => await LockedAsync(async () =>
    {
        var row = await FindAsync(id, ct);
        RequireSource(row, sourceHostId);
        ValidateMetadata(request.SizeBytes, request.Sha256, false);
        if (row.Status == TransferStatus.Created)
        {
            await RequireCapacityAsync(request.SizeBytes, ct);
            row.SizeBytes = request.SizeBytes; row.Sha256 = request.Sha256.ToLowerInvariant(); row.Status = TransferStatus.Uploading;
            await db.SaveChangesAsync(ct);
        }
        else if (row.SizeBytes != request.SizeBytes || !string.Equals(row.Sha256, request.Sha256, StringComparison.OrdinalIgnoreCase))
            throw new InventoryFault(409, "OperationConflict");
        return Info(row);
    }, ct);

    public async Task<TransferInfo> PutChunkAsync(Guid id, Guid? sourceHostId, long offset, string chunkHash, Stream input, CancellationToken ct) => await LockedAsync(async () =>
    {
        var row = await FindAsync(id, ct);
        RequireSource(row, sourceHostId);
        if (row.Status != TransferStatus.Uploading || !row.SizeBytes.HasValue) throw new InventoryFault(409, "StateConflict");
        if (!ValidSha(chunkHash) || offset < 0 || offset > row.SizeBytes.Value || offset > row.CommittedOffset) throw new InventoryFault(409, "TransferOffsetMismatch");
        var old = await db.TransferChunks.AsNoTracking().SingleOrDefaultAsync(x => x.TransferId == id && x.Offset == offset, ct);
        if (offset < row.CommittedOffset && old == null) throw new InventoryFault(409, "TransferOffsetMismatch");
        Directory.CreateDirectory(StageRoot);
        await using var file = new FileStream(StagePath(id), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None, 64 * 1024, FileOptions.Asynchronous);
        if (file.Length < row.CommittedOffset) throw new InventoryFault(409, "RecoveryRequired");
        if (file.Length > row.CommittedOffset) file.SetLength(row.CommittedOffset);
        var committed = row.CommittedOffset;
        var buffer = ArrayPool<byte>.Shared.Rent(64 * 1024);
        try
        {
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            file.Position = offset;
            var count = 0;
            while (true)
            {
                var read = await input.ReadAsync(buffer.AsMemory(0, Math.Min(buffer.Length, ChunkSize + 1 - count)), ct);
                if (read == 0) break;
                count += read;
                if (count > ChunkSize || offset + count > row.SizeBytes) throw new InventoryFault(413, "TransferTooLarge");
                hash.AppendData(buffer, 0, read);
                if (old == null) await file.WriteAsync(buffer.AsMemory(0, read), ct);
            }
            if (count == 0 || !string.Equals(Convert.ToHexString(hash.GetHashAndReset()), chunkHash, StringComparison.OrdinalIgnoreCase)) throw new InventoryFault(409, "ChunkHashMismatch");
            if (old != null)
            {
                if (old.SizeBytes != count || !string.Equals(old.Sha256, chunkHash, StringComparison.OrdinalIgnoreCase)) throw new InventoryFault(409, "TransferOffsetMismatch");
                return Info(row);
            }
            if (offset != row.CommittedOffset) throw new InventoryFault(409, "TransferOffsetMismatch");
            await file.FlushAsync(ct);
            file.Flush(true);
            db.TransferChunks.Add(new ControlTransferChunk { TransferId = id, Offset = offset, SizeBytes = count, Sha256 = chunkHash.ToLowerInvariant() });
            row.CommittedOffset += count;
            await db.SaveChangesAsync(ct);
            return Info(row);
        }
        catch
        {
            if (file.Length > committed) file.SetLength(committed);
            row.CommittedOffset = committed;
            db.ChangeTracker.Clear();
            throw;
        }
        finally { ArrayPool<byte>.Shared.Return(buffer); }
    }, ct);

    public async Task<TransferInfo> CompleteAsync(Guid id, Guid? sourceHostId, TransferCompleteRequest request, CancellationToken ct) => await LockedAsync(async () =>
    {
        var row = await FindAsync(id, ct);
        RequireSource(row, sourceHostId);
        if (row.SizeBytes != request.SizeBytes || !string.Equals(row.Sha256, request.Sha256, StringComparison.OrdinalIgnoreCase)) throw new InventoryFault(409, "OperationConflict");
        if (row.Status is TransferStatus.Verified or TransferStatus.Consumed) return Info(row);
        if (row.Status != TransferStatus.Uploading || row.CommittedOffset != row.SizeBytes) throw new InventoryFault(409, "TransferOffsetMismatch");
        Directory.CreateDirectory(StageRoot);
        await using var file = new FileStream(StagePath(id), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (file.Length != row.SizeBytes) throw new InventoryFault(409, "TransferOffsetMismatch");
        file.Position = 0;
        var actual = Convert.ToHexString(await SHA256.HashDataAsync(file, ct));
        if (!string.Equals(actual, row.Sha256, StringComparison.OrdinalIgnoreCase)) throw new InventoryFault(409, "ArtifactHashMismatch");
        row.Status = TransferStatus.Verified; row.VerifiedAtUtc = Now;
        await db.SaveChangesAsync(ct);
        return Info(row);
    }, ct);

    public async Task<(TransferInfo Info, FileStream Stream, long Start, long End, string ChunkSha256)> OpenRangeAsync(Guid id, Guid? destinationHostId, long start, long end, CancellationToken ct)
    {
        var row = await FindAsync(id, ct);
        if (row.DestinationHostId != destinationHostId) throw new InventoryFault(403, "HostScopeMismatch");
        if (row.Status is not (TransferStatus.Verified or TransferStatus.Consumed) || !row.SizeBytes.HasValue) throw new InventoryFault(409, "StateConflict");
        if (start < 0 || end < start || end >= row.SizeBytes || end - start + 1 > ChunkSize) throw new InventoryFault(416, "InvalidRange");
        var file = new FileStream(StagePath(id), FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (file.Length != row.SizeBytes) { await file.DisposeAsync(); throw new InventoryFault(409, "RecoveryRequired"); }
        file.Position = start;
        var buffer = new byte[64 * 1024];
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var remaining = end - start + 1;
        while (remaining > 0)
        {
            var read = await file.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, remaining)), ct);
            if (read == 0) { await file.DisposeAsync(); throw new InventoryFault(409, "RecoveryRequired"); }
            hash.AppendData(buffer, 0, read); remaining -= read;
        }
        file.Position = start;
        return (Info(row), file, start, end, Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant());
    }

    public async Task<TransferInfo> GetVerifiedForConsumeAsync(Guid transferId, Guid destinationHostId, Guid serverId, Guid consumingOperationId, CancellationToken ct) => await LockedAsync(async () =>
    {
        var row = await FindAsync(transferId, ct);
        if (row.DestinationHostId != destinationHostId || row.ServerId != serverId) throw new InventoryFault(403, "HostScopeMismatch");
        if (row.Status != TransferStatus.Verified && !(row.Status == TransferStatus.Consumed && row.ConsumingOperationId == consumingOperationId)) throw new InventoryFault(409, "StateConflict");
        if (row.ConsumingOperationId.HasValue && row.ConsumingOperationId != consumingOperationId) throw new InventoryFault(409, "OperationConflict");
        if (consumingOperationId == Guid.Empty) throw new InventoryFault(400, "InvalidInput");
        row.ConsumingOperationId = consumingOperationId;
        await db.SaveChangesAsync(ct);
        return Info(row);
    }, ct);

    public async Task FinalizeConsumedAsync(Guid transferId, Guid consumingOperationId, CancellationToken ct) => await LockedAsync(async () =>
    {
        var row = await FindAsync(transferId, ct);
        if (row.ConsumingOperationId != consumingOperationId || row.Status is not (TransferStatus.Verified or TransferStatus.Consumed)) throw new InventoryFault(409, "OperationConflict");
        row.Status = TransferStatus.Consumed; row.ConsumedAtUtc ??= Now;
        await db.SaveChangesAsync(ct);
        return 0;
    }, ct);

    public async Task CleanupAsync(CancellationToken ct) => await LockedAsync(async () =>
    {
        var due = await db.Transfers.Where(x =>
            (x.ConsumingOperationId == null &&
                (x.Status == TransferStatus.Created || x.Status == TransferStatus.Uploading ? x.ExpiresAtUtc <= Now :
                 x.Status == TransferStatus.Verified ? x.VerifiedAtUtc <= Now.AddHours(-24) : false)) ||
            (x.Status == TransferStatus.Consumed && x.ConsumedAtUtc <= Now.AddHours(-24))).ToArrayAsync(ct);
        foreach (var row in due)
        {
            if (row.Status is TransferStatus.Created or TransferStatus.Uploading) row.Status = TransferStatus.Expired;
            var path = StagePath(row.TransferId);
            try { if (File.Exists(path)) File.Delete(path); } catch (IOException) { continue; }
            if (row.Status == TransferStatus.Expired) continue;
            db.TransferScopes.Remove(new InventoryTransferScope { TransferId = row.TransferId });
            db.Transfers.Remove(row);
        }
        await db.SaveChangesAsync(ct);
        return 0;
    }, ct);

    private async Task<T> LockedAsync<T>(Func<Task<T>> action, CancellationToken ct)
    {
        await gate.Semaphore.WaitAsync(ct);
        try { return await action(); }
        finally { gate.Semaphore.Release(); }
    }
    private async Task<ControlTransfer> FindAsync(Guid id, CancellationToken ct) =>
        await db.Transfers.SingleOrDefaultAsync(x => x.TransferId == id, ct) ?? throw new InventoryFault(404, "NotFound");
    private void RequireSource(ControlTransfer row, Guid? hostId)
    {
        if (row.SourceHostId != hostId) throw new InventoryFault(403, "HostScopeMismatch");
        if (row.ExpiresAtUtc <= Now || row.Status == TransferStatus.Expired) throw new InventoryFault(409, "TransferExpired");
    }
    private async Task RequireCapacityAsync(long size, CancellationToken ct)
    {
        var reserved = await db.Transfers.Where(x => x.Status == TransferStatus.Uploading || x.Status == TransferStatus.Created || x.Status == TransferStatus.Verified).SumAsync(x => x.SizeBytes ?? 0, ct);
        if (size > MaxStageBytes - reserved) throw new InventoryFault(413, "StageCapacityExceeded");
        Directory.CreateDirectory(StageRoot);
        var free = new DriveInfo(Path.GetPathRoot(StageRoot)!).AvailableFreeSpace;
        if (size > free) throw new InventoryFault(413, "InsufficientStorage");
    }
    private static void ValidateMetadata(long? size, string? sha, bool allowUnknown)
    {
        if (allowUnknown && size == null && sha == null) return;
        if (size is null or < 0 or > MaxTransferBytes || !ValidSha(sha)) throw new InventoryFault(400, "InvalidInput");
    }
    private static bool ValidSha(string? value) => value is { Length: 64 } && value.All(Uri.IsHexDigit);
    private static TransferInfo Info(ControlTransfer row) => new()
    {
        TransferId = row.TransferId, SourceHostId = row.SourceHostId, DestinationHostId = row.DestinationHostId,
        OperationId = row.OperationId, ServerId = row.ServerId, ConsumingOperationId = row.ConsumingOperationId,
        Status = row.Status, SizeBytes = row.SizeBytes, Sha256 = row.Sha256, CommittedOffset = row.CommittedOffset,
        ExpiresAtUtc = row.ExpiresAtUtc
    };
}
