using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>読み取り専用の提案生成。保存・採番は既存の明示的な編集操作が行う。</summary>
public sealed class MasterDataAiService(MasterDataAiSettingsService settings, MasterDataAiProvider provider,
    MasterDataPaths paths, MasterDataService data, MasterDataCatalog catalog, MasterDataCodec codec,
    MasterDataValidation validation)
{
    private readonly SemaphoreSlim _requests = new(1, 1);
    private static readonly string[] IdentityKeys = ["id", "nodeId", "structureId", "schemaVersion", "$schema"];
    private static readonly HashSet<string> ContinuationFields = new(StringComparer.OrdinalIgnoreCase)
        { "name", "displayName", "title", "label", "description", "lore", "summary", "text", "message" };
    private const int MaxContinuationTextLength = 8000;
    private const string ContinuationModel = "gpt-6-luna";
    internal const int MaxContinuationSuffixLength = 120;

    public async Task<MasterDataAiResult> GenerateAsync(MasterDataAiRequest request, CancellationToken token)
    {
        if (!await _requests.WaitAsync(0, token))
            throw new MasterDataAiException(409, "AI の生成が実行中です。完了または中止後に再実行してください。");
        try { return await GenerateCoreAsync(request, token); }
        finally { _requests.Release(); }
    }

    private async Task<MasterDataAiResult> GenerateCoreAsync(MasterDataAiRequest request, CancellationToken token)
    {
        if (request.Mode is not ("create" or "complete" or "batch")) throw new ArgumentException("AI の操作を選択してください。");
        bool continuation = request.CompletionStyle == "continue" && request.Mode == "complete";
        if (request.CompletionStyle is not null && !continuation)
            throw new ArgumentException("入力補完の方式を確認してください。");
        if (string.IsNullOrWhiteSpace(request.Instruction) || request.Instruction.Length > 8000)
            throw new ArgumentException("指示は1〜8000文字で入力してください。");
        if (request.Raw?.Length > 64_000 || request.CategoryId?.Length > 512 || request.SourcePath?.Length > 512
            || request.FieldPointer?.Length > 512 || request.Paths?.Count > 10)
            throw new ArgumentException("AI に渡す対象が上限を超えています。");

        var credentials = settings.Credentials();
        var model = continuation ? ContinuationModel : credentials.Model;
        var originals = new Dictionary<string, MasterDataDocument>(StringComparer.Ordinal);
        MasterDataCategory? category = null;
        var createTargets = new Dictionary<string, MasterDataCategory>(StringComparer.Ordinal);
        MasterDataDocument? draft = null;
        string[] pointer = [];
        bool arrayCompletion = false;
        string? currentText = null;
        bool multilineContinuation = false;
        MasterDataField? continuationField = null;
        if (request.Mode == "batch")
        {
            if (request.Paths is not { Count: > 0 and <= 10 } || request.Raw is not null || request.SourcePath is not null)
                throw new ArgumentException("一括編集は保存済みファイルを1〜10件選択してください。");
            foreach (var path in request.Paths)
            {
                RequireEditablePath(path);
                if (originals.ContainsKey(path)) throw new ArgumentException("同じファイルを複数指定できません。");
                originals.Add(path, await ReadBoundedAsync(path, token));
            }
        }
        else
        {
            if (request.Paths is { Count: > 0 }) throw new ArgumentException("この操作では一括対象を指定できません。");
            if (request.SourcePath is { } source)
            {
                RequireEditablePath(source);
                var saved = await ReadBoundedAsync(source, token);
                var raw = request.Raw ?? saved.Raw;
                var parsed = RequireObject(source, raw);
                RequireIdentity(saved.Content!, parsed);
                draft = saved with { Raw = raw, Content = parsed };
            }
            if (request.Mode == "create")
            {
                if (string.IsNullOrWhiteSpace(request.CategoryId) || request.CategoryId == ".")
                    throw new ArgumentException("作成先のカテゴリを選択してください。");
                var full = paths.Resolve(request.CategoryId, directory: true);
                if (!Directory.Exists(full) || request.CategoryId.Split('/').Any(segment => segment == "schemas"))
                    throw new ArgumentException("作成先のカテゴリが見つかりません。");
                if (request.CategoryId.StartsWith("35.features.skilltree/nodes", StringComparison.Ordinal)
                    || request.CategoryId == "55.features.pet")
                    throw new ArgumentException("このカテゴリの新規作成は専用の編集操作を使用してください。");
                if (draft is not null && !draft.Path.StartsWith(request.CategoryId + "/", StringComparison.Ordinal))
                    throw new ArgumentException("複製元と作成先のカテゴリを合わせてください。");
                category = await catalog.GetAsync(request.CategoryId, token);
                if (category.Id == "10.features.item")
                {
                    // Use catalog-backed item subtypes, never arbitrary descendants or definition folders.
                    var childIds = paths.Enumerate(category.Id)
                        .Where(path => MasterDataPaths.IsData(path) || path.EndsWith("YAMLスキーマ定義.md", StringComparison.Ordinal))
                        .Select(path => paths.Relative(Path.GetDirectoryName(path)!))
                        .Where(id => MasterDataCatalog.ItemCode(id) is not null)
                        .Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal);
                    foreach (var id in childIds)
                    {
                        var child = await catalog.GetAsync(id, token);
                        if (child.ItemCategoryCode is not null) createTargets.Add(child.Id, child);
                    }
                    if (createTargets.Count == 0)
                        throw new ArgumentException("作成できるアイテムカテゴリが見つかりません。カテゴリの定義を確認してください。");
                }
                else createTargets.Add(category.Id, category);
                if (request.Raw is not null && draft is null)
                    draft = new(request.CategoryId + "/proposal.yml", "yaml", request.Raw, "", RequireObject("proposal.yml", request.Raw), []);
            }
            else
            {
                if (draft is null || request.Raw is null || string.IsNullOrWhiteSpace(request.FieldPointer))
                    throw new ArgumentException("入力補完には対象ファイル、現在の原稿、対象フィールドが必要です。");
                category = await catalog.GetAsync(paths.Relative(Path.GetDirectoryName(paths.Resolve(draft.Path))!), token);
                pointer = ParsePointer(request.FieldPointer);
                var metadataPath = "/" + string.Join('/', pointer.Select(part => int.TryParse(part, out _) ? "*" : Escape(part)));
                var field = category.Fields.FirstOrDefault(value => value.Path == metadataPath);
                var referenceField = category.Fields.Any(value =>
                    (value.Reference is not null || Regex.IsMatch(value.Type, @"\bref(?:erence)?\b", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
                    && (metadataPath == value.Path || metadataPath.StartsWith(value.Path + "/", StringComparison.Ordinal)));
                var value = At(draft.Content, pointer);
                var flowNodeReference = GameFlowValidation.IsFlow(draft.Path)
                    && metadataPath is "/edges/*/from" or "/edges/*/to";
                if (pointer.Any(IsIdentifier) || referenceField || flowNodeReference || LooksLikeReference(value)
                    || (field is not null && Regex.IsMatch(field.Label, "ID|識別子|参照", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)))
                    throw new MasterDataAiException(400, "ID、参照、スキーマバージョンは入力補完できません。");
                if (field is not null && Regex.IsMatch(field.Type, @"\A(?:integer|int\d*|long|double|float|decimal|number|boolean|bool)\z", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
                    throw new MasterDataAiException(400, "数値・真偽値のフィールドは入力補完できません。");
                arrayCompletion = value is JsonArray;
                if (!(value is JsonValue scalar && scalar.GetValueKind() == JsonValueKind.String)
                    && !(value is JsonArray array && array.All(child => child?.GetValueKind() == JsonValueKind.String))
                    && !(value is null && field is not null && field.Type is "String" or "string" or "String[]" or "string[]"))
                    throw new MasterDataAiException(400, "入力補完は文字列または文字列配列のフィールドで利用できます。");
                if (value is null) arrayCompletion = field!.Type.EndsWith("[]", StringComparison.Ordinal);
                if (continuation)
                {
                    var key = pointer.LastOrDefault(part => !int.TryParse(part, out _));
                    if (key is null || !ContinuationFields.Contains(key) || field?.Enum is not null
                        || pointer.Any(part => Regex.IsMatch(part,
                            @"\A(?:\$.*|.*ids?|version|.*path|.*url|.*uri|.*uuid|.*key|.*type|.*material|icon|iconTexture|iconModel|tags?|.*refs?|.*references?|from|to|parent|source|target)\z",
                            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)))
                        throw new MasterDataAiException(400, "続きの予測は名前や説明などの自由入力欄で利用できます。");
                    currentText = value switch
                    {
                        JsonValue text => text.GetValue<string>(),
                        JsonArray lines when lines.All(line => line!.GetValue<string>().IndexOfAny(['\r', '\n']) < 0)
                            => string.Join('\n', lines.Select(line => line!.GetValue<string>())),
                        _ => null
                    };
                    if (string.IsNullOrWhiteSpace(currentText) || currentText.Length > MaxContinuationTextLength)
                        throw new MasterDataAiException(400, "続きの予測には後ろに追記できる8000文字以内の入力が必要です。");
                    multilineContinuation = arrayCompletion || currentText.Contains('\n');
                    continuationField = field;
                }
            }
        }

        var warnings = new List<string>();
        var categories = new List<MasterDataCategory>();
        if (category is not null) categories.Add(category);
        categories.AddRange(createTargets.Values.Where(target => target.Id != category?.Id));
        if (request.Mode == "batch")
            foreach (var directory in originals.Keys.Select(path => paths.Relative(Path.GetDirectoryName(paths.Resolve(path))!)).Distinct(StringComparer.Ordinal))
                categories.Add(await catalog.GetAsync(directory, token));
        object input = continuation ? new
        {
            fieldPointer = request.FieldPointer, currentText,
            field = continuationField is null ? null : new { continuationField.Label, continuationField.Type },
            multiline = multilineContinuation
        } : new
        {
            mode = request.Mode, instruction = request.Instruction, categoryId = request.CategoryId,
            createTargetCategoryIds = createTargets.Keys,
            fieldPointer = request.FieldPointer, draft = draft is null ? null : new { path = draft.Path, raw = draft.Raw },
            targets = originals.Values.Select(value => new { path = value.Path, raw = value.Raw }),
            // Templates already include at most three similar saved masters. Do not send the entire catalog.
            categories = categories.Select(metadata => new
            {
                metadata.Id, metadata.Label, metadata.ItemCategoryCode,
                // Parent item fields are shared; send inherited definitions only once.
                fields = metadata.Fields.Where(field => category?.Id != "10.features.item" || metadata.Id == category.Id
                    || !category.Fields.Any(shared => shared.Path == field.Path && shared.Source == field.Source)).Take(120), schemas = metadata.JsonSchemas.Take(2),
                templates = metadata.Templates.Take(request.Mode == "create" ? (createTargets.Count > 1 ? 1 : 3) : 0).Select(value => new { value.Path, raw = value.Raw.Length <= 24_000 ? value.Raw : "（大きいため省略）" })
            })
        };
        var context = JsonSerializer.Serialize(input, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        if (context.Length > 240_000) throw new ArgumentException("対象原稿やスキーマが大きすぎます。対象を減らしてください。");
        var generated = await provider.GenerateAsync(credentials.Key, model, context, token,
            new(request.Mode, request.Mode == "create" ? createTargets.Keys.ToArray() : originals.Keys.ToArray(), continuation));
        if (request.Mode == "complete")
        {
            if (generated.Proposals.Count != 0) throw InvalidProposal("入力補完の応答にファイル作成・編集の提案が含まれています。");
            if (!continuation && generated.Suggestions.Count == 0)
                throw InvalidProposal("入力補完の候補は1〜3件必要です。");
            var suggestions = continuation
                ? Continuations(currentText!, multilineContinuation, generated.Suggestions)
                : generated.Suggestions;
            HashSet<MasterDataIssue>? existingErrors = null;
            if (continuation && suggestions.Count > 0)
            {
                var baseline = await validation.ValidateAsync(draft!.Path, draft.Raw, token);
                existingErrors = baseline.Issues.Where(issue => issue.Severity == "error").ToHashSet();
            }
            var accepted = new List<string>();
            foreach (var suggestion in suggestions)
            {
                var content = draft!.Content!.DeepClone();
                Set(content, pointer, arrayCompletion
                    ? new JsonArray(suggestion.Replace("\r\n", "\n", StringComparison.Ordinal).Split('\n').Select(line => (JsonNode?)JsonValue.Create(line)).ToArray())
                    : JsonValue.Create(suggestion));
                var rendered = codec.Render(new(draft.Path, content, draft.Raw));
                if (!continuation) RequireObject(draft.Path, rendered.Raw);
                var report = await validation.ValidateAsync(draft.Path, rendered.Raw, token);
                if (existingErrors is not null && report.Issues.Any(issue => issue.Severity == "error" && !existingErrors.Contains(issue)))
                {
                    warnings.Add("検証エラーが増える候補を除外しました。");
                    continue;
                }
                accepted.Add(suggestion);
                warnings.AddRange(rendered.Warnings);
                if (!report.IsValid) warnings.Add("入力補完後の原稿に検証エラーがあります。採用後に検証結果を確認してください。");
            }
            return new(request.Mode, model, accepted, [], warnings.Distinct().ToArray());
        }

        if (generated.Suggestions.Count != 0) throw InvalidProposal("ファイル作成・編集の応答に入力補完の候補が含まれています。");
        if (generated.Proposals.Count == 0
            || (request.Mode == "create" && generated.Proposals.Count > 3)
            || (request.Mode == "batch" && generated.Proposals.Count != originals.Count))
            throw InvalidProposal(request.Mode == "create" ? "新規作成の提案は1〜3件必要です。" : "一括編集の提案件数が選択ファイル数と一致しません。");
        var proposals = new List<MasterDataAiProposal>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var proposal in generated.Proposals)
        {
            try { RequireEditablePath(proposal.Path); }
            catch (Exception exception) when (exception is ArgumentException or UnauthorizedAccessException)
            { throw InvalidProposal("提案の対象パスが編集可能な Filebase のパスではありません。"); }
            MasterDataDocument? before = null;
            MasterDataCategory? targetCategory = null;
            if (request.Mode == "batch")
            {
                if (!seen.Add(proposal.Path)) throw InvalidProposal("一括編集の提案に同じ対象パスが重複しています。");
                if (!originals.TryGetValue(proposal.Path, out before)) throw InvalidProposal("一括編集の提案に選択していない対象パスが含まれています。");
            }
            else
            {
                var directory = Path.GetDirectoryName(proposal.Path)?.Replace('\\', '/');
                if (directory is null || !createTargets.TryGetValue(directory, out targetCategory))
                    throw InvalidProposal("新規作成の対象パスが許可された作成先カテゴリの直下ではありません。");
                if (File.Exists(paths.Resolve(proposal.Path)))
                    throw InvalidProposal("新規作成の提案が既存ファイルを対象にしています。新しいファイル名で再生成してください。");
                // Create proposals are independent alternatives. A provisional filename may be shared.
            }
            var content = RequireObject(proposal.Path, proposal.Raw);
            if (before is not null) RequireIdentity(before.Content!, content);
            // Re-render against the original so provider output cannot silently discard YAML comments.
            var rendered = codec.Render(new(proposal.Path, content, before?.Raw ?? draft?.Raw));
            if (rendered.Raw.Length > 64_000) throw InvalidProposal("提案の原稿が64000文字の上限を超えています。");
            RequireObject(proposal.Path, rendered.Raw);
            warnings.AddRange(rendered.Warnings);
            var report = await validation.ValidateAsync(proposal.Path, rendered.Raw, token);
            var issues = report.Issues.Select(issue => request.Mode == "create" && targetCategory?.ItemCategoryCode is not null
                && issue.Code is "DUPLICATE_ID" or "ITEM_ID_FORMAT" or "ITEM_FILENAME"
                    ? issue with { Severity = "warning", Message = "新規作成時の自動採番・命名で確定します。" } : issue).ToArray();
            if (request.Mode == "create" && targetCategory?.ItemCategoryCode is not null)
                warnings.Add("アイテムの ID とファイル名は新規作成ダイアログの自動採番で確定します。");
            proposals.Add(new(proposal.Path, before?.Path, rendered.Raw, before?.Raw, before?.Revision,
                proposal.Summary, issues, before is null));
        }
        return new(request.Mode, credentials.Model, [], proposals, warnings.Distinct().ToArray());
    }

    private static string[] Continuations(string currentText, bool multiline, IReadOnlyList<string> suffixes)
    {
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var result = new List<string>();
        var prefix = currentText.Trim();
        foreach (var candidate in suffixes)
        {
            var suffix = candidate.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n');
            var trimmed = suffix.Trim();
            if (trimmed.Length == 0 || suffix.Length > MaxContinuationSuffixLength || (!multiline && suffix.Contains('\n'))
                || trimmed == prefix || (prefix.Length >= 8 && trimmed.StartsWith(prefix, StringComparison.Ordinal))
                || !seen.Add(trimmed))
                continue;
            result.Add(currentText + suffix);
        }
        return result.ToArray();
    }

    private void RequireEditablePath(string path)
    {
        if (path is null || path.Length > 512) throw new ArgumentException("対象パスを確認してください。");
        paths.Resolve(path);
        if (MasterDataPaths.IsReadOnly(path) || path == "config.yml")
            throw new ArgumentException("このファイルは AI 編集の対象外です。");
    }

    private async Task<MasterDataDocument> ReadBoundedAsync(string path, CancellationToken token)
    {
        if (new FileInfo(paths.Resolve(path)).Length > 192_000) throw new ArgumentException("対象ファイルが大きすぎます。");
        var result = await data.ReadAsync(path, token);
        if (result.Raw.Length > 64_000) throw new ArgumentException("対象原稿は64000文字以内にしてください。");
        RequireObject(path, result.Raw);
        return result;
    }

    private JsonObject RequireObject(string path, string raw)
    {
        var parsed = codec.Parse(path, raw);
        if (parsed.Issues.Count > 0 || parsed.Content is not JsonObject content)
            throw new MasterDataAiException(422, "原稿を解析できません。YAML / JSON の構文を確認してください。");
        return content;
    }

    private static void RequireIdentity(JsonNode before, JsonNode after)
    {
        foreach (var key in IdentityKeys)
            if (!JsonNode.DeepEquals(before[key], after[key]))
                throw new MasterDataAiException(422, "既存の ID やスキーマバージョンを変更する提案は採用できません。");
    }

    private static bool IsIdentifier(string key) => IdentityKeys.Contains(key, StringComparer.OrdinalIgnoreCase)
        || key.EndsWith("Id", StringComparison.OrdinalIgnoreCase) || key.EndsWith("Ids", StringComparison.OrdinalIgnoreCase)
        || key.EndsWith("Ref", StringComparison.OrdinalIgnoreCase) || key.EndsWith("Refs", StringComparison.OrdinalIgnoreCase)
        || key.Equals("reference", StringComparison.OrdinalIgnoreCase) || key.Equals("references", StringComparison.OrdinalIgnoreCase)
        || key is "uuid" or "key";
    private static bool LooksLikeReference(JsonNode? value) => value switch
    {
        JsonValue scalar when scalar.GetValueKind() == JsonValueKind.String => Regex.IsMatch(scalar.GetValue<string>(), @"\A[a-z_]+:[A-Za-z0-9_.\-]+\z"),
        JsonArray array => array.Any(LooksLikeReference),
        _ => false
    };
    private static string Escape(string part) => part.Replace("~", "~0", StringComparison.Ordinal).Replace("/", "~1", StringComparison.Ordinal);
    private static string[] ParsePointer(string pointer)
    {
        if (!pointer.StartsWith('/') || pointer.Length < 2 || Regex.IsMatch(pointer, "~(?![01])"))
            throw new ArgumentException("対象フィールドは JSON Pointer で指定してください。");
        return pointer[1..].Split('/').Select(part => part.Replace("~1", "/", StringComparison.Ordinal).Replace("~0", "~", StringComparison.Ordinal)).ToArray();
    }
    private static JsonNode? At(JsonNode? node, IEnumerable<string> pointer)
    {
        foreach (var part in pointer)
            node = node switch
            {
                JsonObject obj => obj[part],
                JsonArray array when int.TryParse(part, out var index) && index >= 0 && index < array.Count => array[index],
                _ => throw new ArgumentException("対象フィールドが見つかりません。")
            };
        return node;
    }
    private static void Set(JsonNode node, string[] pointer, JsonNode? value)
    {
        var parent = At(node, pointer[..^1]);
        if (parent is JsonObject obj) obj[pointer[^1]] = value;
        else if (parent is JsonArray array && int.TryParse(pointer[^1], out var index) && index >= 0 && index < array.Count) array[index] = value;
        else throw new ArgumentException("対象フィールドが見つかりません。");
    }
    private static MasterDataAiException InvalidProposal(string reason) => new(502, $"AI の提案を採用できません。{reason} 指示を調整して再実行してください。");
}
