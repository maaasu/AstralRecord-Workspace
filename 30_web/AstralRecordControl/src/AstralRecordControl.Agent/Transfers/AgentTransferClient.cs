using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Transfers;

// Uses the authenticated, epoch-bound HttpClient owned by the current Agent session.
public sealed class AgentTransferClient(HttpClient http, Guid hostId, string stageRoot)
{
    private const int ChunkSize = 4 * 1024 * 1024;

    public async Task<TransferInfo> UploadAsync(Guid transferId, string localStagePath, bool prepareGenerated, CancellationToken ct, Guid? expectedOperationId = null, Guid? expectedServerId = null)
    {
        if (!Path.IsPathFullyQualified(localStagePath)) throw new ArgumentException("A local stage path is required.", nameof(localStagePath));
        await using var file = new FileStream(localStagePath, FileMode.Open, FileAccess.Read, FileShare.Read, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (file.Length > TransferMaxBytes) throw new InvalidOperationException("Transfer exceeds the size limit.");
        var size = file.Length;
        var sha = Convert.ToHexString(await SHA256.HashDataAsync(file, ct)).ToLowerInvariant();
        var info = await GetAsync(transferId, ct);
        if (info.SourceHostId != hostId || expectedOperationId.HasValue && info.OperationId != expectedOperationId || expectedServerId.HasValue && info.ServerId != expectedServerId) throw new InvalidOperationException("Transfer source/operation/server mismatch.");
        if (prepareGenerated)
            info = await PostAsync<TransferPrepareRequest>($"/api/agent/v1/transfers/{transferId}/prepare", new(size, sha), ct);
        if (info.SizeBytes != size || !string.Equals(info.Sha256, sha, StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException("Transfer metadata mismatch.");
        if (info.Status == TransferStatus.Verified) return info;
        if (info.Status != TransferStatus.Uploading || info.CommittedOffset < 0 || info.CommittedOffset > size) throw new InvalidOperationException("Transfer cannot resume.");
        var buffer = new byte[ChunkSize];
        for (var offset = info.CommittedOffset; offset < size;)
        {
            file.Position = offset;
            var count = await file.ReadAtLeastAsync(buffer.AsMemory(0, (int)Math.Min(ChunkSize, size - offset)), (int)Math.Min(ChunkSize, size - offset), false, ct);
            var chunkSha = Convert.ToHexString(SHA256.HashData(buffer.AsSpan(0, count))).ToLowerInvariant();
            using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/agent/v1/transfers/{transferId}/chunks/{offset}");
            request.Headers.Add("X-Chunk-Sha256", chunkSha);
            request.Content = new ByteArrayContent(buffer, 0, count);
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            await RequireSuccessAsync(response, ct);
            info = await ReadAsync<TransferInfo>(response, ct);
            if (info.CommittedOffset != offset + count) throw new InvalidOperationException("Transfer offset mismatch.");
            offset = info.CommittedOffset;
        }
        return await PostAsync<TransferCompleteRequest>($"/api/agent/v1/transfers/{transferId}/complete", new(size, sha), ct);
    }

    public async Task<string> DownloadAsync(Guid transferId, Guid expectedServerId, Guid consumingOperationId, CancellationToken ct)
    {
        var info = await GetAsync(transferId, ct);
        if (info.DestinationHostId != hostId || info.ServerId != expectedServerId ||
            info.OperationId != consumingOperationId || info.ConsumingOperationId != consumingOperationId || info.Status is not (TransferStatus.Verified or TransferStatus.Consumed) ||
            info.SizeBytes is null || info.Sha256 is null) throw new InvalidOperationException("Transfer destination binding mismatch.");
        Directory.CreateDirectory(stageRoot);
        var path = Path.Combine(stageRoot, transferId.ToString("N") + ".download");
        await using var file = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None, 64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
        if (file.Length > info.SizeBytes) file.SetLength(0);
        // A torn final chunk is retried from its 4 MiB boundary.
        if (file.Length < info.SizeBytes && file.Length % ChunkSize != 0) file.SetLength(file.Length / ChunkSize * ChunkSize);
        for (var offset = file.Length; offset < info.SizeBytes;)
        {
            var end = Math.Min(info.SizeBytes.Value - 1, offset + ChunkSize - 1);
            using var request = new HttpRequestMessage(HttpMethod.Get, $"/api/agent/v1/transfers/{transferId}/content");
            request.Headers.Range = new RangeHeaderValue(offset, end);
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
            await RequireSuccessAsync(response, ct);
            if (response.StatusCode != HttpStatusCode.PartialContent || response.Content.Headers.ContentRange?.From != offset || response.Content.Headers.ContentRange?.To != end)
                throw new InvalidOperationException("Unexpected transfer range.");
            var declaredSha = response.Headers.TryGetValues("X-Chunk-Sha256", out var values) ? values.SingleOrDefault() : null;
            if (declaredSha is not { Length: 64 }) throw new InvalidOperationException("Missing transfer chunk hash.");
            await using var input = await response.Content.ReadAsStreamAsync(ct);
            var buffer = new byte[64 * 1024];
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var remaining = end - offset + 1;
            file.Position = offset;
            while (remaining > 0)
            {
                var read = await input.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, remaining)), ct);
                if (read == 0) throw new EndOfStreamException();
                hash.AppendData(buffer, 0, read);
                await file.WriteAsync(buffer.AsMemory(0, read), ct);
                remaining -= read;
            }
            if (await input.ReadAsync(buffer, ct) != 0 || !string.Equals(Convert.ToHexString(hash.GetHashAndReset()), declaredSha, StringComparison.OrdinalIgnoreCase))
            {
                file.SetLength(offset);
                throw new InvalidOperationException("Transfer chunk hash mismatch.");
            }
            await file.FlushAsync(ct); file.Flush(true);
            offset = end + 1;
        }
        if (file.Length != info.SizeBytes) throw new InvalidOperationException("Transfer size mismatch.");
        file.Position = 0;
        var wholeSha = Convert.ToHexString(await SHA256.HashDataAsync(file, ct));
        if (!string.Equals(wholeSha, info.Sha256, StringComparison.OrdinalIgnoreCase))
        {
            file.SetLength(0);
            throw new InvalidOperationException("Transfer hash mismatch.");
        }
        return path;
    }

