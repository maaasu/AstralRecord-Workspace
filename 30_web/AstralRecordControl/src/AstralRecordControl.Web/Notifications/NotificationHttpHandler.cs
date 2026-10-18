using System.Net;
using System.Net.Sockets;

namespace AstralRecordControl.Web.Notifications;

public static class NotificationHttpHandler
{
    public static Uri ValidateUrl(string value)
    {
        if (value.Length is < 1 or > 2048 || !Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttps || uri.Port != 443 || string.IsNullOrEmpty(uri.Host)
            || uri.UserInfo.Length != 0 || uri.Fragment.Length != 0 || uri.HostNameType != UriHostNameType.Dns
            || uri.IdnHost.EndsWith(".local", StringComparison.OrdinalIgnoreCase)
            || uri.IdnHost.Equals("localhost", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("HTTPS webhook destination is invalid.");
        return uri;
    }

    public static SocketsHttpHandler Create() => new()
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        UseProxy = false,
        PooledConnectionLifetime = TimeSpan.Zero,
        ConnectCallback = async (context, cancellationToken) =>
        {
            var addresses = await Dns.GetHostAddressesAsync(context.DnsEndPoint.Host, cancellationToken);
            if (addresses.Length == 0 || addresses.Any(x => !IsPublic(x))) throw new HttpRequestException("Webhook destination unavailable.");
            var address = addresses[0];
            var socket = new Socket(address.AddressFamily, SocketType.Stream, ProtocolType.Tcp);
            try
            {
                await socket.ConnectAsync(new IPEndPoint(address, context.DnsEndPoint.Port), cancellationToken);
                return new NetworkStream(socket, ownsSocket: true);
            }
            catch { socket.Dispose(); throw; }
        }
    };

    private static bool IsPublic(IPAddress address)
    {
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (address.AddressFamily == AddressFamily.InterNetworkV6)
        {
            var b = address.GetAddressBytes();
            // Only IPv6 global unicast (2000::/3); exclude documentation and
            // transition ranges which can carry embedded IPv4 addresses.
            return b[0] is >= 0x20 and <= 0x3f
                && !(b[0] == 0x20 && b[1] == 0x01 && (b[2] == 0x00 && b[3] == 0x00 || b[2] == 0x0d && b[3] == 0xb8))
                && !(b[0] == 0x20 && b[1] == 0x02);
        }
        if (address.AddressFamily != AddressFamily.InterNetwork) return false;
        var octets = address.GetAddressBytes();
        return octets[0] is not (0 or 10 or 127) && octets[0] < 224
            && !(octets[0] == 169 && octets[1] == 254)
            && !(octets[0] == 172 && octets[1] is >= 16 and <= 31)
            && !(octets[0] == 192 && octets[1] == 168)
            && !(octets[0] == 100 && octets[1] is >= 64 and <= 127)
            && !(octets[0] == 198 && (octets[1] is 18 or 19 || octets[1] == 51 && octets[2] == 100))
            && !(octets[0] == 192 && octets[1] == 0 && (octets[2] is 0 or 2))
            && !(octets[0] == 192 && octets[1] == 88 && octets[2] == 99)
            && !(octets[0] == 203 && octets[1] == 0 && octets[2] == 113);
    }
}
