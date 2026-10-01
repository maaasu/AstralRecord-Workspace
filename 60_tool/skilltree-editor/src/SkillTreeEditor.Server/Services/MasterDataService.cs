using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

public sealed class MasterDataService(MasterDataPaths paths, WorkspacePaths workspace, MasterDataCodec codec,
    MasterDataValidation validation, WorkspaceMutationGate mutations, BackupService backups)
{
    public async Task<IReadOnlyList<MasterDataFile>> ListAsync(string? category, string? query, CancellationToken token)
    {
        var result = new List<MasterDataFile>();
        foreach (var file in paths.Enumerate(string.IsNullOrWhiteSpace(category) || category == "." ? null : category).Where(MasterDataPaths.IsData).Order(StringComparer.Ordinal))
        {
            var document = await ReadAsync(paths.Relative(file), token);
            var obj = document.Content as JsonObject;
            var id = MasterDataValidation.Text(obj?["id"] ?? obj?["nodeId"] ?? obj?["structureId"]);
            var name = MasterDataValidation.Text(obj?["name"]);
            var icon = obj?["icon"] is JsonValue iconValue && iconValue.TryGetValue<string>(out var material) ? material : null;
            if (!string.IsNullOrWhiteSpace(query) && !(document.Path + "\n" + id + "\n" + name + "\n" + document.Raw).Contains(query, StringComparison.OrdinalIgnoreCase)) continue;
            var info = new FileInfo(file);
            result.Add(new(document.Path, paths.Relative(info.DirectoryName!), document.Format, id, name,
                info.Length, info.LastWriteTimeUtc, document.Revision, document.Issues.FirstOrDefault()?.Message,
                MasterDataPaths.IsReadOnly(document.Path), icon));
        }
        return result;
    }

    public async Task<MasterDataDocument> ReadAsync(string path, CancellationToken token)
    {
        var full = paths.Resolve(path);
        var bytes = await File.ReadAllBytesAsync(full, token);
        var raw = new UTF8Encoding(false, true).GetString(bytes);
        if (raw.StartsWith('\uFEFF')) raw = raw[1..];
        var parsed = codec.Parse(path, raw);
        return new(path, MasterDataPaths.Format(path), raw, Hash(bytes), parsed.Content, parsed.Issues, MasterDataPaths.IsReadOnly(path));
    }

    public async Task<MasterDataDocument> SaveAsync(MasterDataSaveRequest request, CancellationToken token)
    {
        await using var mutation = await EnterMutationAsync(token);
        var full = RequireWritable(request.Path);
        var existing = await ReadAsync(request.Path, token);
        RequireRevision(existing, request.Revision);
        var parsed = codec.Parse(request.Path, request.Raw);
        if (existing.Content is JsonObject oldObject && parsed.Content is JsonObject newObject)
        {
            // References must remain stable; creation/copy supplies a new identity explicitly.
            foreach (var key in new[] { "id", "nodeId", "structureId" })
                if (oldObject.ContainsKey(key) && !JsonNode.DeepEquals(oldObject[key], newObject[key]))
                    throw new InvalidOperationException($"{key} は既存ファイルの保存では変更できません。新規作成 / 複製してください。");
        }
        await RequireValidAsync(request.Path, request.Raw, token);
        if (existing.Raw != request.Raw)
        {
            await backups.BackupAsync(full, "master-data", token);
            await WriteAtomicAsync(full, request.Raw, create: false, token);
        }
        return await ReadAsync(request.Path, token);
    }

    public async Task<MasterDataDocument> CreateAsync(MasterDataCreateRequest request, CancellationToken token)
    {
        await using var mutation = await EnterMutationAsync(token);
        return await CreateUnderLockAsync(request, token);
    }

    public async Task<MasterDataDocument> CopyAsync(MasterDataCopyRequest request, CancellationToken token)
    {
        await using var mutation = await EnterMutationAsync(token);
        var source = await ReadAsync(request.SourcePath, token);
        RequireRevision(source, request.Revision);
        var raw = source.Raw;
        if (request.NewId is { } newId)
        {
            SafePath.RequireIdentifier(newId, nameof(request.NewId));
            var content = source.Content?.DeepClone() as JsonObject ?? throw new ArgumentException("複製元に ID がありません。");
            var key = content.ContainsKey("id") ? "id" : content.ContainsKey("structureId") ? "structureId" : throw new ArgumentException("複製元に ID がありません。");
            content[key] = newId;
            raw = codec.Render(new(request.TargetPath, content, source.Raw)).Raw;
        }
        return await CreateUnderLockAsync(new(request.TargetPath, raw, request.AutoItemId, request.Slug, request.Group, request.AutoName), token);
    }

    private async Task<MasterDataDocument> CreateUnderLockAsync(MasterDataCreateRequest request, CancellationToken token)
    {
        var path = request.Path;
        var raw = request.Raw;
        if (request.AutoItemId || request.AutoName)
        {
            var directory = paths.Resolve(path, true);
            var content = codec.Parse("document.yml", raw).Content as JsonObject
                ?? throw new ArgumentException("自動命名には YAML オブジェクトが必要です。");
            var version = MasterDataValidation.Text(content["schemaVersion"]) ?? "1";
            if (!Regex.IsMatch(version, "^[1-9][0-9]*$")) throw new ArgumentException("schemaVersion は正の整数です。");
            if (request.AutoItemId)
            {
                var code = MasterDataCatalog.ItemCode(path) ?? throw new ArgumentException("アイテムの採番対象カテゴリフォルダを選択してください。");
                var group = request.Group == "z" ? 'z' : 'a';
                if (request.Group is not null and not "z" and not "a")
                    throw new ArgumentException("通常の英字は自動繰り上げします。デバッグのみ group=z を指定できます。");
                var id = await NextItemIdAsync(code, group, token);
                content["id"] = id;
                content["category"] = Path.GetFileName(directory).Split('.', 2)[1];
                var slug = request.Slug ?? "new_item";
                if (!Regex.IsMatch(slug, "^[A-Za-z0-9][A-Za-z0-9_-]{0,100}$"))
                    throw new ArgumentException("管理用 slug は英数字・_・- で指定してください。");
                path += $"/v{version}.{id}.{slug}.yml";
            }
            else if (path.Equals("20.features.class", MasterDataPaths.Comparison))
            {
                var id = SafePath.RequireIdentifier(MasterDataValidation.Text(content["id"]) ?? "", "id");
                if (!decimal.TryParse(MasterDataValidation.Text(content["order"]), NumberStyles.Float, CultureInfo.InvariantCulture, out var order))
                    throw new ArgumentException("クラスの order は数値で指定してください。");
                if (order * 10 != decimal.Truncate(order * 10)) throw new ArgumentException("order × 10 が整数になる表示順を指定してください。");
                var orderPart = id == "administrator" ? "9999" : (order * 10).ToString("0", CultureInfo.InvariantCulture);
                path += $"/v{version}.{orderPart}.{id}.yml";
            }
            else throw new ArgumentException("自動命名はアイテムまたはクラスで使用できます。");
            raw = codec.Render(new(path, content, request.Raw)).Raw;
        }
        var full = RequireWritable(path);
        if (path.StartsWith("35.features.skilltree/nodes/", MasterDataPaths.Comparison))
            throw new UnauthorizedAccessException("ノードの新規作成・複製はスキルツリー画面で自動採番してください。");
        if (File.Exists(full)) throw new InvalidOperationException("作成先ファイルは既に存在します。");
        await RequireValidAsync(path, raw, token);
        Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        paths.RequireNoLinks(full);
        await WriteAtomicAsync(full, raw, create: true, token);
        return await ReadAsync(path, token);
    }

    public async Task DeleteAsync(string path, string revision, CancellationToken token)
    {
        await using var mutation = await EnterMutationAsync(token);
        var full = RequireWritable(path);
        if (path.Equals("config.yml", MasterDataPaths.Comparison)) throw new UnauthorizedAccessException("Filebase の設定ファイルは削除できません。");
        if (path.Equals(PetMasterValidation.MasterPath, MasterDataPaths.Comparison)) throw new UnauthorizedAccessException("単一ペットマスターは削除できません。フォームまたは原稿で更新してください。");
        var source = await ReadAsync(path, token);
        RequireRevision(source, revision);
        var references = await ReferencesAsync(path, token);
        if (references.Count > 0)
            throw new InvalidOperationException("参照されているため削除できません: " + string.Join(", ", references.Select(reference => reference.Path).Distinct()));
        await backups.BackupAsync(full, "master-data", token);
        File.Delete(full);
    }

    public async Task<IReadOnlyList<MasterDataReference>> ReferencesAsync(string path, CancellationToken token)
    {
        var source = await ReadAsync(path, token);
        var obj = source.Content as JsonObject;
        var id = MasterDataValidation.Text(obj?["id"] ?? obj?["nodeId"] ?? obj?["structureId"]);
        var kind = Kind(path);
        var sourceIds = new HashSet<string>(StringComparer.Ordinal);
        if (!string.IsNullOrEmpty(id)) sourceIds.Add(id);
        if (kind is "tag" or "status" && obj is not null)
        {
            var keyPath = kind == "tag" ? "tags" : "statuses";
            if (obj[keyPath] is JsonArray entries)
                foreach (var entry in entries.OfType<JsonObject>())
                    if (MasterDataValidation.Text(entry["id"]) is { } entryId) sourceIds.Add(entryId);
        }
        var prefixes = await ConfiguredPrefixesAsync(kind, token);
        var result = new List<MasterDataReference>();
        foreach (var file in paths.Enumerate().Where(MasterDataPaths.IsData))
        {
            var relative = paths.Relative(file);
            if (relative.Equals(path, MasterDataPaths.Comparison) || file.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
            var document = await ReadAsync(relative, token);
            foreach (var value in Walk(document.Content))
            {
                var keys = value.Pointer.Split('/');
                if (sourceIds.Any(sourceId => prefixes.Any(prefix => value.Value == prefix + ":" + sourceId)
                    || value.Value == sourceId && keys.Any(key => IsReferenceKey(key, kind))))
                    result.Add(new(relative, value.Pointer, value.Value, MasterDataValidation.Text((document.Content as JsonObject)?["name"]), kind));
            }
            // Local $schema references are path based, not identity based.
            if (path.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase) && document.Content is JsonObject root
                && MasterDataValidation.Text(root["$schema"]) is { } schema && !Uri.TryCreate(schema, UriKind.Absolute, out _))
            {
                var fullReference = Path.GetFullPath(Path.Combine(Path.GetDirectoryName(file)!, schema));
                if (fullReference.Equals(paths.Resolve(path), MasterDataPaths.Comparison))
                    result.Add(new(relative, "/$schema", schema, null, "schema"));
            }
        }
        if (kind == "structure" && File.Exists(workspace.PluginConfig))
        {
            paths.RequireNoLinks(workspace.PluginConfig);
            var configuration = codec.Parse("config.yml", await File.ReadAllTextAsync(workspace.PluginConfig, token)).Content as JsonObject;
            if (configuration?["skilltree"] is JsonObject settings && MasterDataValidation.Text(settings["structureId"]) == id)
                result.Add(new("../10_plugin/AstralRecord/src/main/resources/config.yml", "/skilltree/structureId", id!, "Plugin の表示構造", kind));
        }
        return result;
    }

    public async Task<IReadOnlyList<MasterDataReference>> CandidatesAsync(CancellationToken token)
    {
        var result = new List<MasterDataReference>();
        foreach (var file in paths.Enumerate().Where(MasterDataPaths.IsData))
        {
            if (file.EndsWith(".schema.json", StringComparison.OrdinalIgnoreCase)) continue;
            var path = paths.Relative(file);
            var document = await ReadAsync(path, token);
            if (document.Content is JsonObject root)
            {
                var key = root.ContainsKey("id") ? "id" : root.ContainsKey("nodeId") ? "nodeId" : "structureId";
                if (MasterDataValidation.Text(root[key]) is { } id)
                    result.Add(new(path, "/" + key, id, MasterDataValidation.Text(root["name"]), Kind(path)));
                if (Kind(path) is "tag" or "status")
                {
                    var keyPath = Kind(path) == "tag" ? "tags" : "statuses";
                    CollectCatalogCandidates(root[keyPath], path, "/" + keyPath, Kind(path), result);
                }
            }
        }
        return result;
    }

    private static void CollectCatalogCandidates(JsonNode? node, string path, string pointer, string kind, List<MasterDataReference> result)
    {
        if (node is JsonObject obj)
        {
            if (MasterDataValidation.Text(obj["id"]) is { } id)
                result.Add(new(path, pointer + "/id", id, MasterDataValidation.Text(obj["displayName"] ?? obj["name"]), kind));
            foreach (var pair in obj) CollectCatalogCandidates(pair.Value, path, pointer + "/" + MasterDataValidation.Escape(pair.Key), kind, result);
        }
        else if (node is JsonArray array)
            for (var index = 0; index < array.Count; index++) CollectCatalogCandidates(array[index], path, pointer + "/" + index, kind, result);
    }

    private static bool IsReferenceKey(string key, string kind) => kind switch
    {
        "item" => key is "itemId" or "item" or "requiredItemId" or "allowedSigilIds" or "sigilId",
        "class" => key is "classId" or "class" or "requiredClasses",
        "skill" => key is "skillId" or "skill" or "usableSkills" or "requiredSkillId" or "sourceSkillId",
        "node" => key is "nodeId" or "rootNodeId" or "sourceNodeId" or "targetNodeId",
        "structure" => key == "structureId",
        "set_effect" => key == "setId",
        "status" => key == "status",
        "tag" => key is "tag" or "tags" or "targetTags" or "requiredToolTags",
        "loot_table" => key is "lootTable" or "lootTableId",
        "loot_pool" => key is "poolId" or "lootPoolId",
        _ => key.EndsWith("Id", StringComparison.Ordinal) && key != "id"
    };

    public static string Kind(string path)
    {
        if (path.StartsWith("10.features.item/20.equipment/set_effect/", MasterDataPaths.Comparison)) return "set_effect";
        if (path.StartsWith("10.features.item/", MasterDataPaths.Comparison)) return "item";
        if (path.StartsWith("80.shared.loot/table/", MasterDataPaths.Comparison)) return "loot_table";
        if (path.StartsWith("80.shared.loot/pool/", MasterDataPaths.Comparison)) return "loot_pool";
        if (path.StartsWith("35.features.skilltree/nodes/", MasterDataPaths.Comparison)) return "node";
        if (path.StartsWith("35.features.skilltree/structures/", MasterDataPaths.Comparison)) return "structure";
        var top = path.Split('/')[0].Split('.', 3).Last().Replace('.', '_');
        return OperatingSystem.IsWindows() ? top.ToLowerInvariant() : top;
    }

    private static IEnumerable<string> ReferencePrefixes(string kind) => kind switch
    {
        "item" => ["item", "im"], "class" => ["class", "cs"], "skill" => ["skill", "sk"],
        "set_effect" => ["set", "st"], "loot_table" => ["loot_table", "lt"], "loot_pool" => ["loot_pool", "lp"],
        "mob_spawner" => ["mob_spawner", "ms"], "gathering_spawner" => ["gathering_spawner", "gs"],
        "enchant" => ["enchant", "en"], "buff" => ["buff", "bf"], "recipe" => ["recipe", "rc"],
        "shop" => ["shop", "sh"], "mob" => ["mob", "mb"], "gathering" => ["gathering", "gt"],
        "world" => ["world", "wd"], "dungeon" => ["dungeon", "dn"], "mail" => ["mail", "ml"], "guide" => ["guide", "gd"],
        _ => [kind]
    };

    private async Task<string[]> ConfiguredPrefixesAsync(string kind, CancellationToken token)
    {
        var result = ReferencePrefixes(kind).ToHashSet(StringComparer.Ordinal);
        var configPath = paths.Resolve("config.yml");
        if (!File.Exists(configPath)) return result.ToArray();
        var config = codec.Parse("config.yml", await File.ReadAllTextAsync(configPath, token)).Content as JsonObject;
        if (config?["referenceResolver"] is JsonArray resolvers)
            foreach (var resolver in resolvers.OfType<JsonObject>())
            {
                var database = MasterDataValidation.Text(resolver["database"])?.Replace('.', '_');
                if (database != kind) continue;
                if (MasterDataValidation.Text(resolver["prefix"]) is { } prefix) result.Add(prefix.TrimEnd(':'));
                if (resolver["aliases"] is JsonArray aliases)
                    foreach (var alias in aliases)
                        if (MasterDataValidation.Text(alias) is { } value) result.Add(value.TrimEnd(':'));
            }
        return result.ToArray();
    }

    private static IEnumerable<(string Pointer, string Value)> Walk(JsonNode? node, string pointer = "")
    {
        if (node is JsonObject obj)
            foreach (var pair in obj)
                foreach (var child in Walk(pair.Value, pointer + "/" + MasterDataValidation.Escape(pair.Key))) yield return child;
        else if (node is JsonArray array)
            for (var index = 0; index < array.Count; index++)
                foreach (var child in Walk(array[index], pointer + "/" + index)) yield return child;
        else if (MasterDataValidation.Text(node) is { } value) yield return (pointer, value);
    }

    private async Task<string> NextItemIdAsync(string code, char initialGroup, CancellationToken token)
    {
        var maximum = 0;
        foreach (var file in paths.Enumerate("10.features.item").Where(MasterDataPaths.IsData))
        {
            var content = codec.Parse(file, await File.ReadAllTextAsync(file, token)).Content as JsonObject;
            var id = MasterDataValidation.Text(content?["id"]);
            if (id is null || !Regex.IsMatch(id, "^" + code + "[a-z][0-9]{5}$")) continue;
            if (initialGroup == 'z' && id[2] == 'z') maximum = Math.Max(maximum, int.Parse(id[3..], CultureInfo.InvariantCulture));
            else if (initialGroup == 'a' && id[2] <= 'y') maximum = Math.Max(maximum, (id[2] - 'a') * 99999 + int.Parse(id[3..], CultureInfo.InvariantCulture));
        }
        var next = maximum + 1;
        if (initialGroup == 'z')
        {
            if (next > 99999) throw new InvalidOperationException("デバッグアイテムの連番が上限に達しています。");
            return code + "z" + next.ToString("D5", CultureInfo.InvariantCulture);
        }
        if (next > 25 * 99999) throw new InvalidOperationException("通常アイテムの連番が上限に達しています。");
        return code + (char)('a' + (next - 1) / 99999) + ((next - 1) % 99999 + 1).ToString("D5", CultureInfo.InvariantCulture);
    }

    private string RequireWritable(string path)
    {
        if (MasterDataPaths.IsReadOnly(path)) throw new UnauthorizedAccessException("nodeId 採番の管理ファイルは直接編集できません。");
        var full = paths.Resolve(path);
        paths.RequireNoLinks(workspace.Backups);
        paths.RequireNoLinks(workspace.WorkspaceMutationLock);
        paths.RequireNoLinks(Path.Combine(workspace.Backups, "master-data"));
        return full;
    }

    private ValueTask<IAsyncDisposable> EnterMutationAsync(CancellationToken token)
    {
        paths.RequireNoLinks(workspace.WorkspaceMutationLock);
        return mutations.EnterAsync(token);
    }

    private async Task RequireValidAsync(string path, string raw, CancellationToken token)
    {
        var report = await validation.ValidateAsync(path, raw, token);
        if (!report.IsValid) throw new MasterDataValidationException(report);
    }

    private static void RequireRevision(MasterDataDocument document, string revision)
    {
        if (string.IsNullOrWhiteSpace(revision) || document.Revision != revision)
            throw new InvalidOperationException("ファイルが変更されています。再読み込みして差分を確認してください。");
    }

    private async Task WriteAtomicAsync(string path, string raw, bool create, CancellationToken token)
    {
        paths.RequireNoLinks(path);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await File.WriteAllTextAsync(temporary, raw, new UTF8Encoding(false, true), token);
            paths.RequireNoLinks(path);
            File.Move(temporary, path, overwrite: !create);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    private static string Hash(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
}
