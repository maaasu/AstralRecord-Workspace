namespace AstralRecordControl.TestProcess;

public sealed class Marker;

public static class Program
{
    public static async Task Main(string[] args)
    {
        var ignoreStop = args.Contains("ignore-stop", StringComparer.Ordinal);
        Console.WriteLine("fixture-ready");
        Console.Error.WriteLine("fixture-stderr");
        while (true)
        {
            var line = await Console.In.ReadLineAsync();
            if (line is null) { await Task.Delay(100); continue; }
            if (line == "stop" && !ignoreStop) return;
            if (line == "crash") Environment.Exit(17);
            if (line == "long-line") Console.WriteLine(new string('x', 70 * 1024));
            else Console.WriteLine("echo:" + line);
        }
    }
}
