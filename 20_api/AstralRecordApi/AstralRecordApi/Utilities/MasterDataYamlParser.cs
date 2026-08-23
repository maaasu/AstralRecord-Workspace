using System.Globalization;
using System.Text.Json.Nodes;
using YamlDotNet.Core;
using YamlDotNet.RepresentationModel;

namespace AstralRecordApi.Utilities;

/// <summary>filebase YAML を API 配信用 JSON ノードへ変換する共通パーサー。</summary>
internal static class MasterDataYamlParser
{
    public static JsonObject ParseObject(string rawText, string relativePath)
    {
        var yamlStream = new YamlStream();

        try
        {
            yamlStream.Load(new StringReader(rawText.TrimStart('\uFEFF')));
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException($"YAML の解析に失敗しました: {relativePath} ({ex.Message})", ex);
        }

        if (yamlStream.Documents.Count == 0)
            throw new InvalidOperationException($"YAML が空です: {relativePath}");

        if (ConvertYamlNode(yamlStream.Documents[0].RootNode) is not JsonObject root)
            throw new InvalidOperationException($"YAML のルートがマッピングではありません: {relativePath}");

        return root;
    }

    private static JsonNode? ConvertYamlNode(YamlNode node)
    {
        switch (node)
        {
            case YamlMappingNode mapping:
                var jsonObject = new JsonObject();
                foreach (var pair in mapping.Children)
                {
                    var keyName = ((YamlScalarNode)pair.Key).Value ?? string.Empty;
                    jsonObject[keyName] = ConvertYamlNode(pair.Value);
                }
                return jsonObject;

            case YamlSequenceNode sequence:
                var jsonArray = new JsonArray();
                foreach (var item in sequence.Children)
                    jsonArray.Add(ConvertYamlNode(item));
                return jsonArray;

            case YamlScalarNode scalar:
                return ConvertYamlScalar(scalar);

            default:
                return null;
        }
    }

    private static JsonNode? ConvertYamlScalar(YamlScalarNode scalar)
    {
        var value = scalar.Value ?? string.Empty;

        if (scalar.Style is ScalarStyle.SingleQuoted or ScalarStyle.DoubleQuoted)
            return JsonValue.Create(value);

        // filebase では先頭の & を Minecraft のカラーコードとして使う。
        // YamlDotNet は未引用の &afoo を「値のないアンカー」として解釈するため、
        // 構文全体を再構成せず、値のないアンカーだけを元のリテラルへ戻す。
        if (!scalar.Anchor.IsEmpty && value.Length == 0)
            return JsonValue.Create($"&{scalar.Anchor.Value}");

        if (value.Length == 0 || value is "~" or "null" or "Null" or "NULL")
            return null;

        if (value is "true" or "True" or "TRUE")
            return JsonValue.Create(true);

        if (value is "false" or "False" or "FALSE")
            return JsonValue.Create(false);

        if (long.TryParse(value, NumberStyles.Integer, CultureInfo.InvariantCulture, out var longValue))
            return JsonValue.Create(longValue);

        if (double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var doubleValue))
            return JsonValue.Create(doubleValue);

        return JsonValue.Create(value);
    }
}
