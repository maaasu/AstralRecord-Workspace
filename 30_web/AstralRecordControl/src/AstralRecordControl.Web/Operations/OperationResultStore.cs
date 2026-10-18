using System.Security.Cryptography;
using System.Text;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Inventory;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Operations;

// Large read results use authenticated HTTP; operation/event RPC stays a small summary.
public sealed class OperationResultStore(IConfiguration configuration, ControlDbContext db)
{
    public const int MaxResultBytes = 16 * 1024 * 1024;
    private static readonly SemaphoreSlim WriteGate = new(1);
    private string Root => Path.Combine(configuration["Control:DataRoot"] ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AstralRecordControl", "Web"), "operation-results");
    private string ResultPath(Guid id) => Path.Combine(Root, id.ToString("N") + ".json");

    public async Task SaveAsync(Guid hostId, Guid operationId, string commandHash, string resultHash, Stream source, CancellationToken token)
    {
        var row = await db.Operations.AsNoTracking().SingleOrDefaultAsync(o => o.OperationId == operationId) ?? throw new InventoryFault(404, "NotFound");
        if (row.HostId != hostId) throw new InventoryFault(403, "HostScopeMismatch");
        if (!OperationValidation.IsP2Kind(row.Kind) || row.CommandSha256 != commandHash || !ControlProtocol.IsHash(resultHash) || row.Status is OperationStatus.Rejected or OperationStatus.Cancelled or OperationStatus.Expired or OperationStatus.Failed) throw new InventoryFault(409, "OperationConflict");
        Directory.CreateDirectory(Root);
        var temporary = Path.Combine(Root, Guid.NewGuid().ToString("N") + ".part");
        try
        {
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using (var destination = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                var buffer = new byte[65536]; long length = 0;
                int count;
                while ((count = await source.ReadAsync(buffer, token)) > 0)
                {
                    length += count;
                    if (length > MaxResultBytes) throw new InventoryFault(413, "ResultTooLarge");
                    hash.AppendData(buffer, 0, count);
                    await destination.WriteAsync(buffer.AsMemory(0, count), token);
                }
                await destination.FlushAsync(token);
                destination.Flush(true);
            }
            if (Convert.ToHexStringLower(hash.GetHashAndReset()) != resultHash) throw new InventoryFault(409, "HashMismatch");
            var json = await File.ReadAllTextAsync(temporary, new UTF8Encoding(false, true), token);
            ControlJson.ValidateJson(json, MaxResultBytes);
            await WriteGate.WaitAsync(token);
            try
            {
                var target = ResultPath(operationId);
                if (File.Exists(target))
                {
                    await using var previous = File.OpenRead(target);
                    if (Convert.ToHexStringLower(await SHA256.HashDataAsync(previous, token)) != resultHash) throw new InventoryFault(409, "OperationConflict");
                }
                else File.Move(temporary, target);
            }
            finally { WriteGate.Release(); }
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    public async Task<string?> ReadAsync(Guid operationId, CancellationToken token = default)
    {
        var path = ResultPath(operationId);
        if (!File.Exists(path)) return null;
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0 || new FileInfo(path).Length > MaxResultBytes) throw new InventoryFault(409, "RecoveryRequired");
        return await File.ReadAllTextAsync(path, new UTF8Encoding(false, true), token);
    }
}
