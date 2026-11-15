using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shell;
using Microsoft.Web.WebView2.Wpf;

namespace SkillTreeEditor.Desktop;

// Shares its WebView environment and React state with the main editor. Closing returns the
// panels to the main window before releasing the native browser; it never closes a document.
internal sealed class DetachedEditorWindow : Window
{
    internal WebView2 Browser { get; } = new() { DefaultBackgroundColor = System.Drawing.Color.FromArgb(40, 44, 52) };
    internal EditorTitleBar TitleBar { get; } = new();
    internal string PopoutId { get; }
    private bool approved;
    private bool returning;
    private bool closed;

    internal DetachedEditorWindow(string id, string? workspace, Func<DetachedEditorWindow, Task> returnPanels)
    {
        PopoutId = id;
        Title = "AstralRecord マスタデータエディタ";
        Icon = new BitmapImage(new Uri("pack://application:,,,/Assets/AppIcon.ico"));
        Background = new SolidColorBrush(Color.FromRgb(40, 44, 52));
        Foreground = new SolidColorBrush(Color.FromRgb(205, 211, 222));
        Width = 900;
        Height = 700;
        MinWidth = 360;
        MinHeight = 280;
        WindowChrome.SetWindowChrome(this, new WindowChrome
        {
            CaptionHeight = 32, ResizeBorderThickness = new Thickness(6), GlassFrameThickness = new Thickness(0),
            CornerRadius = new CornerRadius(0), UseAeroCaptionButtons = false
        });
        TitleBar.SetWorkspace(workspace);
        var grid = new Grid();
        grid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(32) });
        grid.RowDefinitions.Add(new RowDefinition());
        grid.Children.Add(TitleBar);
        Grid.SetRow(Browser, 1);
        grid.Children.Add(Browser);
        Content = grid;
        Closing += OnClosing;
        Closed += (_, _) => { closed = true; Browser.Dispose(); };

        async void OnClosing(object? sender, CancelEventArgs e)
        {
            if (approved) return;
            e.Cancel = true;
            if (returning) return;
            returning = true;
            try
            {
                await returnPanels(this);
                CloseAfterReturn();
            }
            catch (Exception error)
            {
                if (closed) return;
                // Keep the window and draft alive if the owner cannot receive its panels.
                App.RecordFailure(error);
                MessageBox.Show(this, "ビューを元のウィンドウへ戻せませんでした。もう一度閉じる操作をしてください。",
                    "ビューの移動", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            finally { returning = false; }
        }
    }

    internal void CloseAfterReturn()
    {
        if (closed) return;
        approved = true;
        Close();
    }

    internal void CloseFromBrowser()
    {
        // Native Closing may still be waiting for ExecuteScriptAsync to complete. Disposing
        // the WebView from its re-entrant web message would cancel that pending return.
        if (!returning) CloseAfterReturn();
    }
}
