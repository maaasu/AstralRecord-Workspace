using System.Security.Principal;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

if (args.Length > 0 && args[0] != "owner")
{
    if (!OperatingSystem.IsWindows()) { Console.Error.WriteLine("Control CLI requires Windows."); return 2; }
    return await AstralRecordControl.Cli.ControlCli.RunAsync(args);
}

if (args.Length != 2 || args[0] != "owner" || args[1] is not ("create" or "reset"))
{
    Console.Error.WriteLine("使用法: AstralRecordControl.Cli owner create|reset（対話専用）");
    return 2;
}
if (!OperatingSystem.IsWindows()) { Console.Error.WriteLine("WindowsのローカルOS管理者として実行してください。"); return 2; }
using (var identity = WindowsIdentity.GetCurrent())
{
    if (!new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator))
    { Console.Error.WriteLine("管理者として実行してください。"); return 2; }
}
if (Console.IsInputRedirected || Console.IsOutputRedirected)
{ Console.Error.WriteLine("対話ターミナルが必要です。秘密を引数・パイプへ渡せません。"); return 2; }
try
{
    var builder = Host.CreateApplicationBuilder([]);
    // The CLI never starts hosted Web services and does not accept environment overrides through arguments.
    builder.Environment.EnvironmentName = Environments.Production;
    builder.Logging.ClearProviders();
    OwnerAuthentication.AddServices(builder.Services, builder.Configuration, builder.Environment);
    using var host = builder.Build();
    var storage = host.Services.GetRequiredService<ControlStorage>();
    using var lease = storage.AcquireExclusiveLease();
    using var scope = host.Services.CreateScope();
    await ControlDatabase.InitializeAsync(scope.ServiceProvider.GetRequiredService<ControlDbContext>());
    Console.WriteLine($"対象: {storage.DatabasePath}");
    var name = "";
    if (args[1] == "create") { Console.Write("管理者名: "); name = Console.ReadLine() ?? ""; }
    var password = ReadSecret("新しいパスワード: ");
    if (password != ReadSecret("新しいパスワード（確認）: ")) { Console.Error.WriteLine("パスワードが一致しません。"); return 1; }
    if (args[1] == "reset")
    {
        Console.Write("全セッションを失効します。RESETと入力: ");
        if (Console.ReadLine() != "RESET") return 1;
    }
    var owner = scope.ServiceProvider.GetRequiredService<OwnerAuthService>();
    var result = args[1] == "create" ? await owner.CreateAsync(name, password) : await owner.ResetAsync(password);
    if (!result.Succeeded)
    { foreach (var error in result.Errors) Console.Error.WriteLine(error.Description); return 1; }
    Console.WriteLine("完了しました。Webを起動し、パスワードでログインしてください。");
    return 0;
}
catch (IOException) { Console.Error.WriteLine("Webが起動中、またはデータフォルダにアクセスできません。Webを停止してから再実行してください。"); return 1; }
catch (Exception exception) when (exception is InvalidOperationException or UnauthorizedAccessException)
{ Console.Error.WriteLine("データ・権限・スキーマの検査に失敗しました。設定とWebの停止状態を確認してください。"); return 1; }

static string ReadSecret(string prompt)
{
    Console.Write(prompt);
    var value = new System.Text.StringBuilder();
    while (true)
    {
        var key = Console.ReadKey(intercept: true);
        if (key.Key == ConsoleKey.Enter) { Console.WriteLine(); return value.ToString(); }
        if (key.Key == ConsoleKey.Backspace) { if (value.Length > 0) value.Length--; continue; }
        if (!char.IsControl(key.KeyChar) && value.Length < 1024) value.Append(key.KeyChar);
    }
}
