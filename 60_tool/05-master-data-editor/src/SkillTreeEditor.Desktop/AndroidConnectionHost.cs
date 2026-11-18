using System.IO;
using Microsoft.AspNetCore.Builder;
using SkillTreeEditor.Server;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Desktop;

internal sealed class AndroidConnectionHost(string workspace, string settingsDirectory)
{
    private WebApplication? app;
    private string SettingsFile => Path.Combine(settingsDirectory, "android-origin.txt");
    internal string ApkDirectory => Path.Combine(workspace, "60_tool", "05-master-data-editor", "dist", "android");
    internal Uri? Origin { get; private set; }
    internal MobileDeviceRegistry Devices { get; } = new(workspace);

    internal async Task EnableAsync()
    {
        if (app is not null) return;
        var origin = await AndroidPrivateNetwork.GetOriginAsync();
        await StartAsync(origin);
        try
        {
            await AndroidPrivateNetwork.ConfigureServeAsync(origin);
            Directory.CreateDirectory(settingsDirectory);
            var temporary = SettingsFile + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                await File.WriteAllTextAsync(temporary, origin.GetLeftPart(UriPartial.Authority));
                File.Move(temporary, SettingsFile, overwrite: true);
            }
            finally { File.Delete(temporary); }
        }
        catch
        {
            await StopAsync();
            throw;
        }
    }

    internal async Task ResumeAsync()
    {
        if (!File.Exists(SettingsFile)) return;
        var address = await File.ReadAllTextAsync(SettingsFile);
        if (!Uri.TryCreate(address, UriKind.Absolute, out var origin) || !AndroidPrivateNetwork.IsEditorOrigin(origin))
            throw new InvalidDataException("Android接続の保存先を確認できません。受付を設定し直してください。");
        if (!await AndroidPrivateNetwork.IsConfiguredAsync(origin)) return;
        await StartAsync(origin);
    }

    private async Task StartAsync(Uri origin)
    {
        var starting = EditorApplication.Create([
            "--SkillTreeEditor:WorkspaceRoot", workspace,
            "--SkillTreeEditor:MobileOrigin", origin.GetLeftPart(UriPartial.Authority),
            "--SkillTreeEditor:MobilePort", AndroidPrivateNetwork.LocalPort.ToString(System.Globalization.CultureInfo.InvariantCulture),
            "--Logging:LogLevel:Default", "Warning"
        ]);
        try { await starting.StartAsync(); }
        catch
        {
            await starting.DisposeAsync();
            throw;
        }
        app = starting;
        Origin = origin;
    }

    internal async Task DisableAsync()
    {
        File.Delete(SettingsFile);
        await StopAsync();
    }

    internal async Task StopAsync()
    {
        if (app is null) return;
        var stopping = app;
        app = null;
        Origin = null;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        try { await stopping.StopAsync(timeout.Token); }
        finally { await stopping.DisposeAsync(); }
    }
}
