using AstralRecordControl.Agent;
using AstralRecordControl.Contracts;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("Agent requires Windows.");
var dataRoot = AgentConfiguration.DefaultDataRoot;
var configure = args.Contains("--configure", StringComparer.Ordinal);
var remaining = args.Where(a => a != "--configure").ToArray();
if (remaining.Length == 2 && remaining[0] == "--data-root") dataRoot = Path.GetFullPath(remaining[1]);
else if (remaining.Length != 0) throw new ArgumentException("Usage: AstralRecordControl.Agent [--configure] [--data-root path]");
AgentResourceBoundary.ValidateLocalPath(dataRoot);
Directory.CreateDirectory(dataRoot);
AgentResourceBoundary.ValidateLocalPath(Path.Combine(dataRoot, "service.lock"));
using var instanceLock = new FileStream(Path.Combine(dataRoot, "service.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
var configPath = Path.Combine(dataRoot, "config.json");
AgentResourceBoundary.ValidateLocalPath(configPath);
if (configure)
{
    CredentialAcl.RequireAdministrator();
    var existing = File.Exists(configPath) ? ControlJson.Deserialize<AgentConfiguration>(File.ReadAllText(configPath)) : new AgentConfiguration();
    Console.Write("Host ID: ");
    var hostId = Guid.Parse(Console.ReadLine() ?? "");
    Console.Write("Control HTTPS origin: ");
    var origin = Console.ReadLine() ?? "";
    var configured = existing with { HostId = hostId, ServerUrl = origin };
    configured.Validate();
    Console.Write("Agent token: ");
    string token;
    if (Console.IsInputRedirected) token = Console.ReadLine() ?? "";
    else
    {
        var input = new System.Text.StringBuilder();
        ConsoleKeyInfo key;
        while ((key = Console.ReadKey(true)).Key != ConsoleKey.Enter)
        {
            if (key.Key == ConsoleKey.Backspace) { if (input.Length > 0) input.Length--; }
            else if (!char.IsControl(key.KeyChar)) input.Append(key.KeyChar);
        }
        token = input.ToString(); input.Clear(); Console.WriteLine();
    }
    new ProtectedTokenFile(dataRoot, configured.TokenFile).Write(token);
    var temporary = configPath + "." + Guid.NewGuid().ToString("N") + ".tmp";
    File.WriteAllText(temporary, ControlJson.Serialize(configured));
    File.Move(temporary, configPath, true);
    Console.WriteLine("Agent configuration saved. Token protected for this machine and service ACL.");
    return;
}
var configuration = ControlJson.Deserialize<AgentConfiguration>(File.ReadAllText(configPath));
configuration.Validate();
var builder = Host.CreateApplicationBuilder(Array.Empty<string>());
builder.Services.AddWindowsService(options => options.ServiceName = "AstralRecordControl.Agent");
builder.Services.AddSingleton(configuration);
builder.Services.AddSingleton(_ => new AgentStore(dataRoot, configuration.HostId));
builder.Services.AddSingleton(_ => new ProtectedTokenFile(dataRoot, configuration.TokenFile));
builder.Services.AddSingleton<IRunnerClient, RunnerClient>();
builder.Services.AddSingleton<InventoryApplier>();
builder.Services.AddSingleton(new AgentProcessControlGate(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AstralRecordControl", "managed-operations.enabled")));
builder.Services.AddSingleton<AgentOperationHandler>();
builder.Services.AddSingleton(_ => new AstralRecordControl.Agent.Storage.P2StorageService(Path.Combine(dataRoot, "storage"), configuration.LocalResources));
builder.Services.AddHostedService<AgentConnectionService>();
await builder.Build().RunAsync();
