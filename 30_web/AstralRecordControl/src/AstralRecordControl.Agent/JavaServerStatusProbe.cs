using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace AstralRecordControl.Agent;

public interface IJavaServerStatusProbe
{
    Task<int?> QueryPlayerCountAsync(int port, CancellationToken cancellationToken);
}

/// <summary>Java Server List Ping to the configured local port. No login or game command is sent.</summary>
public sealed class JavaServerStatusProbe : IJavaServerStatusProbe
{
    private const int MaximumPacketBytes = 32 * 1024;
    private static readonly TimeSpan Timeout = TimeSpan.FromMilliseconds(500);
    private static readonly byte[] Address = Encoding.ASCII.GetBytes("127.0.0.1");

    public async Task<int?> QueryPlayerCountAsync(int port, CancellationToken cancellationToken)
    {
        if (port is < 1 or > 65535) return null;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(Timeout);
        try
        {
            using var client = new TcpClient(AddressFamily.InterNetwork);
            await client.ConnectAsync(IPAddress.Loopback, port, deadline.Token);
            await using var stream = client.GetStream();
            using var handshake = new MemoryStream();
            WriteVarInt(handshake, 0); // Handshake packet id.
            WriteVarInt(handshake, -1); // Status protocol version; no login negotiation.
            WriteVarInt(handshake, Address.Length); handshake.Write(Address);
            handshake.WriteByte((byte)(port >> 8)); handshake.WriteByte((byte)port);
            WriteVarInt(handshake, 1); // Status state.
            using var request = new MemoryStream();
            WriteVarInt(request, checked((int)handshake.Length));
            handshake.Position = 0; handshake.CopyTo(request);
            request.WriteByte(1); request.WriteByte(0); // Empty status request packet.
            await stream.WriteAsync(request.ToArray(), deadline.Token);
            var length = await ReadVarIntAsync(stream, deadline.Token);
            if (length is < 2 or > MaximumPacketBytes) return null;
            var packet = new byte[length];
            await stream.ReadExactlyAsync(packet, deadline.Token);
            using var response = new MemoryStream(packet, writable: false);
            if (await ReadVarIntAsync(response, deadline.Token) != 0) return null;
            var jsonLength = await ReadVarIntAsync(response, deadline.Token);
            if (jsonLength <= 0 || jsonLength != response.Length - response.Position) return null;
            using var json = JsonDocument.Parse(packet.AsMemory(checked((int)response.Position), jsonLength),
                new JsonDocumentOptions { MaxDepth = 16 });
            if (!json.RootElement.TryGetProperty("players", out var players) || players.ValueKind != JsonValueKind.Object ||
                !players.TryGetProperty("online", out var online) || !online.TryGetInt32(out var count) || count < 0)
                return null;
            return count;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { return null; }
        catch (Exception exception) when (exception is SocketException or IOException or JsonException or InvalidOperationException or OverflowException) { return null; }
    }

    private static void WriteVarInt(Stream stream, int value)
    {
        var remaining = unchecked((uint)value);
        do
        {
            var next = (byte)(remaining & 0x7f);
            remaining >>= 7;
            stream.WriteByte(remaining == 0 ? next : (byte)(next | 0x80));
        } while (remaining != 0);
    }

    private static async Task<int> ReadVarIntAsync(Stream stream, CancellationToken cancellationToken)
    {
        var value = 0;
        var buffer = new byte[1];
        for (var i = 0; i < 5; i++)
        {
            await stream.ReadExactlyAsync(buffer, cancellationToken);
            if (i == 4 && (buffer[0] & 0xf0) != 0) throw new InvalidDataException("VarInt overflow.");
            value |= (buffer[0] & 0x7f) << (7 * i);
            if ((buffer[0] & 0x80) == 0) return value;
        }
        throw new InvalidDataException("VarInt too long.");
    }
}
