using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using AstralRecordWeb.Options;
using Microsoft.Extensions.Options;

namespace AstralRecordWeb.Services;

public sealed record MinecraftServerStatus(string State, int? Players, int? MaximumPlayers, DateTimeOffset CheckedAt)
{
    public string Label => State switch { "online" => "公開中", "offline" => "閉鎖中", _ => "状況を確認できません" };
}

public interface IMinecraftStatusProbe
{
    Task<MinecraftServerStatus> QueryAsync(string host, int port, CancellationToken cancellationToken);
}

/// <summary>Java Edition の Server List Ping。ログインやプレイヤーデータ取得は行わない。</summary>
public sealed class MinecraftStatusProbe(TimeProvider timeProvider) : IMinecraftStatusProbe
{
    public async Task<MinecraftServerStatus> QueryAsync(string host, int port, CancellationToken cancellationToken)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(4));
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(host, port, deadline.Token);
            await using var stream = client.GetStream();
            using var handshake = new MemoryStream();
            WriteVarInt(handshake, 0); // Handshake packet id.
            WriteVarInt(handshake, -1); // Status inquiry; no game protocol version is negotiated.
            var hostBytes = Encoding.UTF8.GetBytes(host);
            if (hostBytes.Length > 255) throw new InvalidDataException("Server address is too long.");
            WriteVarInt(handshake, hostBytes.Length);
            handshake.Write(hostBytes);
            handshake.WriteByte((byte)(port >> 8));
            handshake.WriteByte((byte)port);
            WriteVarInt(handshake, 1); // Next state: status.
            using var request = new MemoryStream();
            WriteVarInt(request, checked((int)handshake.Length));
            handshake.Position = 0;
            handshake.CopyTo(request);
            request.Write([1, 0]); // Status request: one-byte packet body with packet id zero.
            await stream.WriteAsync(request.ToArray(), deadline.Token);
            var packetLength = await ReadVarIntAsync(stream, deadline.Token);
            if (packetLength is < 2 or > 262144) throw new InvalidDataException("Invalid status packet length.");
            var packet = new byte[packetLength];
            await stream.ReadExactlyAsync(packet, deadline.Token);
            using var response = new MemoryStream(packet, writable: false);
            if (await ReadVarIntAsync(response, deadline.Token) != 0) throw new InvalidDataException("Unexpected status packet.");
            var jsonLength = await ReadVarIntAsync(response, deadline.Token);
            if (jsonLength <= 0 || jsonLength != response.Length - response.Position) throw new InvalidDataException("Invalid status JSON length.");
            using var json = JsonDocument.Parse(packet.AsMemory(checked((int)response.Position), jsonLength));
            int? online = null, maximum = null;
            if (json.RootElement.TryGetProperty("players", out var players) && players.ValueKind == JsonValueKind.Object)
            {
                if (players.TryGetProperty("online", out var count) && count.TryGetInt32(out var value) && value >= 0) online = value;
                if (players.TryGetProperty("max", out var max) && max.TryGetInt32(out var maxValue) && maxValue >= 0) maximum = maxValue;
            }
            return new("online", online, maximum, timeProvider.GetUtcNow());
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return new("unknown", null, null, timeProvider.GetUtcNow());
        }
        catch (SocketException ex)
        {
            var state = ex.SocketErrorCode == SocketError.ConnectionRefused ? "offline" : "unknown";
            return new(state, null, null, timeProvider.GetUtcNow());
        }
        catch (Exception ex) when (ex is IOException or InvalidDataException or JsonException or InvalidOperationException)
        {
            return new("unknown", null, null, timeProvider.GetUtcNow());
        }
    }

    private static void WriteVarInt(Stream stream, int value)
    {
        var remaining = unchecked((uint)value);
        do
        {
            var current = (byte)(remaining & 0x7f);
            remaining >>= 7;
            stream.WriteByte(remaining == 0 ? current : (byte)(current | 0x80));
        } while (remaining != 0);
    }

    private static async Task<int> ReadVarIntAsync(Stream stream, CancellationToken ct)
    {
        var value = 0;
        var buffer = new byte[1];
        for (var i = 0; i < 5; i++)
        {
            await stream.ReadExactlyAsync(buffer, ct);
            if (i == 4 && (buffer[0] & 0xf0) != 0) throw new InvalidDataException("Status VarInt overflow.");
            value |= (buffer[0] & 0x7f) << (i * 7);
            if ((buffer[0] & 0x80) == 0) return value;
        }
        throw new InvalidDataException("Status VarInt is too long.");
    }
}

/// <summary>サイト全体で接続先ごとではなく設定済みサーバー1件の結果を15秒共有する。</summary>
public sealed class MinecraftServerStatusService(
    IMinecraftStatusProbe probe, IOptions<PublicSiteOptions> options, TimeProvider timeProvider) : IDisposable
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private MinecraftServerStatus? cached;

    public async Task<MinecraftServerStatus> GetAsync(CancellationToken ct)
    {
        await gate.WaitAsync(ct);
        try
        {
            if (cached is not null && timeProvider.GetUtcNow() - cached.CheckedAt < TimeSpan.FromSeconds(15)) return cached;
            cached = await probe.QueryAsync(options.Value.JavaServerAddress, options.Value.JavaServerPort, ct);
            return cached;
        }
        finally { gate.Release(); }
    }

    public void Dispose() => gate.Dispose();
}
