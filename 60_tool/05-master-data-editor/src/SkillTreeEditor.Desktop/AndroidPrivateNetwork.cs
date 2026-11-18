using System.Diagnostics;
using System.IO;
using System.Text.Json;

namespace SkillTreeEditor.Desktop;

internal static class AndroidPrivateNetwork
{
    internal const int HttpsPort = 8443;
    internal const int LocalPort = 5285;
    private static readonly string Port = HttpsPort.ToString(System.Globalization.CultureInfo.InvariantCulture);
    private static readonly string Target = $"http://127.0.0.1:{LocalPort}";
    internal static string SetupCommand => $"tailscale serve --bg --https={Port} {Target}";

    internal static bool IsEditorOrigin(Uri origin) =>
        origin.Scheme == Uri.UriSchemeHttps && origin.HostNameType == UriHostNameType.Dns
        && origin.Host.EndsWith(".ts.net", StringComparison.OrdinalIgnoreCase) && origin.Port == HttpsPort
        && origin.UserInfo.Length == 0 && origin.PathAndQuery == "/" && origin.Fragment.Length == 0;

    internal static async Task<Uri> GetOriginAsync()
    {
        using var status = JsonDocument.Parse(await RunAsync("status", "--json"));
        var node = status.RootElement;
        if (!node.TryGetProperty("BackendState", out var state) || state.GetString() != "Running"
            || !node.TryGetProperty("Self", out var self) || !self.TryGetProperty("DNSName", out var name))
            throw new InvalidOperationException("WindowsのTailscaleにログインしてから「接続を開始」を押してください。");
        var dns = name.GetString()?.TrimEnd('.');
        if (Uri.CheckHostName(dns) != UriHostNameType.Dns
            || !Uri.TryCreate($"https://{dns}:{Port}", UriKind.Absolute, out var origin) || !IsEditorOrigin(origin))
            throw new InvalidOperationException("TailscaleでMagicDNSとHTTPSを有効にしてください。");
        return origin;
    }

    internal static async Task ConfigureServeAsync(Uri origin)
    {
        if (await IsConfiguredAsync(origin)) return;
        try
        {
            await RunAsync("serve", "--bg", $"--https={Port}", Target);
            if (!await IsConfiguredAsync(origin))
                throw new InvalidOperationException("TailscaleのHTTPS接続先を確認できませんでした。「初回の設定」を確認してください。");
        }
        catch (Exception setupError)
        {
            // The daemon may have installed the route even when the CLI confirmation timed out.
            try { await RemoveServeAsync(origin); }
            catch (Exception cleanupError)
            {
                throw new IOException($"Tailscaleの設定を確認してください。{setupError.Message}\n"
                    + $"専用転送の解除結果を確認できませんでした。{cleanupError.Message}", setupError);
            }
            throw;
        }
    }

    internal static async Task<bool> IsConfiguredAsync(Uri origin)
    {
        using var status = JsonDocument.Parse(await RunAsync("serve", "status", "--json"));
        return HasPrivateRoute(status.RootElement, origin.Authority);
    }

    internal static async Task RemoveServeAsync(Uri origin)
    {
        if (await IsConfiguredAsync(origin))
            await RunAsync("serve", $"--https={Port}", "off");
    }

    // A port shared with a different handler or Funnel must never be repurposed for the editor.
    internal static bool HasPrivateRoute(JsonElement config, string authority)
    {
        if (config.ValueKind == JsonValueKind.Null) return false;
        if (config.ValueKind != JsonValueKind.Object) throw new InvalidDataException("Tailscaleの設定形式を確認してください。");
        if (config.TryGetProperty("Foreground", out var foreground))
            foreach (var entry in foreground.EnumerateObject())
                if (HasPrivateRoute(entry.Value, authority)) throw PortInUse();
        if (config.TryGetProperty("AllowFunnel", out var funnel)
            && funnel.TryGetProperty(authority, out var allowed) && allowed.GetBoolean()) throw PortInUse();
        if (!config.TryGetProperty("TCP", out var ports) || !ports.TryGetProperty(Port, out var listener)) return false;
        if (listener.TryGetProperty("HTTPS", out var https) && https.ValueKind == JsonValueKind.True
            && config.TryGetProperty("Web", out var web) && web.TryGetProperty(authority, out var site)
            && web.EnumerateObject().Count(entry => entry.Name.EndsWith(":" + Port, StringComparison.Ordinal)) == 1
            && site.TryGetProperty("Handlers", out var paths) && paths.EnumerateObject().Count() == 1
            && paths.TryGetProperty("/", out var root) && root.TryGetProperty("Proxy", out var proxy)
            && proxy.GetString()?.TrimEnd('/') == Target) return true;
        throw PortInUse();
    }

    private static InvalidOperationException PortInUse() =>
        new("Tailscaleの8443番を別の接続設定が使用しています。設定を確認してから再度お試しください。");

    private static async Task<string> RunAsync(params string[] arguments)
    {
        var executable = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Tailscale", "tailscale.exe");
        if (!File.Exists(executable)) throw new FileNotFoundException("このPCにTailscaleをインストールしてログインしてください。");
        var start = new ProcessStartInfo(executable)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
        };
        foreach (var argument in arguments) start.ArgumentList.Add(argument);
        using var process = Process.Start(start) ?? throw new IOException("Tailscaleを起動できませんでした。");
        process.StandardInput.Close();
        var output = process.StandardOutput.ReadToEndAsync();
        var error = process.StandardError.ReadToEndAsync();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        try { await process.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException)
        {
            if (!process.HasExited) process.Kill();
            await Task.WhenAll(output, error);
            throw new InvalidOperationException("Tailscaleの応答がありません。「初回の設定」のコマンドでHTTPSを有効にしてください。");
        }
        await Task.WhenAll(output, error);
        if (process.ExitCode != 0)
            throw new InvalidOperationException("Tailscaleのログイン状態と「初回の設定」を確認してください。" + Environment.NewLine + (await error).Trim());
        return await output;
    }
}
