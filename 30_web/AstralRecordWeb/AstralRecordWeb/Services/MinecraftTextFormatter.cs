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

        var builder = new StringBuilder(text.Length);
        string? currentClass = "mc-white";
        var bold = false;
        var segmentOpen = false;

        void CloseSegment()
        {
            if (!segmentOpen)
                return;

            if (bold)
                builder.Append("</strong>");
            if (currentClass is not null)
                builder.Append("</span>");
            segmentOpen = false;
        }

        void OpenSegment()
        {
            if (segmentOpen)
                return;

            if (currentClass is not null)
                builder.Append("<span class=\"").Append(currentClass).Append("\">");
            if (bold)
                builder.Append("<strong>");
            segmentOpen = true;
        }

        for (var index = 0; index < text.Length; index++)
        {
            var current = text[index];
            if ((current is '&' or '§') && index + 1 < text.Length)
            {
                var code = char.ToLowerInvariant(text[index + 1]);
                var nextColorClass = MinecraftColorClass(code);
                if (nextColorClass is not null)
                {
                    CloseSegment();
                    currentClass = nextColorClass;
                    bold = false;
                    index++;
                    continue;
                }

                if (code == 'x' && IsLegacyHexColor(text, index))
                {
                    CloseSegment();
                    currentClass = null;
                    bold = false;
                    index += 13;
                    continue;
                }

                if (code == 'l')
                {
                    CloseSegment();
                    bold = true;
                    index++;
                    continue;
                }

                if (code is 'k' or 'm' or 'n' or 'o')
                {
                    index++;
                    continue;
                }
            }

            OpenSegment();
            builder.Append(WebUtility.HtmlEncode(current.ToString()));
        }

        CloseSegment();
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

    private static bool IsLegacyHexColor(string text, int startIndex)
    {
        const int sequenceLength = 14;
        if (startIndex + sequenceLength > text.Length)
            return false;

        for (var offset = 2; offset < sequenceLength; offset += 2)
        {
            if (text[startIndex + offset] is not ('&' or '§')
                || !Uri.IsHexDigit(text[startIndex + offset + 1]))
                return false;
        }

        return true;
    }
}
