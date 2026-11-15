using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace SkillTreeEditor.Desktop;

internal static class NativeWindowFrame
{
    private const int DwmUseImmersiveDarkMode = 20;
    private const int DwmWindowCornerPreference = 33;
    private const int DwmBorderColor = 34;
    private const int DwmCaptionColor = 35;
    private const int DwmTextColor = 36;
    private const int RoundCornersWhenAppropriate = 2;
    private const int SystemColor = -1;

    internal static void Apply(Window window)
    {
        var interop = new WindowInteropHelper(window);
        window.SourceInitialized += (_, _) =>
        {
            SetAttribute(interop.Handle, DwmUseImmersiveDarkMode, SystemParameters.HighContrast ? 0 : 1);
            if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000)) return;

            SetAttribute(interop.Handle, DwmWindowCornerPreference, RoundCornersWhenAppropriate);
            ApplyColors(window.IsActive);
            window.Activated += (_, _) => ApplyColors(true);
            window.Deactivated += (_, _) => ApplyColors(false);
        };

        void ApplyColors(bool active)
        {
            var highContrast = SystemParameters.HighContrast;
            // DWM color values use COLORREF's 0x00BBGGRR byte order.
            SetAttribute(interop.Handle, DwmBorderColor, highContrast ? SystemColor : active ? 0x007d7976 : 0x005e5a57);
            SetAttribute(interop.Handle, DwmCaptionColor, highContrast ? SystemColor : active ? 0x003b3937 : 0x00343230);
            SetAttribute(interop.Handle, DwmTextColor, highContrast ? SystemColor : active ? 0x00e8e6e4 : 0x00bebbb8);
        }
    }

    private static void SetAttribute(IntPtr handle, int attribute, int value) =>
        _ = DwmSetWindowAttribute(handle, attribute, ref value, sizeof(int));

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr handle, int attribute, ref int value, int size);
}
