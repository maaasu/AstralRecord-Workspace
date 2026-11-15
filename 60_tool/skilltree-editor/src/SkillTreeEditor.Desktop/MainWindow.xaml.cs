using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Interop;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;
using SkillTreeEditor.Server;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Desktop;

public partial class MainWindow : Window
{
    private WebApplication? server;
    private Uri? origin;
    private string sessionToken = "";
    private string? workspace;
    private string? restoreScriptId;
    private bool changingWorkspace;
    private bool closeApproved;
    private bool checkingClose;
    private readonly string[] arguments;
    private static string SettingsPath => Path.Combine(App.StateDirectory, "settings.json");

    public MainWindow(string[] arguments)
    {
        this.arguments = arguments;
        InitializeComponent();
        Loaded += OnLoaded;
        Closing += OnClosing;
        SourceInitialized += (_, _) => ApplyTitleBarColors();
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        changingWorkspace = true;
        try
        {
            var selected = ResolveInitialWorkspace();
            if (selected is null)
            {
                ShowWorkspaceSelection("40_filebase・10_plugin・60_tool がある AstralRecord のフォルダーを選んでください。");
                return;
            }
            await OpenWorkspaceAsync(selected);
        }
        catch (Exception error) { ShowFailure(error); }
        finally { changingWorkspace = false; }
    }

    private string? ResolveInitialWorkspace()
    {
        if (arguments.Length > 0)
        {
            if (arguments.Length == 1 && arguments[0] == "--select-workspace") return null;
            if (arguments.Length == 2 && arguments[0] == "--workspace")
                return ValidateWorkspace(arguments[1]);
            throw new ArgumentException("起動引数は --workspace <フォルダー> または --select-workspace を指定してください。");
        }

        if (File.Exists(SettingsPath))
        {
            try
            {
                var settings = JsonSerializer.Deserialize<DesktopSettings>(File.ReadAllText(SettingsPath));
                if (settings?.Workspace is { } saved && IsWorkspace(saved)) return Path.GetFullPath(saved);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
            { App.RecordFailure(error); }
        }

        // On first launch the workspace is chosen explicitly, even when the ZIP is inside a repository.
        return null;
    }

    private async Task OpenWorkspaceAsync(string selected)
    {
        selected = ValidateWorkspace(selected);
        Browser.Visibility = Visibility.Hidden;
        LoadingPanel.Visibility = Visibility.Visible;
        SelectWorkspaceButton.Visibility = Visibility.Collapsed;
        StatusText.Text = "マスタデータを読み込んでいます…";
        await SavePreferencesAsync();
        await StopServerAsync();
        sessionToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        server = EditorApplication.Create([
            "--SkillTreeEditor:WorkspaceRoot", selected,
            "--SkillTreeEditor:DesktopSessionToken", sessionToken,
            "--Logging:LogLevel:Default", "Warning"
        ]);
        await server.StartAsync();
        var addresses = server.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>();
        origin = new Uri(addresses?.Addresses.Single() ?? throw new InvalidOperationException("アプリの起動先を取得できませんでした。"));
        workspace = selected;
        Title = $"AstralRecord マスタデータエディタ — {selected}";
        await InitializeBrowserAsync();
        await RestorePreferencesAsync(selected);
        Browser.CoreWebView2.Navigate(origin.AbsoluteUri);
        try
        {
            Directory.CreateDirectory(App.StateDirectory);
            await File.WriteAllTextAsync(SettingsPath, JsonSerializer.Serialize(new DesktopSettings(selected)));
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { App.RecordFailure(error); }
    }

    private async Task InitializeBrowserAsync()
    {
        if (Browser.CoreWebView2 is not null) return;
        Directory.CreateDirectory(App.StateDirectory);
        var environment = await CoreWebView2Environment.CreateAsync(
            userDataFolder: Path.Combine(App.StateDirectory, "WebView2"));
        await Browser.EnsureCoreWebView2Async(environment);
        var core = Browser.CoreWebView2 ?? throw new InvalidOperationException("WebView2 の初期化に失敗しました。");
        core.Settings.AreHostObjectsAllowed = false;
        core.Settings.AreDevToolsEnabled = false;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.IsPasswordAutosaveEnabled = false;
        core.Settings.IsGeneralAutofillEnabled = false;
        core.Settings.IsZoomControlEnabled = true;
        core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All,
            CoreWebView2WebResourceRequestSourceKinds.All);
        core.WebResourceRequested += (_, e) =>
        {
            if (IsAppUri(e.Request.Uri))
                e.Request.Headers.SetHeader(DesktopSessionBoundaryMiddleware.HeaderName, sessionToken);
            else if (Uri.TryCreate(e.Request.Uri, UriKind.Absolute, out var uri) && uri.Scheme is "http" or "https")
                e.Response = environment.CreateWebResourceResponse(null, 403, "Forbidden", "");
        };
        core.NavigationStarting += (_, e) =>
        {
            if (!IsAppUri(e.Uri)) e.Cancel = true;
        };
        core.FrameNavigationStarting += (_, e) => e.Cancel = true;
        core.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
            if (e.IsUserInitiated && Uri.TryCreate(e.Uri, UriKind.Absolute, out var uri)
                && uri.Scheme is "https" or "http" && !uri.IsLoopback)
                Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        };
        core.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
        core.NavigationCompleted += (_, e) =>
        {
            if (!e.IsSuccess)
            {
                ShowWorkspaceSelection($"画面を開けませんでした（{e.WebErrorStatus}）。ワークスペースを選び直してください。");
                return;
            }
            LoadingPanel.Visibility = Visibility.Collapsed;
            Browser.Visibility = Visibility.Visible;
        };
        core.WebMessageReceived += async (_, e) =>
        {
            if (!IsAppUri(e.Source)) return;
            string message;
            try { message = e.TryGetWebMessageAsString(); }
            catch (ArgumentException) { return; }
            if (message == "select-workspace") await SelectWorkspaceAsync();
        };
    }

