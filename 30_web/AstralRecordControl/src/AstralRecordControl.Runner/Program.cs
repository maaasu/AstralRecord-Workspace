using AstralRecordControl.Contracts;
using AstralRecordControl.Runner;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("Runner requires Windows.");
var dataRoot = RunnerConfiguration.DefaultDataRoot;
if (args.Length == 2 && args[0] == "--data-root") dataRoot = Path.GetFullPath(args[1]);
else if (args.Length != 0) throw new ArgumentException("Usage: AstralRecordControl.Runner [--data-root path]");
LocalResourceBoundary.ValidateLocalPath(dataRoot);
Directory.CreateDirectory(dataRoot);
LocalResourceBoundary.ValidateLocalPath(Path.Combine(dataRoot, "service.lock"));
using var instanceLock = new FileStream(Path.Combine(dataRoot, "service.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
LocalResourceBoundary.ValidateLocalPath(Path.Combine(dataRoot, "config.json"));
var configuration = ControlJson.Deserialize<RunnerConfiguration>(File.ReadAllText(Path.Combine(dataRoot, "config.json")));
if (configuration.EnableFileControl && !configuration.EnableProcessControl) throw new InvalidOperationException("File control requires process control.");
if (configuration.EnableProcessControl)
{
    var marker = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AstralRecordControl", "managed-operations.enabled");
    LocalResourceBoundary.ValidateLocalPath(marker);
    if (!File.Exists(marker)) throw new InvalidOperationException("Process control requires the installed managed-operations gate.");
}
var builder = Host.CreateApplicationBuilder(Array.Empty<string>());
builder.Services.AddWindowsService(options => options.ServiceName = "AstralRecordControl.Runner");
builder.Services.AddSingleton(configuration);
builder.Services.AddSingleton(_ => new RunnerStore(dataRoot, configuration));
builder.Services.AddSingleton<RunnerDispatcher>();
builder.Services.AddHostedService<RunnerService>();
builder.Services.AddHostedService<RunnerAutomationService>();
await builder.Build().RunAsync();
