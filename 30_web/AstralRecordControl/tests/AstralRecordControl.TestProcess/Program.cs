namespace AstralRecordControl.TestProcess;

public sealed class Marker;

public static class Program
{
    public static async Task Main(string[] args)
    {
        Console.InputEncoding = new System.Text.UTF8Encoding(false);
        Console.OutputEncoding = new System.Text.UTF8Encoding(false);
        var ignoreStop = args.Contains("ignore-stop", StringComparer.Ordinal);
        Console.WriteLine("fixture-ready");
        Console.Error.WriteLine("fixture-stderr");
        Console.WriteLine("fixture-utf8-stdout:日本語😀\u001b[90m灰色\u001b[0m");
        Console.Error.WriteLine("fixture-utf8-stderr:警告⚠️\u001b[31m赤色\u001b[0m");
        if (args.Contains("no-read", StringComparer.Ordinal)) await Task.Delay(Timeout.InfiniteTimeSpan);
        while (true)
        {
            var line = await Console.In.ReadLineAsync();
            if (line is null) { await Task.Delay(100); continue; }
            if (line == "stop" && !ignoreStop) return;
            if (line == "crash") Environment.Exit(17);
            if (line == "environment-probe")
            {
                var temporary = Path.GetTempPath();
                var path = Path.Combine(temporary, "child-probe-" + Guid.NewGuid().ToString("N"));
                File.WriteAllText(path, "fixture");
                File.Delete(path);
                using var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
                listener.Start();
                Console.WriteLine("environment-system-root:" + Environment.GetEnvironmentVariable("SystemRoot"));
                Console.WriteLine("environment-temp:" + temporary);
                Console.WriteLine("environment-parent-only:" + (Environment.GetEnvironmentVariable("ASTRAL_CONTROL_PARENT_ONLY_FIXTURE") is null ? "absent" : "present"));
                Console.WriteLine("environment-io-ready");
            }
            if (line == "long-line") Console.WriteLine(new string('x', 70 * 1024));
            else Console.WriteLine("echo:" + line);
        }
    }
}
