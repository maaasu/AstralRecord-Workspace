using System.Globalization;

namespace AstralRecordApi.Utilities;

/// <summary>
/// "min~max" 形式の範囲文字列を解決するユーティリティ。
/// </summary>
public static class RangeValueResolver
{
    /// <summary>
    /// "min~max" 形式の文字列をランダムに解決して整数値を返す。
    /// 固定値の場合はそのまま返す。
    /// </summary>
    public static int ResolveInt(string value)
    {
        var trimmed = value.Trim();

        if (TryParseIntRange(trimmed, out var min, out var max))
            return Random.Shared.Next(min, max + 1);

        return int.Parse(trimmed, CultureInfo.InvariantCulture);
    }

    /// <summary>
    /// 固定値または範囲文字列を decimal として解決する。
    /// </summary>
    public static decimal ResolveDecimal(string value)
    {
        var trimmed = value.Trim();

        if (TryParseIntRange(trimmed, out var intMin, out var intMax))
            return Random.Shared.Next(intMin, intMax + 1);

        if (TryParseDecimalRange(trimmed, out var min, out var max))
        {
            var sample = (decimal)Random.Shared.NextDouble();
            return decimal.Round(min + ((max - min) * sample), 4, MidpointRounding.AwayFromZero);
        }

        return decimal.Parse(trimmed, CultureInfo.InvariantCulture);
    }

    /// <summary>
    /// 固定値または範囲文字列を文字列数値として解決する。
    /// </summary>
    public static string ResolveNumericString(string value)
    {
        var trimmed = value.Trim();
        if (!trimmed.Contains('~'))
            return trimmed;

        if (TryParseIntRange(trimmed, out var intMin, out var intMax))
            return Random.Shared.Next(intMin, intMax + 1).ToString(CultureInfo.InvariantCulture);

        return ResolveDecimal(trimmed).ToString("0.####", CultureInfo.InvariantCulture);
    }

    /// <summary>
    /// "min~max" 形式かどうかを判定し、min / max を返す。
    /// </summary>
    public static bool TryParseIntRange(string value, out int min, out int max)
    {
        var trimmed = value.Trim();
        var tildeIndex = trimmed.IndexOf('~');
        if (tildeIndex >= 0
            && int.TryParse(trimmed[..tildeIndex].Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out min)
            && int.TryParse(trimmed[(tildeIndex + 1)..].Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out max))
        {
            if (min > max)
                (min, max) = (max, min);

            return true;
        }

        min = max = 0;
        return false;
    }

    public static bool TryParseDecimalRange(string value, out decimal min, out decimal max)
    {
        var trimmed = value.Trim();
        var tildeIndex = trimmed.IndexOf('~');
        if (tildeIndex >= 0
            && decimal.TryParse(trimmed[..tildeIndex].Trim(), NumberStyles.Number, CultureInfo.InvariantCulture, out min)
            && decimal.TryParse(trimmed[(tildeIndex + 1)..].Trim(), NumberStyles.Number, CultureInfo.InvariantCulture, out max))
        {
            if (min > max)
                (min, max) = (max, min);

            return true;
        }

        min = max = 0;
        return false;
    }

    /// <summary>
    /// "min~max" 形式の文字列を (min, max) タプルに分解して返す。
    /// 範囲でない場合は (value, value) を返す。
    /// </summary>
    public static (string Min, string Max) SplitRange(string value)
    {
        var trimmed = value.Trim();
        var tildeIndex = trimmed.IndexOf('~');
        return tildeIndex >= 0
            ? (trimmed[..tildeIndex].Trim(), trimmed[(tildeIndex + 1)..].Trim())
            : (trimmed, trimmed);
    }
}
