using System.Globalization;

namespace SkillTreeEditor.Server.Services;

internal sealed record MobileHostOptions(Uri Origin, int LoopbackPort)
{
    public string OriginText { get; } = Origin.GetLeftPart(UriPartial.Authority);

    public bool MatchesOrigin(string? value) => string.Equals(OriginText, value, StringComparison.OrdinalIgnoreCase);

    public bool MatchesPublicHost(string? value) =>
        string.Equals(Origin.Authority, value, StringComparison.OrdinalIgnoreCase)
        || Origin.IsDefaultPort && string.Equals(Origin.Host + ":443", value, StringComparison.OrdinalIgnoreCase);

    public bool MatchesLoopbackHost(string? value) =>
        string.Equals("127.0.0.1:" + LoopbackPort.ToString(CultureInfo.InvariantCulture), value, StringComparison.Ordinal)
        || string.Equals("localhost:" + LoopbackPort.ToString(CultureInfo.InvariantCulture), value, StringComparison.OrdinalIgnoreCase);

    public static MobileHostOptions? FromArguments(string[] args)
    {
        // The opt-in belongs to this host instance, including when Desktop creates two hosts in one process.
        var config = new ConfigurationBuilder().AddCommandLine(args).Build();
        var origin = config["SkillTreeEditor:MobileOrigin"];
        var port = config["SkillTreeEditor:MobilePort"];
        if (origin is null && port is null)
            return null;

        if (!string.IsNullOrEmpty(config["SkillTreeEditor:DesktopSessionToken"]))
            throw new ArgumentException("モバイルホストにデスクトップのセッショントークンは指定できません。");

        if (!Uri.TryCreate(origin, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttps || uri.HostNameType != UriHostNameType.Dns
            || !uri.IdnHost.EndsWith(".ts.net", StringComparison.OrdinalIgnoreCase)
            || uri.UserInfo.Length != 0 || uri.AbsolutePath != "/" || uri.Query.Length != 0 || uri.Fragment.Length != 0
            || !string.Equals(origin!.TrimEnd('/'), uri.GetLeftPart(UriPartial.Authority), StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("モバイルホストには Tailscale Serve の HTTPS オリジンを指定してください。");

        if (!int.TryParse(port, NumberStyles.None, CultureInfo.InvariantCulture, out var listenPort)
            || listenPort is < 1024 or > 65535)
            throw new ArgumentException("モバイルホストには 1024～65535 のループバックポートを指定してください。");

        return new MobileHostOptions(uri, listenPort);
    }
}
