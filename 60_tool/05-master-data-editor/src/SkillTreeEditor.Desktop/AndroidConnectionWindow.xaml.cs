using System.IO;
using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using QRCoder;

namespace SkillTreeEditor.Desktop;

internal partial class AndroidConnectionWindow : Window
{
    private readonly AndroidConnectionHost host;
    private bool changing;

    internal AndroidConnectionWindow(AndroidConnectionHost host)
    {
        this.host = host;
        InitializeComponent();
        SetupCommand.Text = AndroidPrivateNetwork.SetupCommand;
        OpenApk.Visibility = File.Exists(Path.Combine(host.ApkDirectory, "AstralRecordMasterEditor.apk"))
            ? Visibility.Visible : Visibility.Collapsed;
        UpdateView();
        RefreshDevices();
        Closing += (_, e) => e.Cancel = changing;
    }

    private void UpdateView()
    {
        var origin = host.Origin;
        Toggle.Content = origin is null ? "接続を開始" : "接続を停止";
        Status.Text = origin is null ? "このワークスペースの接続は停止中です。" : "このワークスペースで端末からの接続を受け付けています。";
        PairPanel.Visibility = origin is null ? Visibility.Collapsed : Visibility.Visible;
        CodeText.Text = "";
        if (origin is null) return;
        Address.Text = origin.GetLeftPart(UriPartial.Authority);
        using var stream = new MemoryStream(PngByteQRCodeHelper.GetQRCode(
            "astralrecord-editor://connect?origin=" + Uri.EscapeDataString(Address.Text), QRCodeGenerator.ECCLevel.M, 5));
        var qr = new BitmapImage();
        qr.BeginInit();
        qr.CacheOption = BitmapCacheOption.OnLoad;
        qr.StreamSource = stream;
        qr.EndInit();
        ConnectionQr.Source = qr;
    }

    private async void Toggle_Click(object sender, RoutedEventArgs e)
    {
        if (changing) return;
        changing = true;
        Toggle.IsEnabled = false;
        PairPanel.IsEnabled = false;
        try
        {
            Status.Text = "接続設定を確認しています…";
            if (host.Origin is null) await host.EnableAsync();
            else await host.DisableAsync();
            UpdateView();
        }
        catch (Exception error) { UpdateView(); Status.Text = error.Message; }
        finally { changing = false; Toggle.IsEnabled = true; PairPanel.IsEnabled = true; }
    }

    private void Issue_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var code = host.Devices.CreatePairingCode();
            CodeText.Text = $"{code.Code}　（{code.ExpiresAt.ToLocalTime():HH:mm}まで・1回限り）";
        }
        catch (Exception error) { Status.Text = error.Message; }
    }

    private void OpenApk_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo(host.ApkDirectory) { UseShellExecute = true }); }
        catch (Exception error) { Status.Text = error.Message; }
    }

    private void RefreshDevices()
    {
        try
        {
            var devices = host.Devices.ListDevices().Select(device =>
                new DeviceRow(device.DeviceId, $"{device.Name}　登録 {device.PairedAt.ToLocalTime():yyyy/MM/dd HH:mm}")).ToArray();
            Devices.ItemsSource = devices;
            if (devices.Length == 0)
                Devices.ItemsSource = new[] { new DeviceRow(null, "登録済みの端末はありません") };
        }
        catch (Exception error) { Status.Text = error.Message; }
    }

    private void Refresh_Click(object sender, RoutedEventArgs e) => RefreshDevices();

    private void Devices_SelectionChanged(object sender, SelectionChangedEventArgs e) =>
        Revoke.IsEnabled = Devices.SelectedItem is DeviceRow { Id: not null };

    private void Revoke_Click(object sender, RoutedEventArgs e)
    {
        if (Devices.SelectedItem is not DeviceRow { Id: { } id } device) return;
        if (MessageBox.Show(this, $"{device.Label}\nこの端末の接続を解除しますか？",
            "端末の登録を解除", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No) != MessageBoxResult.Yes) return;
        try { host.Devices.Revoke(id); RefreshDevices(); }
        catch (Exception error) { Status.Text = error.Message; }
    }

    private sealed record DeviceRow(string? Id, string Label);
}
