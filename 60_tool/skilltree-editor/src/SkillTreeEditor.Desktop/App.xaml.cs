using System.IO;
using System.Windows;

namespace SkillTreeEditor.Desktop;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        var window = new MainWindow(e.Args);
        MainWindow = window;
        window.Show();
    }

    public static string StateDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AstralRecord", "MasterEditor");

    internal static void RecordFailure(Exception error)
    {
        try
        {
            Directory.CreateDirectory(StateDirectory);
            File.AppendAllText(Path.Combine(StateDirectory, "startup.log"), $"{DateTimeOffset.Now:O} {error}\n");
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
