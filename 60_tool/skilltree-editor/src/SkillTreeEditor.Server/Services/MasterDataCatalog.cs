using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

public sealed partial class MasterDataCatalog(MasterDataPaths paths)
{
    private static readonly Dictionary<string, string> Labels = new(StringComparer.Ordinal)
    {
        ["schemaVersion"] = "スキーマバージョン", ["id"] = "ID", ["name"] = "表示名",
        ["category"] = "カテゴリ", ["icon"] = "アイコン素材", ["iconTexture"] = "頭アイコンのテクスチャ",
        ["rarity"] = "レアリティ", ["saleValue"] = "売却額", ["lore"] = "説明文",
        ["unTradeable"] = "取引不可", ["unSellable"] = "売却不可", ["requiredLevel"] = "必要レベル",
        ["requiredClasses"] = "必要クラス", ["level"] = "レベル", ["status"] = "ステータスID",
        ["value"] = "値", ["type"] = "種別", ["tag"] = "タグID", ["tags"] = "タグ",
        ["slot"] = "装備スロット", ["handType"] = "手数", ["stats"] = "ステータス補正",
        ["durability"] = "耐久", ["max"] = "最大値", ["min"] = "最小値", ["consume"] = "消費量",
        ["order"] = "表示順", ["shortName"] = "短縮名", ["description"] = "説明",
        ["maxLevel"] = "最大レベル", ["expRate"] = "必要経験値の倍率指標", ["baseStats"] = "初期ステータス補正",
        ["growthPerLevel"] = "レベルごとの成長量", ["classId"] = "クラスID", ["itemId"] = "アイテムID",
        ["skillId"] = "スキルID", ["amount"] = "数量", ["count"] = "個数", ["chance"] = "確率",
        ["equipment"] = "装備設定", ["consumable"] = "消耗品設定", ["appearance"] = "外見設定",
        ["params"] = "固有パラメータ", ["effects"] = "効果", ["conditions"] = "条件"
    };
    private static readonly Dictionary<string, string> CategoryLabels = new(StringComparer.Ordinal)
    {
        ["item"] = "アイテム共通", ["material"] = "素材", ["equipment"] = "装備", ["consumable"] = "消耗品",
        ["orb"] = "オーブ", ["bundle"] = "バンドル", ["rune"] = "ルーン", ["sigil"] = "シジル",
        ["currency"] = "通貨", ["set_effect"] = "セット効果", ["class"] = "クラス", ["skill"] = "スキル",
        ["skilltree"] = "スキルツリー", ["mail"] = "メール", ["guide"] = "ガイド", ["enchant"] = "エンチャント",
        ["mob"] = "モブ", ["enemy"] = "敵", ["boss"] = "ボス", ["npc"] = "NPC", ["spawner"] = "スポナー",
        ["gathering"] = "採集", ["shop"] = "ショップ", ["quest"] = "クエスト", ["quest_board"] = "クエスト掲示板",
        ["world"] = "ワールド", ["dungeon"] = "ダンジョン", ["buff"] = "バフ", ["status"] = "ステータス",
        ["tag"] = "タグ", ["loot"] = "ルート", ["pool"] = "ルートプール", ["table"] = "ルートテーブル", ["recipe"] = "レシピ"
    };

    public async Task<MasterDataCatalogResult> ReadAsync(string backups, CancellationToken token)
    {
        var entries = paths.Enumerate().ToArray();
        var directories = entries.Where(path => MasterDataPaths.IsData(path) || path.EndsWith("YAMLスキーマ定義.md", StringComparison.Ordinal))
            .Select(path => Path.GetDirectoryName(path)!).Distinct(StringComparer.OrdinalIgnoreCase).Order(StringComparer.Ordinal).ToArray();
        var categories = new List<MasterDataCategory>();
        foreach (var directory in directories)
        {
            token.ThrowIfCancellationRequested();
            var category = await ReadCategoryAsync(paths.Relative(directory), entries, token);
            categories.Add(category);
        }
        return new(paths.Root, backups, categories);
    }

    public async Task<MasterDataCategory> GetAsync(string category, CancellationToken token)
        => await ReadCategoryAsync(category, paths.Enumerate().ToArray(), token);

