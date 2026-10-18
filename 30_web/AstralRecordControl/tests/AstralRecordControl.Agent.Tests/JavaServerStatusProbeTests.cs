using System.Net;
using System.Net.Sockets;
using System.Text;
using AstralRecordControl.Agent;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class JavaServerStatusProbeTests
{
    [Fact]
    public async Task FragmentedStatusWithZeroPlayersIsValid()
    {
        await using var fixture = new StatusFixture(async stream =>
        {
            var packet = Packet("{\"players\":{\"online\":0,\"max\":20}}");
            await stream.WriteAsync(packet.AsMemory(0, 1));
            await Task.Delay(10);
            await stream.WriteAsync(packet.AsMemory(1, 2));
            await Task.Delay(10);
            await stream.WriteAsync(packet.AsMemory(3));
        });
        Assert.Equal(0, await new JavaServerStatusProbe().QueryPlayerCountAsync(fixture.Port, CancellationToken.None));
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"players\":{\"online\":\"0\"}}")]
    [InlineData("{\"players\":{\"online\":-1}}")]
    [InlineData("garbage")]
    public async Task MissingOrMalformedPlayerCountRemainsUnknown(string json)
    {
        await using var fixture = new StatusFixture(stream => stream.WriteAsync(Packet(json)).AsTask());
        Assert.Null(await new JavaServerStatusProbe().QueryPlayerCountAsync(fixture.Port, CancellationToken.None));
    }

    [Fact]
    public async Task SilentServerTimesOutAndCallerCancellationPropagates()
    {
        await using var fixture = new StatusFixture(async _ => await Task.Delay(1000));
        Assert.Null(await new JavaServerStatusProbe().QueryPlayerCountAsync(fixture.Port, CancellationToken.None));
        using var canceled = new CancellationTokenSource(); canceled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => new JavaServerStatusProbe().QueryPlayerCountAsync(fixture.Port, canceled.Token));
    }

    [Fact]
    public async Task OversizedPacketIsRejectedBeforeAllocation()
    {
        await using var fixture = new StatusFixture(stream => stream.WriteAsync(VarInt(32769)).AsTask());
        Assert.Null(await new JavaServerStatusProbe().QueryPlayerCountAsync(fixture.Port, CancellationToken.None));
    }

    private static byte[] Packet(string json)
    {
        var bytes = Encoding.UTF8.GetBytes(json);
        var payload = new byte[] { 0 }.Concat(VarInt(bytes.Length)).Concat(bytes).ToArray();
        return VarInt(payload.Length).Concat(payload).ToArray();
    }
    private static byte[] VarInt(int value)
    {
        var output = new List<byte>();
        do { var next = (byte)(value & 0x7f); value >>>= 7; output.Add(value == 0 ? next : (byte)(next | 0x80)); }
        while (value != 0);
        return output.ToArray();
    }
    private sealed class StatusFixture : IAsyncDisposable
    {
        private readonly TcpListener listener = new(IPAddress.Loopback, 0);
        private readonly Task server;
        public int Port { get; }
        public StatusFixture(Func<NetworkStream, Task> respond)
        {
            listener.Start(); Port = ((IPEndPoint)listener.LocalEndpoint).Port;
            server = Task.Run(async () =>
            {
                try
                {
                    using var client = await listener.AcceptTcpClientAsync();
                    await respond(client.GetStream());
                }
                catch (SocketException) { }
                catch (ObjectDisposedException) { }
            });
        }
        public async ValueTask DisposeAsync() { listener.Stop(); await server; }
    }
}
