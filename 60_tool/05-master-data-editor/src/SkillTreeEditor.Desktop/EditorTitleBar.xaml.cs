using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace SkillTreeEditor.Desktop;

public partial class EditorTitleBar : UserControl
{
    public event Action? WorkspaceRequested;
    public event Action<string>? CommandRequested;
    public bool NavigationVisible { get; set; } = true;

    public EditorTitleBar()
    {
        InitializeComponent();
        Loaded += (_, _) =>
        {
            if (Window.GetWindow(this) is not { } window) return;
            window.StateChanged += (_, _) => MaximizeButton.Content = window.WindowState == WindowState.Maximized ? "\uE923" : "\uE922";
        };
    }

    public void SetWorkspace(string? path)
    {
        WorkspaceText.Text = path ?? "ワークスペース未選択";
        WorkspaceText.ToolTip = path;
    }

    public void SetCaption(string caption) => CaptionText.Text = caption;

    private void Views_Click(object sender, RoutedEventArgs e)
    {
        var menu = new ContextMenu
        {
            Background = new SolidColorBrush(Color.FromRgb(40, 44, 52)),
            Foreground = new SolidColorBrush(Color.FromRgb(205, 211, 222)),
            PlacementTarget = (Button)sender
        };
        Add("編集ナビゲーション", "toggle-navigation", NavigationVisible);
        menu.Items.Add(new Separator());
        Add("マスターデータ", "show-master");
        Add("マスタフォルダ", "show-master-folders");
        Add("マスタファイル", "show-master-files");
        Add("検証結果", "show-master-validation");
        Add("装備・入手経路", "show-design");
        Add("スキルツリー", "show-skilltree");
        Add("比較・成長グラフ", "show-analytics");
        Add("ワールド配置・編集", "show-spawner");
        menu.Items.Add(new Separator());
        Add("03 マスタ反映", "show-03");
        Add("01 Dev更新", "show-01");
        Add("12 ネットワークビルド", "show-12");
        Add("16 メンテナンス配布", "show-16");
        Add("実行履歴", "show-history");
        menu.Items.Add(new Separator());
        Add("初期レイアウトに戻す", "reset-layout");
        menu.IsOpen = true;
        void Add(string title, string command, bool? isChecked = null)
        {
            var item = new MenuItem { Header = title, IsCheckable = isChecked.HasValue, IsChecked = isChecked ?? false };
            item.Click += (_, _) => CommandRequested?.Invoke(command);
            menu.Items.Add(item);
        }
    }

    private void Workspace_Click(object sender, RoutedEventArgs e) => WorkspaceRequested?.Invoke();
    private void Minimize_Click(object sender, RoutedEventArgs e) => SystemCommands.MinimizeWindow(Window.GetWindow(this));
    private void Maximize_Click(object sender, RoutedEventArgs e)
    {
        var window = Window.GetWindow(this);
        if (window.WindowState == WindowState.Maximized) SystemCommands.RestoreWindow(window);
        else SystemCommands.MaximizeWindow(window);
    }
    private void Close_Click(object sender, RoutedEventArgs e) => SystemCommands.CloseWindow(Window.GetWindow(this));
}