    private async Task<MasterDataCategory> ReadCategoryAsync(string category, string[] entries, CancellationToken token)
    {
        var directory = category == "." ? paths.Root : paths.Resolve(category, true);
        var ownFiles = entries.Where(path => string.Equals(Path.GetDirectoryName(path), directory, StringComparison.OrdinalIgnoreCase) && MasterDataPaths.IsData(path)).ToArray();
        var documentPaths = new List<string>();
        var ancestor = directory;
        while (ancestor.StartsWith(paths.Root, StringComparison.OrdinalIgnoreCase))
        {
            var localDocuments = entries.Where(path => string.Equals(Path.GetDirectoryName(path), ancestor, StringComparison.OrdinalIgnoreCase)
                && path.EndsWith("YAMLスキーマ定義.md", StringComparison.Ordinal)).ToArray();
            documentPaths.InsertRange(0, localDocuments);
            // A separately defined resource (e.g. set_effect) is not an item subtype.
            if (ancestor == directory && localDocuments.Any(document => Regex.IsMatch(File.ReadAllText(document), @"(?m)^\|\s*`schemaVersion`\s*\|"))) break;
            if (ancestor == paths.Root) break;
            ancestor = Path.GetDirectoryName(ancestor)!;
        }
        var documents = new List<MasterDataSource>();
        var fields = new Dictionary<string, MasterDataField>(StringComparer.Ordinal);
        foreach (var document in documentPaths)
        {
            var raw = await File.ReadAllTextAsync(document, Encoding.UTF8, token);
            documents.Add(new(paths.Relative(document), raw.Split('\n').FirstOrDefault(line => line.StartsWith("# ", StringComparison.Ordinal))?.TrimStart('#', ' ', '\r') ?? Path.GetFileName(document)));
            foreach (var field in ParseFields(raw, paths.Relative(document), category)) fields[field.Path] = field;
        }
        var schemas = new List<MasterDataSchema>();
        var schemaDirectories = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { directory, Path.Combine(directory, "schemas") };
        if (category.StartsWith("35.features.skilltree/", StringComparison.OrdinalIgnoreCase))
            schemaDirectories.Add(Path.Combine(paths.Root, "35.features.skilltree", "schemas"));
        var schemaFiles = entries.Where(path => path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)
            && schemaDirectories.Contains(Path.GetDirectoryName(path)!));
        foreach (var path in schemaFiles)
        {
            try
            {
                var schema = JsonNode.Parse(await File.ReadAllTextAsync(path, Encoding.UTF8, token));
                if (schema is not null) schemas.Add(new(paths.Relative(path), schema["title"]?.ToString() ?? Path.GetFileName(path), schema));
            }
            catch (System.Text.Json.JsonException) { /* malformed schema remains available in the file list */ }
        }
        var templates = new List<MasterDataTemplate>();
        foreach (var path in ownFiles.Where(path => !path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)).Take(3))
            templates.Add(new(paths.Relative(path), await File.ReadAllTextAsync(path, Encoding.UTF8, token)));
        var parts = category.Split('/');
        var label = string.Join(" / ", parts.Select(part =>
        {
            var key = part.Split('.').Last();
            return CategoryLabels.GetValueOrDefault(key) ?? part;
        }));
        return new(category, category == "." ? "Filebase 設定" : label, category, ownFiles.Length,
            documents, fields.Values.ToArray(), schemas, templates, ItemCode(category));
    }

    public static string? ItemCode(string category)
    {
        var parts = category.ToLowerInvariant().Split('/');
        return parts.Length == 2 && parts[0] == "10.features.item" && Regex.IsMatch(parts[1], "^(10|20|30|40|50|60|70|99)\\.[a-z_]+$")
            ? parts[1][..2] : null;
    }

    public static IEnumerable<MasterDataField> ParseFields(string raw, string source, string category)
    {
        string[]? header = null;
        string scope = "";
        var arrayPaths = new HashSet<string>(StringComparer.Ordinal);
        var recordTypes = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var snippetTypes = ReadSnippetTypes(raw);
        foreach (var line in raw.Split('\n'))
        {
            if (line.StartsWith('#'))
            {
                var heading = line.TrimStart('#', ' ').Trim();
                var quoted = Regex.Match(heading, @"`([a-zA-Z][a-zA-Z0-9_.\[\]]*)`");
                var first = Regex.Match(heading, @"^([a-zA-Z][a-zA-Z0-9_.\[\]]*)").Value;
                scope = quoted.Success ? quoted.Groups[1].Value : first;
                if (recordTypes.TryGetValue(scope, out var recordPath)) scope = recordPath;
                if (arrayPaths.Contains(scope) && !scope.EndsWith("[]", StringComparison.Ordinal)) scope += "[]";
                if (heading.Contains("params", StringComparison.Ordinal) && !scope.StartsWith("params", StringComparison.Ordinal)) scope = "params";
                if (scope.Length > 0 && !quoted.Success && !arrayPaths.Contains(scope.Replace("[]", "", StringComparison.Ordinal))
                    && !recordTypes.ContainsKey(first) && !scope.Contains('.') && !scope.EndsWith("[]", StringComparison.Ordinal)
                    && scope is not "params" and not "rewards" and not "challenge") scope = "";
                if (category == "47.features.quest" && heading == "item") scope = "@quest-items";
                if (category.StartsWith("40.features.mob/npc", StringComparison.Ordinal) && heading == "Action") scope = "npc.interaction.actions[]";
                header = null;
                continue;
            }
            if (!line.TrimStart().StartsWith('|')) { header = null; continue; }
            var cells = Regex.Split(line.Trim().Trim('|'), @"(?<!\\)\|").Select(cell => cell.Trim()).ToArray();
            if (cells.Any(cell => cell is "キー" or "項目" or "Key" or "Field"))
            {
                header = cells.Select(cell => cell switch
                {
                    "項目" or "Key" or "Field" => "キー", "Type" => "型", "Required" => "必須",
                    "Description" or "制約・説明" or "既定値・制約" => "説明", "既定値" or "Default" => "デフォルト", _ => cell
                }).ToArray();
                continue;
            }
            if (header is null || cells.Length != header.Length || cells.All(cell => Regex.IsMatch(cell, "^[: -]+$"))) continue;
            string Cell(string title) { var index = Array.IndexOf(header, title); return index < 0 ? "" : cells[index]; }
            var key = Cell("キー").Replace("`", "", StringComparison.Ordinal).Trim();
            if (key.Length == 0 || key.Contains(' ')) continue;
            var type = Cell("型").Replace("\\", "", StringComparison.Ordinal);
            var isArray = key.EndsWith("[]", StringComparison.Ordinal) || type.EndsWith("[]", StringComparison.Ordinal)
                || type.StartsWith("List", StringComparison.OrdinalIgnoreCase) || type == "array";
            if (isArray) arrayPaths.Add(key.Replace("[]", "", StringComparison.Ordinal));
            var recordType = Regex.Match(type, @"^List<([A-Z][A-Za-z]+)>$");
            if (recordType.Success) recordTypes[recordType.Groups[1].Value] = key.TrimEnd('[', ']') + "[]";
            var scopes = scope == "@quest-items" ? new[] { "acceptRequirements.items[]", "rewards.items[]" } : new[] { scope };
            foreach (var prefix in scopes)
            {
            var scopedKey = prefix.Length > 0 && !key.StartsWith(prefix, StringComparison.Ordinal) ? prefix + "." + key : key;
            if (type.Length == 0) type = snippetTypes.GetValueOrDefault(scopedKey.TrimEnd('[', ']')) ?? snippetTypes.GetValueOrDefault(scopedKey) ?? "Any";
            var segments = scopedKey.Replace("[]", ".*", StringComparison.Ordinal).Split('.', StringSplitOptions.RemoveEmptyEntries).ToList();
            // Item subtype sections are maps in actual YAML; historical tables use equipment[].
            if (category.StartsWith("10.features.item/", StringComparison.Ordinal) && segments.Count > 1
                && segments[1] == "*" && segments[0] is "equipment" or "consumable" or "bundle" or "rune" or "orb" or "sigil" or "currency")
                segments.RemoveAt(1);
            segments = segments.Select(segment => segment.StartsWith('<') || segment.StartsWith('{') ? "*" : segment).ToList();
            if (segments.Count > 1 && segments[^1] == "*" && (isArray || type == "Any"))
                segments.RemoveAt(segments.Count - 1);
            var pointer = "/" + string.Join('/', segments.Select(segment => segment.Replace("~", "~0", StringComparison.Ordinal).Replace("/", "~1", StringComparison.Ordinal)));
            var leaf = segments.LastOrDefault(segment => segment != "*") ?? key;
            var description = Cell("説明").Replace("\\|", "|", StringComparison.Ordinal);
            var label = Labels.GetValueOrDefault(leaf) ?? leaf;
            var reference = leaf switch { "itemId" => "item", "classId" or "class" => "class", "skillId" => "skill", "status" => "status", "tag" or "tags" => "tag", _ => null };
            yield return new(pointer, scopedKey, label, type, Cell("必須") is "○" or "〇" or "必須" or "yes" or "true", description,
                Cell("デフォルト"), source, null, reference);
            }
        }
    }

    private static Dictionary<string, string> ReadSnippetTypes(string raw)
    {
        var result = new Dictionary<string, string>(StringComparer.Ordinal);
        var fence = Regex.Match(raw, @"(?s)```yaml\s*\r?\n(.*?)```");
        if (!fence.Success) return result;
        var parsed = new MasterDataCodec().Parse("snippet.yml", fence.Groups[1].Value);
        void Walk(JsonNode? node, string path)
        {
            if (path.Length > 0)
                result[path] = node switch
                {
                    JsonObject => "Object", JsonArray => "List", null => "Any",
                    _ => node.GetValueKind() switch
                    {
                        System.Text.Json.JsonValueKind.True or System.Text.Json.JsonValueKind.False => "Boolean",
                        System.Text.Json.JsonValueKind.Number => node.ToJsonString().Contains('.') ? "Double" : "Integer",
                        _ => node.ToString().TrimEnd('?').ToLowerInvariant() switch
                        { "boolean" => "Boolean", "integer" => "Integer", "number" => "Double", _ => "String" }
                    }
                };
            if (node is JsonObject obj)
                foreach (var pair in obj) Walk(pair.Value, path.Length == 0 ? pair.Key : path + "." + pair.Key);
            else if (node is JsonArray array && array.Count > 0)
                Walk(array[0], path + "[]");
        }
        if (parsed.Content is not null) Walk(parsed.Content, "");
        return result;
    }
}
