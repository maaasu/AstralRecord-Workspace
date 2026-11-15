using System.Globalization;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>One bounded immutable experiment; each variant receives the same trial seed.</summary>
public static class BalanceLabRunner
{
    public static void Validate(JsonObject request)
    {
        if (request.ToJsonString().Length > 1_000_000) throw new ArgumentException("条件JSONは1MB以下にしてください。");
        RequireInteger(request, "schemaVersion", 1, 1);
        RequireInteger(request, "seed", int.MinValue, int.MaxValue);
        var trials = RequireInteger(request, "trials", 1, 500);
        var seconds = RequireInteger(request, "maxSeconds", 1, 600);
        if (request["mode"]?.ToString() is not ("strict" or "approximate"))
            throw new ArgumentException("計算モードは strict / approximate から指定してください。");
        if (request["builds"] is not JsonArray builds || builds.Count is < 1 or > 4 || builds.Any(value => value is not JsonObject))
            throw new ArgumentException("ビルドを1〜4件指定してください。");
        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var build in builds.OfType<JsonObject>())
        {
            if (string.IsNullOrWhiteSpace(build["id"]?.ToString()) || !ids.Add(build["id"]!.ToString()))
                throw new ArgumentException("各ビルドに重複しないIDを指定してください。");
            RequireInteger(build, "playerLevel", 1, 200);
            RequireInteger(build, "classLevel", 1, 10000);
            if (build["normalAttacks"] is not null && build["normalAttacks"]!.GetValueKind() is not (System.Text.Json.JsonValueKind.True or System.Text.Json.JsonValueKind.False))
                throw new ArgumentException("normalAttacks は真偽値で指定してください。");
            if (build["initialShield"] is not null) RequireNumber(build, "initialShield", 0, 1e9);
            if (build["equipment"] is not JsonArray equipment || equipment.Count > 20)
                throw new ArgumentException("装備構成は20件以内の配列で指定してください。");
            if (build["skillIds"] is not JsonArray skills || skills.Count > 20)
                throw new ArgumentException("スキル優先順位は20件以内の配列で指定してください。");
            if (skills.Any(value => value is null || value.GetValueKind() != System.Text.Json.JsonValueKind.String)
                || skills.Select(value => value!.ToString()).Distinct(StringComparer.OrdinalIgnoreCase).Count() != skills.Count)
                throw new ArgumentException("スキルIDは重複しない文字列で指定してください。");
            if (equipment.Any(value => value is not JsonObject)) throw new ArgumentException("装備はオブジェクトで指定してください。");
            foreach (var gear in equipment.OfType<JsonObject>())
            {
                foreach (var field in new[] { "enhanceLevel", "transcendRank", "durabilityMax", "durabilityValue" })
                    if (gear[field] is not null) RequireInteger(gear, field, 0, 100000);
                foreach (var field in new[] { "statRolls", "enchants", "runes", "enchantments" })
                    if (gear[field] is not null && gear[field] is not JsonArray) throw new ArgumentException($"{field}は配列で指定してください。");
                foreach (var roll in (gear["statRolls"] as JsonArray ?? []).OfType<JsonObject>())
                {
                    RequireNumber(roll, "min", -1e9, 1e9);
                    RequireNumber(roll, "max", -1e9, 1e9);
                }
                foreach (var enchant in (gear["enchants"] as JsonArray ?? []))
                {
                    if (enchant is not JsonObject definition || string.IsNullOrWhiteSpace(definition["status"]?.ToString()))
                        throw new ArgumentException("エンチャントはstatusとvalueを持つオブジェクトで指定してください。");
                    RequireNumber(definition, "value", -1e9, 1e9);
                }
            }
            if (build["baseStats"] is JsonObject stats)
                foreach (var field in stats.Select(value => value.Key)) RequireNumber(stats, field, -1e9, 1e9);
            else if (build["baseStats"] is not null) throw new ArgumentException("baseStatsは数値のマップで指定してください。");
        }
        if (request["enemy"] is not JsonObject enemy || string.IsNullOrWhiteSpace(enemy["mobId"]?.ToString()))
            throw new ArgumentException("敵マスターを指定してください。");
        RequireInteger(enemy, "level", 1, 10000);
        if (enemy["attackIntervalTicks"] is not null) RequireInteger(enemy, "attackIntervalTicks", 0, 100000);
        if (enemy["distanceBlocks"] is not null) RequireNumber(enemy, "distanceBlocks", double.Epsilon, 128);
        if (enemy["targetHalfDepth"] is not null) RequireNumber(enemy, "targetHalfDepth", double.Epsilon, 16);
        if (request["economy"] is JsonObject economy)
        {
            foreach (var field in new[] { "quantity", "fromEnhanceLevel", "targetEnhanceLevel", "transcendenceRank", "orbOwned", "playerLevel", "targetPlayerLevel", "classLevel", "targetClassLevel" })
                if (economy[field] is not null) RequireInteger(economy, field, 0, 10000);
            foreach (var field in new[] { "dropRatePercent", "luck", "moveSeconds", "waitSeconds", "resupplySeconds", "killSeconds", "timeBudgetSeconds" })
                if (economy[field] is not null) RequireNumber(economy, field, 0, 1e9);
            foreach (var field in new[] { "playerLevel", "targetPlayerLevel" })
                if (economy[field] is not null) RequireInteger(economy, field, 1, 200);
            foreach (var field in new[] { "classLevel", "targetClassLevel" })
                if (economy[field] is not null) RequireInteger(economy, field, 1, 10000);
        }
        else if (request["economy"] is not null) throw new ArgumentException("economyはオブジェクトで指定してください。");
        if (request["overrides"] is not null && request["overrides"] is not JsonArray)
            throw new ArgumentException("調整案は配列で指定してください。");
        var overrides = request["overrides"] as JsonArray;
        if (overrides?.Count > 50) throw new ArgumentException("調整案は50マスターまでです。");
        var variants = overrides?.Count > 0 ? 2 : 1;
        if ((long)trials * seconds * 20 * builds.Count * variants > 2_000_000)
            throw new ArgumentException("計算量の上限200万tickを超えます。試行数・時間・ビルド数を減らしてください。");
    }

    public static JsonObject Run(JsonObject catalog, JsonObject request, Action<int, int> progress, CancellationToken token)
    {
        Validate(request);
        var results = new JsonArray();
        var warnings = new JsonArray();
        var changes = new JsonArray();
        if (request["masterHash"] is { } expected && expected.ToString() != catalog["masterHash"]?.ToString())
            warnings.Add("保存時とマスターのハッシュが異なります。同じマスターを再現できていません。");
        if (request["modelVersion"] is { } model && model.ToString() != BalanceLabCatalog.ModelVersion)
            warnings.Add("保存時と計算モデルの版が異なります。");
        if (request["sourceHash"] is { } source && source.ToString() != catalog["sourceHash"]?.ToString())
            warnings.Add("保存時とPluginの計算ソースが異なります。固定ケース照合を再実行してください。");
        if (catalog["diagnostics"]?.AsArray().Count > 0)
            warnings.Add("解析できないマスターがあります。参照先が不足する条件は実行できません。");
        var variants = new List<(string Name, JsonObject Catalog)> { ("current", catalog) };
        if (request["overrides"] is JsonArray { Count: > 0 } overrides)
            variants.Add(("proposal", BalanceLabCatalog.ApplyProposal(catalog, overrides, out changes)));
        var builds = request["builds"]!.AsArray().OfType<JsonObject>().ToArray();
        var count = RequireInteger(request, "trials", 1, 500);
        var total = variants.Count * builds.Length * count;
        var completed = 0;
        foreach (var variant in variants)
            foreach (var build in builds)
            {
                var trials = new JsonArray();
                JsonObject? first = null;
                for (var index = 0; index < count; index++)
                {
                    token.ThrowIfCancellationRequested();
                    var seed = TrialSeed(RequireInteger(request, "seed", int.MinValue, int.MaxValue), index);
                    var trial = BalanceCombat.Simulate(variant.Catalog, request, build, seed, token);
                    first ??= trial.DeepClone().AsObject();
                    trial["index"] = index;
                    trial["seed"] = seed;
                    // Resource traces are for the first specified trial, never a selected successful run.
                    if (index > 0) { trial["resources"] = new JsonArray(); trial["skillTimeline"] = new JsonArray(); }
                    else if (trial["resources"] is JsonArray trace && trace.Count > 301)
                    {
                        var interval = (int)Math.Ceiling((trace.Count - 1) / 300.0);
                        trial["resources"] = new JsonArray(trace.Where((_, n) => n % interval == 0 || n == trace.Count - 1).Select(value => value?.DeepClone()).ToArray());
                    }
                    trials.Add(trial);
                    progress(++completed, total);
                    // Validation errors do not need hundreds of identical evaluations.
                    if (trial["outcome"]?.ToString() is "invalid" or "unsupported") break;
                }
                var summary = Summarize(trials);
                var status = first?["outcome"]?.ToString() is "invalid" or "unsupported" ? first["outcome"]!.ToString() : "completed";
                var result = new JsonObject
                {
                    ["buildId"] = build["id"]?.DeepClone(), ["buildName"] = build["name"]?.DeepClone() ?? build["id"]?.DeepClone(),
                    ["variant"] = variant.Name, ["status"] = status, ["requestedTrials"] = count,
                    ["diagnostics"] = first?["diagnostics"]?.DeepClone() ?? new JsonArray(),
                    ["playerStats"] = first?["playerStats"]?.DeepClone(), ["enemyStats"] = first?["enemyStats"]?.DeepClone(),
                    ["assumptions"] = first?["assumptions"]?.DeepClone() ?? new JsonArray(),
                    ["sources"] = first?["sources"]?.DeepClone() ?? new JsonArray(),
                    ["trials"] = trials, ["summary"] = summary
                };
                if (request["economy"] is JsonObject)
                {
                    var economyRequest = request.DeepClone().AsObject();
                    economyRequest["builds"] = new JsonArray(build.DeepClone());
                    var economySummary = summary.DeepClone().AsObject();
                    economySummary["playerStats"] = first?["playerStats"]?.DeepClone();
                    var changed = BalanceEconomyParity.Mismatches(variant.Catalog);
                    if (changed.Length > 0 && request["mode"]?.ToString() == "strict")
                        result["economy"] = new JsonObject { ["status"] = "unsupported", ["diagnostics"] = new JsonArray(new JsonObject
                        { ["code"] = "ECONOMY_SOURCE_CHANGED", ["severity"] = "error", ["message"] = "経済モデルの根拠ソースが変更されています。照合してからモデルを更新してください: " + changed[0] }) };
                    else
                    {
                        var estimate = BalanceEconomy.Estimate(variant.Catalog, economyRequest, economySummary, token);
                        if (changed.Length > 0 && estimate["diagnostics"] is JsonArray diagnostics)
                            diagnostics.Add(new JsonObject { ["code"] = "ECONOMY_SOURCE_CHANGED", ["severity"] = "warning", ["message"] = "経済の根拠ソース変更後に再照合されていない近似結果です。" });
                        result["economy"] = estimate;
                    }
                }
                results.Add(result);
            }
        return new JsonObject
        {
            ["schemaVersion"] = 1, ["modelVersion"] = BalanceLabCatalog.ModelVersion,
            ["masterHash"] = catalog["masterHash"]?.DeepClone(), ["sourceHash"] = catalog["sourceHash"]?.DeepClone(),
            ["proposalHash"] = variants.Count > 1 ? variants[1].Catalog["masterHash"]?.DeepClone() : null,
            ["manifest"] = catalog["manifest"]?.DeepClone(), ["sources"] = catalog["sources"]?.DeepClone(),
            ["seed"] = request["seed"]?.DeepClone(), ["scenario"] = request.DeepClone(),
            ["assumptions"] = new JsonArray("20tick/秒の固定一対一・停止・射程内の条件実験です。移動・地形・回避操作・敵AIは再現しません。", "平均DPSと経過時間は死亡・時間切れを含む全有効試行から計算します。討伐時間の分位点は未討伐を無限時間として扱います。", "リソース曲線は成功可否にかかわらず第1試行のみ、最大301点を保存します。", "試行seedは先頭で指定seedをそのまま使い、以降はモデル版に固定した整数混合で生成します。"),
            ["warnings"] = warnings, ["results"] = results, ["changes"] = changes,
            ["comparison"] = Compare(results)
        };
    }

    public static JsonObject Summarize(JsonArray trials)
    {
        var valid = trials.OfType<JsonObject>().Where(value => value["outcome"]?.ToString() is "victory" or "death" or "timeout").ToArray();
        var dps = valid.Select(value => Number(value["dps"])).Order().ToArray();
        var elapsed = valid.Select(value => Number(value["elapsedTicks"]) / 20).Order().ToArray();
        var killTimes = valid.Select(value => value["outcome"]?.ToString() == "victory" ? Number(value["elapsedTicks"]) / 20 : double.PositiveInfinity).Order().ToArray();
        var mean = dps.Length == 0 ? (double?)null : dps.Average();
        return new JsonObject
        {
            ["totalTrials"] = trials.Count, ["validTrials"] = valid.Length,
            ["victories"] = Count("victory"), ["deaths"] = Count("death"), ["timeouts"] = Count("timeout"),
            ["invalid"] = Count("invalid"), ["unsupported"] = Count("unsupported"),
            ["successRate"] = valid.Length == 0 ? null : (double)Count("victory") / valid.Length,
            ["meanDps"] = mean, ["medianDps"] = Quantile(dps, .5),
            ["stdDevDps"] = mean is null ? null : Math.Sqrt(dps.Select(value => Math.Pow(value - mean.Value, 2)).Average()),
            ["meanElapsedSeconds"] = elapsed.Length == 0 ? null : elapsed.Average(),
            ["medianElapsedSeconds"] = Quantile(elapsed, .5), ["p90ElapsedSeconds"] = Quantile(elapsed, .9),
            ["medianKillSeconds"] = Quantile(killTimes, .5), ["p90KillSeconds"] = Quantile(killTimes, .9),
            ["meanDamageDealt"] = valid.Length == 0 ? null : valid.Average(value => Number(value["damageDealt"])),
            ["meanDamageTaken"] = valid.Length == 0 ? null : valid.Average(value => Number(value["damageTaken"]))
        };
        int Count(string outcome) => trials.Count(value => value?["outcome"]?.ToString() == outcome);
    }

    private static JsonArray Compare(JsonArray results)
    {
        var comparison = new JsonArray();
        var all = results.OfType<JsonObject>().ToArray();
        foreach (var current in all)
        {
            var basis = current["variant"]?.ToString() == "proposal"
                ? all.FirstOrDefault(value => value["buildId"]?.ToString() == current["buildId"]?.ToString() && value["variant"]?.ToString() == "current")
                : all.FirstOrDefault(value => value["variant"]?.ToString() == "current");
            if (basis is null || ReferenceEquals(basis, current)) continue;
            var metrics = new JsonArray();
            foreach (var field in new[] { "meanDps", "medianDps", "stdDevDps", "successRate", "meanElapsedSeconds", "meanDamageDealt", "meanDamageTaken" })
            {
                var before = basis["summary"]?[field]; var after = current["summary"]?[field];
                metrics.Add(new JsonObject { ["metric"] = field, ["before"] = before?.DeepClone(), ["after"] = after?.DeepClone(), ["delta"] = before is null || after is null ? null : Number(after) - Number(before) });
            }
            comparison.Add(new JsonObject { ["baselineBuildId"] = basis["buildId"]?.DeepClone(), ["buildId"] = current["buildId"]?.DeepClone(), ["variant"] = current["variant"]?.DeepClone(), ["metrics"] = metrics });
        }
        return comparison;
    }

    public static int TrialSeed(int seed, int index)
    {
        if (index == 0) return seed;
        unchecked { var value = (uint)seed + (uint)index * 0x9e3779b9u; value ^= value >> 16; value *= 0x85ebca6bu; value ^= value >> 13; return (int)value; }
    }
    private static double? Quantile(double[] sorted, double fraction)
    {
        if (sorted.Length == 0) return null;
        var value = fraction == .5 && sorted.Length % 2 == 0
            ? (sorted[sorted.Length / 2 - 1] + sorted[sorted.Length / 2]) / 2
            : sorted[Math.Clamp((int)Math.Ceiling(fraction * sorted.Length) - 1, 0, sorted.Length - 1)];
        return double.IsFinite(value) ? value : null;
    }
    private static int RequireInteger(JsonObject value, string key, int min, int max)
    {
        if (value[key]?.GetValueKind() != System.Text.Json.JsonValueKind.Number
            || !double.TryParse(value[key]?.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var number)
            || !double.IsFinite(number) || number != Math.Truncate(number) || number < min || number > max)
            throw new ArgumentException($"{key} は {min}〜{max} の整数で指定してください。");
        return (int)number;
    }
    private static void RequireNumber(JsonObject value, string key, double min, double max)
    {
        if (value[key]?.GetValueKind() != System.Text.Json.JsonValueKind.Number
            || !double.TryParse(value[key]?.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var number)
            || !double.IsFinite(number) || number < min || number > max)
            throw new ArgumentException($"{key} は {min}〜{max} の有限の数値で指定してください。");
    }
    private static double Number(JsonNode? value) => double.TryParse(value?.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var number) && double.IsFinite(number) ? number : 0;
}
