using System.Net;
using System.Net.Sockets;
using System.Text;
using AstralRecordWeb.Options;
using AstralRecordWeb.Services;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class MinecraftServerStatusTests
{
    [Fact]
    public async Task JavaPingReadsPlayerCountFromFragmentedResponseWithoutLogin()
    {
        var result = await WithReply("""{"players":{"online":17,"max":100}}""");
        Assert.Equal("公開中", result.Label);
        Assert.Equal(17, result.Players);
        Assert.Equal(100, result.MaximumPlayers);
    }

    [Fact]
    public async Task SuccessfulResponseWithoutPlayerCountDoesNotInventZero()
    {
        var result = await WithReply("""{"description":"player count hidden"}""");
        Assert.Equal("online", result.State);
        Assert.Null(result.Players);
        Assert.Null(result.MaximumPlayers);
    }

    [Fact]
    public async Task MalformedReplyIsUnknownRatherThanClosed()
    {
        var result = await WithReply("not valid json");
        Assert.Equal("unknown", result.State);
        Assert.Null(result.Players);
    }

    [Fact]
    public async Task InvalidPacketIdIsUnknownRatherThanThrowingIntoTheHomePage()
    {
        var result = await WithReply("{}", packetId: 7);
        Assert.Equal("unknown", result.State);
        Assert.Null(result.Players);
    }

    [Fact]
    public async Task RefusedConnectionIsReportedAsClosed()
    {
        using var boundSocket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
        boundSocket.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        var port = ((IPEndPoint)boundSocket.LocalEndPoint!).Port;
        var result = await new MinecraftStatusProbe(TimeProvider.System).QueryAsync("127.0.0.1", port, CancellationToken.None);
        Assert.Equal("閉鎖中", result.Label);
        Assert.Null(result.Players);
    }

    [Fact]
    public async Task CallerCancellationIsNotCachedAsAnOfflineResult()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new MinecraftStatusProbe(TimeProvider.System)
            .QueryAsync("127.0.0.1", 25565, cancellation.Token));
    }

    [Fact]
    public async Task ConcurrentRequestsShareFifteenSecondCacheAndConfiguredJoinAddress()
    {
        var clock = new ManualClock();
        var probe = new CountingProbe(clock);
        using var service = new MinecraftServerStatusService(probe,
            Microsoft.Extensions.Options.Options.Create(new PublicSiteOptions { JavaServerAddress = "join.example", JavaServerPort = 25570 }), clock);
        await Task.WhenAll(Enumerable.Range(0, 10).Select(_ => service.GetAsync(CancellationToken.None)));
        Assert.Equal(1, probe.Calls);
        Assert.Equal("join.example", probe.Host);
        Assert.Equal(25570, probe.Port);
        clock.Now += TimeSpan.FromSeconds(16);
        await service.GetAsync(CancellationToken.None);
        Assert.Equal(2, probe.Calls);
    }

    private static async Task<MinecraftServerStatus> WithReply(string json, byte packetId = 0)
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        using var limit = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        var server = Task.Run(async () =>
        {
            using var peer = await listener.AcceptTcpClientAsync(limit.Token);
            await using var stream = peer.GetStream();
            var length = new byte[1];
            await stream.ReadExactlyAsync(length, limit.Token);
            var handshake = new byte[length[0]];
            await stream.ReadExactlyAsync(handshake, limit.Token);
            Assert.Equal(0, handshake[0]);
            Assert.Equal(1, handshake[^1]); // Status mode, never login mode (2).
            var query = new byte[2];
            await stream.ReadExactlyAsync(query, limit.Token);
            Assert.Equal(new byte[] { 1, 0 }, query);
            var body = Encoding.UTF8.GetBytes(json);
            Assert.True(body.Length < 125);
            await stream.WriteAsync(new byte[] { (byte)(body.Length + 2), packetId }, limit.Token);
            await Task.Yield();
            await stream.WriteAsync(new byte[] { (byte)body.Length }, limit.Token);
            await stream.WriteAsync(body, limit.Token);
        }, limit.Token);
        var result = await new MinecraftStatusProbe(TimeProvider.System).QueryAsync("127.0.0.1", port, limit.Token);
        await server;
        return result;
    }

    private sealed class ManualClock : TimeProvider
    {
        public DateTimeOffset Now { get; set; } = new(2026, 9, 16, 0, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class CountingProbe(ManualClock clock) : IMinecraftStatusProbe
    {
        public int Calls { get; private set; }
        public string? Host { get; private set; }
        public int Port { get; private set; }
        public Task<MinecraftServerStatus> QueryAsync(string host, int port, CancellationToken cancellationToken)
        {
            Calls++; Host = host; Port = port;
            return Task.FromResult(new MinecraftServerStatus("online", 3, 50, clock.Now));
        }
    }
}
