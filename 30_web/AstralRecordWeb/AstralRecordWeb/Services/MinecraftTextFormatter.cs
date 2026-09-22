using System.Net;
using System.Text;

namespace AstralRecordWeb.Services;

/// <summary>Minecraft の legacy 色コードを、安全な固定 CSS クラスへ変換します。</summary>
public static class MinecraftTextFormatter
{
    public static string ToHtml(string? text)
    {
        if (string.IsNullOrEmpty(text))
            return string.Empty;

        var builder = new StringBuilder();
        var currentClass = "mc-white";
        var openSpan = false;

        void OpenSpan()
        {
            if (openSpan)
                return;

            builder.Append("<span class=\"");
            builder.Append(currentClass);
            builder.Append("\">");
            openSpan = true;
        }

        for (var index = 0; index < text.Length; index++)
        {
            var current = text[index];
            if ((current is '&' or '§') && index + 1 < text.Length)
            {
                var cssClass = MinecraftColorClass(char.ToLowerInvariant(text[index + 1]));
                if (cssClass is not null)
                {
                    if (openSpan)
                    {
                        builder.Append("</span>");
                        openSpan = false;
                    }

                    currentClass = cssClass;
                    index++;
                    continue;
                }
            }

            OpenSpan();
            builder.Append(WebUtility.HtmlEncode(current.ToString()));
        }

        if (openSpan)
            builder.Append("</span>");

        return builder.ToString();
    }

    public static string StripCodes(string text)
    {
        var builder = new StringBuilder(text.Length);
        for (var index = 0; index < text.Length; index++)
        {
            if ((text[index] is '&' or '§') && index + 1 < text.Length)
            {
                index++;
                continue;
            }

            builder.Append(text[index]);
        }

        return builder.ToString();
    }

    private static string? MinecraftColorClass(char code)
        => code switch
        {
            '0' => "mc-black",
            '1' => "mc-dark-blue",
            '2' => "mc-dark-green",
            '3' => "mc-dark-aqua",
            '4' => "mc-dark-red",
            '5' => "mc-dark-purple",
            '6' => "mc-gold",
            '7' => "mc-gray",
            '8' => "mc-dark-gray",
            '9' => "mc-blue",
            'a' => "mc-green",
            'b' => "mc-aqua",
            'c' => "mc-red",
            'd' => "mc-light-purple",
            'e' => "mc-yellow",
            'f' or 'r' => "mc-white",
            _ => null,
        };
}
