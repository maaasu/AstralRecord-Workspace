using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Windows;
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
    private AndroidConnectionHost? androidHost;
    private Uri? origin;
    private string sessionToken = "";
    private string? workspace;
    private string? restoreScriptId;
    private bool changingWorkspace;
    private bool closeApproved;
    private bool checkingClose;
    private bool acceptPopouts;
    private CoreWebView2Environment? browserEnvironment;
    private readonly Dictionary<string, DetachedEditorWindow> popouts = new(StringComparer.Ordinal);
    private readonly string[] arguments;
    private static string SettingsPath => Path.Combine(App.StateDirectory, "settings.json");

    public MainWindow(string[] arguments)
    {
        this.arguments = arguments;
        InitializeComponent();
        NativeWindowFrame.Apply(this);
        Loaded += OnLoaded;
        Closing += OnClosing;
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
        acceptPopouts = false;
        await ReturnAllPopoutsAsync();
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
        acceptPopouts = true;
        Browser.CoreWebView2.Navigate(origin.AbsoluteUri);
        androidHost = new AndroidConnectionHost(selected, Path.GetDirectoryName(PreferencesPath(selected))!);
        // The optional mobile listener must never prevent the local workspace from opening.
        try { await androidHost.ResumeAsync(); }
        catch (Exception error) { App.RecordFailure(error); }
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
        var bundledRuntime = Path.Combine(AppContext.BaseDirectory, "webview2");
        var browserDirectory = File.Exists(Path.Combine(bundledRuntime, "msedgewebview2.exe")) ? bundledRuntime : null;
        if (browserDirectory is not null) GrantWindows10RuntimeReadAccess(browserDirectory);
        browserEnvironment = await CoreWebView2Environment.CreateAsync(
            browserExecutableFolder: browserDirectory,
            userDataFolder: Path.Combine(App.StateDirectory, "WebView2"));
        await Browser.EnsureCoreWebView2Async(browserEnvironment);
        var core = Browser.CoreWebView2 ?? throw new InvalidOperationException("WebView2 の初期化に失敗しました。");
        ConfigureBrowser(core);
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
        core.WebMessageReceived += OnWebMessageReceived;
    }

    private void ConfigureBrowser(CoreWebView2 core)
    {
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
                e.Response = browserEnvironment!.CreateWebResourceResponse(null, 403, "Forbidden", "");
        };
        core.NavigationStarting += (_, e) =>
        {
            if (!IsAppUri(e.Uri)) e.Cancel = true;
        };
        core.FrameNavigationStarting += (_, e) => e.Cancel = true;
        core.NewWindowRequested += OnNewWindowRequested;
        core.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
    }

    private async void OnWebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (!IsAppUri(e.Source)) return;
        try
        {
            using var json = JsonDocument.Parse(e.WebMessageAsJson);
            var message = json.RootElement;
            if (message.ValueKind == JsonValueKind.String)
            {
                if (message.GetString() == "select-workspace") await SelectWorkspaceAsync();
                if (message.GetString() == "android-connections" && androidHost is not null
                    && !changingWorkspace && !checkingClose && IsEnabled)
                    new AndroidConnectionWindow(androidHost) { Owner = this }.ShowDialog();
                return;
            }
            if (message.ValueKind != JsonValueKind.Object || !message.TryGetProperty("type", out var type)) return;
            if (type.ValueKind == JsonValueKind.String && type.GetString() == "close-popout"
                && message.TryGetProperty("id", out var id)
                && id.ValueKind == JsonValueKind.String && popouts.TryGetValue(id.GetString()!, out var popout))
                popout.CloseFromBrowser();
        }
        catch (Exception error) when (error is JsonException or InvalidOperationException or ArgumentException)
        { App.RecordFailure(error); }
    }

    private async void OnNewWindowRequested(object? sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled = true;
        if (!IsAppUri(e.Uri) || new Uri(e.Uri).AbsolutePath != "/popout.html")
        {
            if (e.IsUserInitiated && Uri.TryCreate(e.Uri, UriKind.Absolute, out var external)
                && external.Scheme is "https" or "http" && !external.IsLoopback)
            {
                try { Process.Start(new ProcessStartInfo(external.AbsoluteUri) { UseShellExecute = true }); }
                catch (Exception error) { App.RecordFailure(error); }
            }
            return;
        }
        if (!acceptPopouts || checkingClose || browserEnvironment is null || popouts.Count >= 32) return;
        using var deferral = e.GetDeferral();
        var openingSession = sessionToken;
        DetachedEditorWindow? child = null;
        try
        {
            var id = e.Name;
            if (string.IsNullOrEmpty(id) || popouts.ContainsKey(id)) return;
            var openedWorkspace = workspace;
            child = new DetachedEditorWindow(Title, async window =>
            {
                var result = await window.Browser.CoreWebView2.ExecuteScriptAsync(
                    "typeof window.opener?.astralEditorReturnPopout === 'function' ? (window.opener.astralEditorReturnPopout(window), true) : false");
                if (result != "true") throw new InvalidOperationException("ビューを戻す処理が見つかりません。");
            });
            var features = e.WindowFeatures;
            if (features.HasSize)
            {
                var frame = SystemParameters.WindowNonClientFrameThickness;
                child.Width = Math.Clamp(features.Width + frame.Left + frame.Right, child.MinWidth, SystemParameters.VirtualScreenWidth);
                child.Height = Math.Clamp(features.Height + frame.Top + frame.Bottom, child.MinHeight, SystemParameters.VirtualScreenHeight);
            }
            if (features.HasPosition)
            {
                child.Left = Math.Clamp(features.Left, SystemParameters.VirtualScreenLeft, SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - child.Width);
                child.Top = Math.Clamp(features.Top, SystemParameters.VirtualScreenTop, SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - child.Height);
            }
            popouts.Add(id, child);
            var current = child;
            child.Closed += (_, _) => popouts.Remove(id);
            child.Show();
            await child.Browser.EnsureCoreWebView2Async(browserEnvironment);
            if (!acceptPopouts || checkingClose || openingSession != sessionToken) { child.CloseAfterReturn(); return; }
            var core = child.Browser.CoreWebView2;
            ConfigureBrowser(core);
            core.DocumentTitleChanged += (_, _) =>
            {
                if (!string.IsNullOrWhiteSpace(core.DocumentTitle))
                    current.Title = $"{core.DocumentTitle} — {openedWorkspace}";
            };
            core.WindowCloseRequested += (_, _) => current.CloseFromBrowser();
            e.NewWindow = core;
        }
        catch (Exception error)
        {
            App.RecordFailure(error);
            child?.CloseAfterReturn();
        }
    }

    private async Task ReturnAllPopoutsAsync()
    {
        try
        {
            if (Browser.CoreWebView2 is not null && IsAppUri(Browser.Source?.AbsoluteUri ?? ""))
            {
                // Record layout before returning popouts, including their saved bounds.
                await Browser.CoreWebView2.ExecuteScriptAsync("window.astralEditorReturnPopout?.()");
            }
        }
        catch (Exception error) { App.RecordFailure(error); }
        finally
        {
            foreach (var child in popouts.Values.ToArray()) child.CloseAfterReturn();
        }
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
            acceptPopouts = false;
            try
            {
                await ReturnAllPopoutsAsync();
                await SavePreferencesAsync();
            }
            finally
            {
                try { Browser.Dispose(); }
                finally { await StopServerAsync(); }
            }
            closeApproved = true;
            // With no workspace all awaited methods can complete synchronously. WPF does
            // not allow a second Close until this Closing event has returned.
            _ = Dispatcher.BeginInvoke(new Action(Close));
        }
        catch (Exception error)
        {
            App.RecordFailure(error);
            closeApproved = true;
            _ = Dispatcher.BeginInvoke(new Action(Close));
        }
        finally { checkingClose = false; }
    }

    private async Task StopServerAsync()
    {
        if (androidHost is not null)
        {
            try { await androidHost.StopAsync(); }
            catch (Exception error) { App.RecordFailure(error); }
            finally { androidHost = null; }
        }
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

    private static void GrantWindows10RuntimeReadAccess(string runtimeDirectory)
    {
        // Microsoft requires these read-only package permissions for unpackaged Fixed Runtime on Windows 10.
        // Windows 11 does not require this adjustment. Only the app's bundled runtime directory is affected.
        if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000)) return;
        var directory = new DirectoryInfo(runtimeDirectory);
        var permissions = directory.GetAccessControl();
        var inheritance = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
        var changed = false;
        foreach (var sid in new[] { "S-1-15-2-1", "S-1-15-2-2" })
        {
            var identity = new SecurityIdentifier(sid);
            var exists = permissions.GetAccessRules(true, true, typeof(SecurityIdentifier)).OfType<FileSystemAccessRule>()
                .Any(rule => rule.IdentityReference.Equals(identity) && rule.AccessControlType == AccessControlType.Allow
                    && (rule.FileSystemRights & FileSystemRights.ReadAndExecute) == FileSystemRights.ReadAndExecute
                    && (rule.InheritanceFlags & inheritance) == inheritance);
            if (exists) continue;
            permissions.AddAccessRule(new FileSystemAccessRule(identity, FileSystemRights.ReadAndExecute,
                inheritance, PropagationFlags.None, AccessControlType.Allow));
            changed = true;
        }
        if (changed) directory.SetAccessControl(permissions);
    }

    private sealed record DesktopSettings(string Workspace);
}