    public async Task<TransferInfo> GetAsync(Guid transferId, CancellationToken ct)
    {
        using var response = await http.GetAsync($"/api/agent/v1/transfers/{transferId}", HttpCompletionOption.ResponseHeadersRead, ct);
        await RequireSuccessAsync(response, ct);
        return await ReadAsync<TransferInfo>(response, ct);
    }

    private async Task<TransferInfo> PostAsync<T>(string path, T body, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, path)
        { Content = new StringContent(ControlJson.Serialize(body), Encoding.UTF8, "application/json") };
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        await RequireSuccessAsync(response, ct);
        return await ReadAsync<TransferInfo>(response, ct);
    }
    private static async Task<T> ReadAsync<T>(HttpResponseMessage response, CancellationToken ct)
    {
        if (response.Content.Headers.ContentLength > ControlProtocol.MaxMessageBytes) throw new InvalidOperationException("Transfer response too large.");
        var bytes = await response.Content.ReadAsByteArrayAsync(ct);
        if (bytes.Length > ControlProtocol.MaxMessageBytes) throw new InvalidOperationException("Transfer response too large.");
        return ControlJson.Deserialize<T>(Encoding.UTF8.GetString(bytes));
    }
    private static async Task RequireSuccessAsync(HttpResponseMessage response, CancellationToken ct)
    {
        if (response.IsSuccessStatusCode) return;
        await Task.CompletedTask;
        throw new HttpRequestException("Transfer endpoint rejected request.", null, response.StatusCode);
    }
    private const long TransferMaxBytes = 20L * 1024 * 1024 * 1024;
}