    private bool IsAppUri(string value) => origin is not null
        && Uri.TryCreate(value, UriKind.Absolute, out var uri)
        && uri.Scheme == origin.Scheme && uri.Host == origin.Host && uri.Port == origin.Port;

    private async void SelectWorkspace_Click(object sender, RoutedEventArgs e) => await SelectWorkspaceAsync();

    private async Task SelectWorkspaceAsync()
    {
        if (changingWorkspace || checkingClose) return;
        changingWorkspace = true;
        try
        {
            var dialog = new OpenFolderDialog { Title = "AstralRecord ワークスペースを選択", Multiselect = false };
            if (workspace is not null) dialog.InitialDirectory = workspace;
            if (dialog.ShowDialog(this) != true) return;
            var selected = ValidateWorkspace(dialog.FolderName);
            if (string.Equals(selected, workspace, StringComparison.OrdinalIgnoreCase) && Browser.Visibility == Visibility.Visible) return;
            if (!await ConfirmDiscardAsync()) return;
            await OpenWorkspaceAsync(selected);
        }
        catch (Exception error) { ShowFailure(error); }
        finally { changingWorkspace = false; }
    }

    private async Task<bool> ConfirmDiscardAsync()
    {
        if (Browser.CoreWebView2 is null || !IsAppUri(Browser.Source?.AbsoluteUri ?? "")) return true;
        try
        {
            var dirty = await Browser.CoreWebView2.ExecuteScriptAsync("Boolean(window.astralEditorHasUnsavedChanges?.())");
            if (dirty != "true") return true;
        }
        catch (Exception error) { App.RecordFailure(error); }
        return MessageBox.Show(this, "未保存の変更がある可能性があります。変更を破棄して続けますか？",
            "未保存の変更", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No) == MessageBoxResult.Yes;
    }

    private async void OnClosing(object? sender, CancelEventArgs e)
    {
        if (closeApproved) return;
        e.Cancel = true;
        if (checkingClose || changingWorkspace) return;
        checkingClose = true;
        try
        {
            if (!await ConfirmDiscardAsync()) return;
            await SavePreferencesAsync();
            Browser.Dispose();
            await StopServerAsync();
            closeApproved = true;
            Close();
        }
        catch (Exception error)
        {
            App.RecordFailure(error);
            closeApproved = true;
            Close();
        }
        finally { checkingClose = false; }
    }

