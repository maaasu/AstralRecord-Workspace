using System.Globalization;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using YamlDotNet.Core;
using YamlDotNet.Core.Events;
using YamlDotNet.RepresentationModel;

namespace SkillTreeEditor.Server.Services;

public sealed class MasterDataCodec
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true, Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    private static readonly JsonSerializerOptions ScalarOptions = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };
    public MasterDataParseResult Parse(string path, string raw)
    {
        try
        {
            if (MasterDataPaths.Format(path) == "json")
            {
                using var document = JsonDocument.Parse(raw);
                RejectDuplicateJson(document.RootElement);
                return new(JsonNode.Parse(raw), []);
            }
            var stream = LoadYaml(raw);
            return new(FromYaml(stream.Documents[0].RootNode, new HashSet<YamlNode>(ReferenceEqualityComparer.Instance)), []);
        }
        catch (Exception exception) when (exception is YamlException or JsonException or InvalidDataException or FormatException)
        {
            return new(null, [new("error", "PARSE_ERROR", "", exception.Message)]);
        }
    }

    public MasterDataRenderResult Render(MasterDataRenderRequest request)
    {
        if (request.OriginalRaw is { } original)
        {
            var parsed = Parse(request.Path, original);
            if (parsed.Issues.Count > 0)
                throw new ArgumentException("元の文書を解析できないためフォームを反映できません。");
            if (JsonNode.DeepEquals(parsed.Content, request.Content)) return new(original, true, []);
            if (MasterDataPaths.Format(request.Path) == "yaml")
            {
                var edits = new List<(int Start, int End, string Value)>();
                if (CollectScalarEdits(LoadYaml(original).Documents[0].RootNode, parsed.Content, request.Content, edits))
                {
                    var raw = original;
                    foreach (var edit in edits.OrderByDescending(edit => edit.Start))
                        raw = raw[..edit.Start] + edit.Value + raw[edit.End..];
                    if (JsonNode.DeepEquals(Parse(request.Path, raw).Content, request.Content))
                        return new(raw, true, []);
                }
                // Structural changes cannot safely reuse source offsets. Keep every YAML comment,
                // explicitly report relocation, and let raw mode retain their original positions.
                var comments = ReadComments(original);
                var serialized = SerializeYaml(request.Content);
                var newline = original.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
                var header = string.Concat(comments.Select(comment => "# " + comment + "\n"));
                var rendered = (header + serialized).Replace("\n", newline, StringComparison.Ordinal);
                return new(rendered, comments.Count == 0,
                    comments.Count == 0 ? [] : ["構造変更によりコメントを文書の先頭へ移しました。保存前に YAML を確認してください。"]);
            }
        }
        var result = MasterDataPaths.Format(request.Path) == "json"
            ? (request.Content?.ToJsonString(JsonOptions) ?? "null") + "\n"
            : SerializeYaml(request.Content);
        return new(result, true, []);
    }

    private static YamlStream LoadYaml(string raw)
    {
        var stream = new YamlStream();
        stream.Load(new StringReader(raw));
        if (stream.Documents.Count != 1)
            throw new InvalidDataException("1ファイルに YAML 文書を1つだけ記載してください。");
        return stream;
    }

    private static JsonNode? FromYaml(YamlNode node, HashSet<YamlNode> ancestors)
    {
        if (!ancestors.Add(node)) throw new InvalidDataException("循環する YAML alias は扱えません。");
        try
        {
            return node switch
            {
                YamlMappingNode mapping => new JsonObject(mapping.Children.Select(pair =>
                    KeyValuePair.Create((pair.Key as YamlScalarNode)?.Value
                        ?? throw new InvalidDataException("YAML のキーは文字列で指定してください。"), FromYaml(pair.Value, ancestors)))),
                YamlSequenceNode sequence => new JsonArray(sequence.Children.Select(child => FromYaml(child, ancestors)).ToArray()),
                YamlScalarNode scalar => Scalar(scalar),
                _ => throw new InvalidDataException("未対応の YAML ノードです。")
            };
        }
        finally { ancestors.Remove(node); }
    }

    private static JsonNode? Scalar(YamlScalarNode scalar)
    {
        var value = scalar.Value ?? "";
        if (scalar.Style is not ScalarStyle.Plain and not ScalarStyle.Any || scalar.Tag == "tag:yaml.org,2002:str")
            return JsonValue.Create(value);
        if (value is "" or "~" || value.Equals("null", StringComparison.OrdinalIgnoreCase)) return null;
        if (bool.TryParse(value, out var boolean)) return JsonValue.Create(boolean);
        if (long.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var integer))
            return JsonValue.Create(integer);
        if (decimal.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var number))
            return JsonValue.Create(number);
        return JsonValue.Create(value);
    }

    private static bool CollectScalarEdits(YamlNode node, JsonNode? before, JsonNode? after,
        List<(int Start, int End, string Value)> edits)
    {
        if (JsonNode.DeepEquals(before, after)) return true;
        if (!node.Anchor.IsEmpty) return false;
        if (node is YamlMappingNode map && before is JsonObject oldObject && after is JsonObject newObject
            && oldObject.Count == newObject.Count && oldObject.All(pair => newObject.ContainsKey(pair.Key)))
        {
            foreach (var pair in map.Children)
            {
                var key = ((YamlScalarNode)pair.Key).Value!;
                if (!CollectScalarEdits(pair.Value, oldObject[key], newObject[key], edits)) return false;
            }
            return true;
        }
        if (node is YamlSequenceNode sequence && before is JsonArray oldArray && after is JsonArray newArray
            && oldArray.Count == newArray.Count)
        {
            for (var index = 0; index < oldArray.Count; index++)
                if (!CollectScalarEdits(sequence.Children[index], oldArray[index], newArray[index], edits)) return false;
            return true;
        }
        if (node is YamlScalarNode && after is not JsonObject and not JsonArray)
        {
            edits.Add((checked((int)node.Start.Index), checked((int)node.End.Index), after?.ToJsonString(ScalarOptions) ?? "null"));
            return true;
        }
        return false;
    }

    private static string SerializeYaml(JsonNode? node)
    {
        var stream = new YamlStream(new YamlDocument(ToYaml(node)));
        var writer = new StringWriter(CultureInfo.InvariantCulture);
        stream.Save(writer, assignAnchors: false);
        return writer.ToString().Replace("\r\n", "\n", StringComparison.Ordinal).Replace("...\n", "", StringComparison.Ordinal);
    }

    private static YamlNode ToYaml(JsonNode? node) => node switch
    {
        JsonObject value => new YamlMappingNode(value.Select(pair =>
            KeyValuePair.Create<YamlNode, YamlNode>(new YamlScalarNode(pair.Key), ToYaml(pair.Value)))),
        JsonArray value => new YamlSequenceNode(value.Select(ToYaml)),
        JsonValue value when value.GetValueKind() == JsonValueKind.String => new YamlScalarNode(value.GetValue<string>()) { Style = ScalarStyle.DoubleQuoted },
        _ => new YamlScalarNode(node?.ToJsonString() ?? "null") { Style = ScalarStyle.Plain }
    };

    private static List<string> ReadComments(string raw)
    {
        var result = new List<string>();
        var parser = new Parser(new Scanner(new StringReader(raw), skipComments: false));
        while (parser.MoveNext())
            if (parser.Current is Comment comment) result.Add(comment.Value);
        return result;
    }

    private static void RejectDuplicateJson(JsonElement value)
    {
        if (value.ValueKind == JsonValueKind.Object)
        {
            var keys = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in value.EnumerateObject())
            {
                if (!keys.Add(property.Name)) throw new InvalidDataException($"JSON キー '{property.Name}' が重複しています。");
                RejectDuplicateJson(property.Value);
            }
        }
        else if (value.ValueKind == JsonValueKind.Array)
            foreach (var child in value.EnumerateArray()) RejectDuplicateJson(child);
    }
}
