using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using AstralRecordControl.Contracts;
using AstralRecordControl.Agent.Transfers;
using AstralRecordControl.Web.Data;
using AstralRecordControl.Web.Transfers;
using Microsoft.AspNetCore.Http.Connections;
using Microsoft.AspNetCore.SignalR.Client;
using Xunit;

namespace AstralRecordControl.Integration.Tests;

public sealed partial class LiveTransportTests
{
    [Fact]
    public async Task TransferHttpSeparatesOwnerAndAgentAndStreamsVerifiedRange()
    {
        await using var fixture = await LiveFixture.StartAsync();
        using var owner = fixture.CreateClient(fixture.OwnerCookies);
        await fixture.LoginOwnerAsync(owner);
        var host = await fixture.OwnerApiAsync<InventoryHost>(owner, HttpMethod.Post, "/api/control/v1/hosts", new HostCreateRequest("transfer-host", "Transfer host"), create: true);
        var credential = await fixture.OwnerApiAsync<CredentialIssued>(owner, HttpMethod.Post, $"/api/control/v1/hosts/{host.HostId}/credentials", new { });
        using var agent = fixture.CreateClient();
        agent.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credential.Token);
        await using var hub = fixture.CreateHub(ControlProtocol.AgentHubPath, HttpTransportType.WebSockets, credential.Token);
        await hub.StartAsync();
        var runnerEpoch = Guid.NewGuid();
        var hello = await Invoke<HelloAck>(hub, "Hello", new AgentHello
        {
            AgentVersion = "transfer-fixture", AgentStoreId = Guid.NewGuid(), RunnerEpoch = runnerEpoch, LocalResources = fixture.Resources
        }, CancellationToken.None);
        agent.DefaultRequestHeaders.Add("X-Control-Connection-Epoch", hello.ConnectionEpoch.ToString());
        await Invoke<ReconcileAck>(hub, "Reconcile", new ReconcileRequest { ConnectionEpoch = hello.ConnectionEpoch, RunnerEpoch = runnerEpoch }, CancellationToken.None);
        var serverId = Guid.NewGuid();
        var config = new ServerConfig
        {
            ServerId = serverId, HostId = host.HostId, Revision = 1, Kind = ServerKind.Java,
            ManagedRootId = fixture.Resources.Roots.Single().Id, ExecutableId = fixture.Resources.Executables.Single().Id,
            ServerDirectory = "dev", ArgumentList = ["-jar", "fixture.jar", "nogui"]
        };
        await fixture.OwnerApiAsync<InventoryServer>(owner, HttpMethod.Post, "/api/control/v1/servers", new { hostId = host.HostId, serverKey = "transfer-server", displayName = "Transfer", config }, create: true);
        var bytes = RandomNumberGenerator.GetBytes(128 * 1024);
        var sha = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        var transfer = await fixture.OwnerApiAsync<TransferInfo>(owner, HttpMethod.Post, "/api/control/v1/transfers", new TransferCreateRequest
        { DestinationHostId = host.HostId, ServerId = serverId, OperationId = Guid.NewGuid(), SizeBytes = bytes.Length, Sha256 = sha }, create: true);
        Assert.Equal(TransferStatus.Uploading, transfer.Status);
        Assert.Equal(HttpStatusCode.Unauthorized, (await agent.GetAsync($"/api/control/v1/transfers/{transfer.TransferId}")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await owner.GetAsync($"/api/agent/v1/transfers/{transfer.TransferId}")).StatusCode);
        using (var before = new HttpRequestMessage(HttpMethod.Get, $"/api/agent/v1/transfers/{transfer.TransferId}/content"))
        {
            before.Headers.Range = new RangeHeaderValue(0, 9);
            using var rejected = await agent.SendAsync(before);
            Assert.Equal(HttpStatusCode.Conflict, rejected.StatusCode);
        }
        var csrf = await GetCsrfAsync(owner);
        using (var missingOrigin = new HttpRequestMessage(HttpMethod.Put, $"/api/control/v1/transfers/{transfer.TransferId}/chunks/0"))
        {
            missingOrigin.Headers.Add("X-CSRF-TOKEN", csrf);
            missingOrigin.Headers.Add("X-Chunk-Sha256", sha);
            missingOrigin.Content = new ByteArrayContent(bytes);
            missingOrigin.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            using var rejected = await owner.SendAsync(missingOrigin);
            Assert.Equal(HttpStatusCode.Forbidden, rejected.StatusCode);
        }
        using (var missingCsrf = new HttpRequestMessage(HttpMethod.Put, $"/api/control/v1/transfers/{transfer.TransferId}/chunks/0"))
        {
            missingCsrf.Headers.Add("Origin", fixture.Origin.GetLeftPart(UriPartial.Authority));
            missingCsrf.Headers.Add("X-Chunk-Sha256", sha);
            missingCsrf.Content = new ByteArrayContent(bytes);
            missingCsrf.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            using var rejected = await owner.SendAsync(missingCsrf);
            Assert.Equal(HttpStatusCode.BadRequest, rejected.StatusCode);
        }
        using (var upload = new HttpRequestMessage(HttpMethod.Put, $"/api/control/v1/transfers/{transfer.TransferId}/chunks/0"))
        {
            upload.Headers.Add("Origin", fixture.Origin.GetLeftPart(UriPartial.Authority));
            upload.Headers.Add("X-CSRF-TOKEN", csrf);
            upload.Headers.Add("X-Chunk-Sha256", sha);
            upload.Content = new ByteArrayContent(bytes);
            upload.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            using var response = await owner.SendAsync(upload);
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        }
        await fixture.OwnerApiAsync<TransferInfo>(owner, HttpMethod.Post, $"/api/control/v1/transfers/{transfer.TransferId}/complete", new TransferCompleteRequest(bytes.Length, sha), expectedStatus: HttpStatusCode.OK);
        using (var range = new HttpRequestMessage(HttpMethod.Get, $"/api/agent/v1/transfers/{transfer.TransferId}/content"))
        {
            range.Headers.Range = new RangeHeaderValue(5, 99);
            using var response = await agent.SendAsync(range);
            Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
            Assert.Equal(bytes[5..100], await response.Content.ReadAsByteArrayAsync());
            Assert.Equal(Convert.ToHexString(SHA256.HashData(bytes[5..100])).ToLowerInvariant(), response.Headers.GetValues("X-Chunk-Sha256").Single());
            Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
        }
        using (var oldEpoch = fixture.CreateClient())
        {
            oldEpoch.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", credential.Token);
            oldEpoch.DefaultRequestHeaders.Add("X-Control-Connection-Epoch", Guid.NewGuid().ToString());
            Assert.Equal(HttpStatusCode.Conflict, (await oldEpoch.GetAsync($"/api/agent/v1/transfers/{transfer.TransferId}")).StatusCode);
        }
        using (var huge = new HttpRequestMessage(HttpMethod.Put, $"/api/control/v1/transfers/{transfer.TransferId}/chunks/0"))
        {
            huge.Headers.Add("Origin", fixture.Origin.GetLeftPart(UriPartial.Authority));
            huge.Headers.Add("X-CSRF-TOKEN", csrf);
            huge.Headers.Add("X-Chunk-Sha256", sha);
            huge.Content = new ByteArrayContent(new byte[TransferRelay.ChunkSize + 1]);
            huge.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            using var response = await owner.SendAsync(huge);
            Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
        }
        using (var generated = new HttpRequestMessage(HttpMethod.Put, $"/api/control/v1/transfers/{transfer.TransferId}/chunks/0"))
        {
            generated.Headers.Add("Origin", fixture.Origin.GetLeftPart(UriPartial.Authority));
            generated.Headers.Add("X-CSRF-TOKEN", csrf);
            generated.Headers.Add("X-Chunk-Sha256", sha);
            generated.Headers.ExpectContinue = true;
            generated.Content = new StreamContent(new VirtualTwentyGiBStream());
            generated.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            using var response = await owner.SendAsync(generated);
            Assert.Equal(HttpStatusCode.RequestEntityTooLarge, response.StatusCode);
        }
        var exportBytes = RandomNumberGenerator.GetBytes(TransferRelay.ChunkSize + 17);
        var exportPath = Path.Combine(fixture.Root, "generated-export.stage");
        await File.WriteAllBytesAsync(exportPath, exportBytes);
        var export = await fixture.OwnerApiAsync<TransferInfo>(owner, HttpMethod.Post, "/api/control/v1/transfers", new TransferCreateRequest
        { SourceHostId = host.HostId, ServerId = serverId, OperationId = Guid.NewGuid() }, create: true);
        var client = new AgentTransferClient(agent, host.HostId, Path.Combine(fixture.Root, "agent-stage"));
        var exportSha = Convert.ToHexString(SHA256.HashData(exportBytes)).ToLowerInvariant();
        using (var prepare = new HttpRequestMessage(HttpMethod.Post, $"/api/agent/v1/transfers/{export.TransferId}/prepare")
        { Content = new StringContent(ControlJson.Serialize(new TransferPrepareRequest(exportBytes.Length, exportSha)), Encoding.UTF8, "application/json") })
        using (var prepared = await agent.SendAsync(prepare)) Assert.Equal(HttpStatusCode.OK, prepared.StatusCode);
        using (var firstChunk = new HttpRequestMessage(HttpMethod.Put, $"/api/agent/v1/transfers/{export.TransferId}/chunks/0"))
        {
            firstChunk.Headers.Add("X-Chunk-Sha256", Convert.ToHexString(SHA256.HashData(exportBytes.AsSpan(0, TransferRelay.ChunkSize))).ToLowerInvariant());
            firstChunk.Content = new ByteArrayContent(exportBytes, 0, TransferRelay.ChunkSize);
            firstChunk.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            using var accepted = await agent.SendAsync(firstChunk);
            Assert.Equal(HttpStatusCode.OK, accepted.StatusCode);
        }
        Assert.Equal(TransferStatus.Verified, (await client.UploadAsync(export.TransferId, exportPath, true, CancellationToken.None)).Status);
        using (var exportedRange = new HttpRequestMessage(HttpMethod.Get, $"/api/control/v1/transfers/{export.TransferId}/content"))
        {
            exportedRange.Headers.Range = new RangeHeaderValue(TransferRelay.ChunkSize, TransferRelay.ChunkSize + 16);
            using var response = await owner.SendAsync(exportedRange);
            Assert.Equal(HttpStatusCode.PartialContent, response.StatusCode);
            Assert.Equal(exportBytes[TransferRelay.ChunkSize..], await response.Content.ReadAsByteArrayAsync());
        }
    }

    private static async Task<string> GetCsrfAsync(HttpClient owner)
    {
        var html = await owner.GetStringAsync("/Security");
        return WebUtility.HtmlDecode(System.Text.RegularExpressions.Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value);
    }

    private sealed class VirtualTwentyGiBStream : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => true;
        public override bool CanWrite => false;
        public override long Length => 20L * 1024 * 1024 * 1024;
        public override long Position { get; set; }
        public override int Read(byte[] buffer, int offset, int count) => throw new InvalidOperationException("The rejected request must not read a 20 GiB source.");
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) => throw new InvalidOperationException("The rejected request must not read a 20 GiB source.");
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) { Position = origin switch { SeekOrigin.Begin => offset, SeekOrigin.Current => Position + offset, _ => Length + offset }; return Position; }
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