    private async Task StopServerAsync()
    {
        if (server is null) return;
        var stopping = server;
        server = null;
        origin = null;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        try { await stopping.StopAsync(timeout.Token); }
        finally { await stopping.DisposeAsync(); }
    }

    private static string PreferencesPath(string root)
    {
        var key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(root).ToUpperInvariant())));
        return Path.Combine(App.StateDirectory, "workspaces", key, "preferences.json");
    }

    private async Task SavePreferencesAsync()
    {
        if (workspace is null || Browser.CoreWebView2 is null || !IsAppUri(Browser.Source?.AbsoluteUri ?? "")) return;
        try
        {
            var scriptResult = await Browser.CoreWebView2.ExecuteScriptAsync("JSON.stringify(localStorage)");
            var json = JsonSerializer.Deserialize<string>(scriptResult);
            if (json is null) return;
            var path = PreferencesPath(workspace);
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var temporaryPath = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            try
            {
                await File.WriteAllTextAsync(temporaryPath, json);
                File.Move(temporaryPath, path, overwrite: true);
            }
            finally { if (File.Exists(temporaryPath)) File.Delete(temporaryPath); }
        }
        catch (Exception error) { App.RecordFailure(error); }
    }

    private async Task RestorePreferencesAsync(string selected)
    {
        var preferences = new Dictionary<string, string>();
        try
        {
            var path = PreferencesPath(selected);
            if (File.Exists(path))
                preferences = JsonSerializer.Deserialize<Dictionary<string, string>>(await File.ReadAllTextAsync(path)) ?? [];
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        { App.RecordFailure(error); }
        if (restoreScriptId is not null) Browser.CoreWebView2.RemoveScriptToExecuteOnDocumentCreated(restoreScriptId);
        var address = JsonSerializer.Serialize(origin!.GetLeftPart(UriPartial.Authority));
        var marker = JsonSerializer.Serialize(Guid.NewGuid().ToString("N"));
        var state = JsonSerializer.Serialize(preferences);
        // A reused dynamic port must not inherit another workspace's drafts or preferences.
        // The marker preserves same-tab reload behavior after this initial restoration.
        restoreScriptId = await Browser.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync($$"""
            if (location.origin === {{address}} && sessionStorage.getItem('astral.desktop.session') !== {{marker}}) {
              localStorage.clear(); sessionStorage.clear();
              for (const [key, value] of Object.entries({{state}})) localStorage.setItem(key, value);
              sessionStorage.setItem('astral.desktop.session', {{marker}});
            }
            """);
    }

    private void ShowFailure(Exception error)
    {
        App.RecordFailure(error);
        var message = error is WebView2RuntimeNotFoundException
            ? "Microsoft Edge WebView2 Runtime が見つかりません。配布フォルダーの README を参照してインストールしてください。"
            : $"起動できませんでした。{error.Message}";
        ShowWorkspaceSelection(message);
    }

    private void ShowWorkspaceSelection(string message)
    {
        Browser.Visibility = Visibility.Hidden;
        LoadingPanel.Visibility = Visibility.Visible;
        StatusText.Text = message;
        SelectWorkspaceButton.Visibility = Visibility.Visible;
    }

    private static bool IsWorkspace(string path) => Directory.Exists(path)
        && new[] { "40_filebase", "10_plugin", "60_tool" }.All(name => Directory.Exists(Path.Combine(path, name)));

    private static string ValidateWorkspace(string path)
    {
        var fullPath = Path.GetFullPath(path);
        if (!IsWorkspace(fullPath))
            throw new DirectoryNotFoundException("選んだフォルダーに 40_filebase・10_plugin・60_tool がありません。");
        return fullPath;
    }

    private void ApplyTitleBarColors()
    {
        var handle = new WindowInteropHelper(this).Handle;
        var darkMode = 1;
        var background = 0x00342c28;
        var foreground = 0x00ded3cd;
        _ = DwmSetWindowAttribute(handle, 20, ref darkMode, sizeof(int));
        _ = DwmSetWindowAttribute(handle, 35, ref background, sizeof(int));
        _ = DwmSetWindowAttribute(handle, 36, ref foreground, sizeof(int));
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    private sealed record DesktopSettings(string Workspace);
}
