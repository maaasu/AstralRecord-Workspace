namespace AstralRecordApi.Utilities;

public static class YamlRangeNormalizer
{
    /// <summary>
    /// YAML の random スカラー形式をキーの値に昇格させる正規化を行う。
    /// 例:
    ///   maxSlots:        maxSlots: 1~3
    ///     random: 1~3
    /// </summary>
    public static string[] NormalizeRandomRangeObjects(string[] lines)
    {
        var result = new List<string>(lines.Length);
        for (int i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            var trimmed = line.TrimStart();

            if (trimmed.EndsWith(':') && !trimmed.StartsWith('-') && !trimmed.StartsWith('#'))
            {
                if (i + 1 < lines.Length)
                {
                    var next1 = lines[i + 1].TrimStart();
                    if (next1.StartsWith("random: ", StringComparison.OrdinalIgnoreCase))
                    {
                        var rangeVal = next1["random: ".Length..].Trim();
                        var indent = line[..(line.Length - trimmed.Length)];
                        var key = trimmed.TrimEnd(':');
                        result.Add($"{indent}{key}: {rangeVal}");
                        i++;
                        continue;
                    }
                }
            }

            result.Add(line);
        }
        return result.ToArray();
    }

    /// <summary>
    /// YAML の min/max オブジェクト形式を範囲スカラーに正規化する。
    /// 例:
    ///   amount:          amount: 1~5
    ///     min: 1
    ///     max: 5
    /// </summary>
    public static string[] NormalizeMinMaxObjects(string[] lines)
    {
        var result = new List<string>(lines.Length);
        for (int i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            var trimmed = line.TrimStart();

            if (trimmed.EndsWith(':') && !trimmed.StartsWith('-') && !trimmed.StartsWith('#'))
            {
                if (i + 2 < lines.Length)
                {
                    var next1 = lines[i + 1].TrimStart();
                    var next2 = lines[i + 2].TrimStart();

                    if (next1.StartsWith("min: ", StringComparison.OrdinalIgnoreCase) &&
                        next2.StartsWith("max: ", StringComparison.OrdinalIgnoreCase))
                    {
                        var minVal = next1["min: ".Length..].Trim();
                        var maxVal = next2["max: ".Length..].Trim();

                        // min / max 自体が範囲値を持つ場合はオブジェクト構造を維持する。
                        if (!minVal.Contains('~') && !maxVal.Contains('~'))
                        {
                            var indent = line[..(line.Length - trimmed.Length)];
                            var key = trimmed.TrimEnd(':');
                            result.Add($"{indent}{key}: {minVal}~{maxVal}");
                            i += 2;
                            continue;
                        }
                    }
                }
            }

            result.Add(line);
        }
        return result.ToArray();
    }
}