using System.Globalization;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>Estimates the item, upgrade, gold and experience side of a balance scenario from a frozen master catalog.</summary>
public static class BalanceEconomy
{
    private const int ExactPoolLimit = 12;
    private const int ExactQuantityLimit = 200;
    private const int ExactRollLimit = 20;
    private const string LootSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/loot/service/LootRollService.java";
    private const string MobSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobDropService.java";
    private const string OrbSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/OrbLocalMutationCalculator.java";
    private const string SellSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/sell/service/SellService.java";

    /// <summary>Computes a reproducible estimate. All input is read only and all results are derived from the supplied catalog.</summary>
    public static JsonObject Estimate(JsonObject catalog, JsonObject request, JsonObject combatSummary, CancellationToken token)
    {
        var diagnostics = new JsonArray();
        var assumptions = new JsonArray();
        var sources = new HashSet<string>(StringComparer.Ordinal) { LootSource, MobSource, OrbSource, SellSource };
        var result = new JsonObject
        {
            ["status"] = "completed", ["diagnostics"] = diagnostics, ["collection"] = new JsonObject(),
            ["enhancement"] = new JsonObject(), ["growth"] = new JsonObject(), ["gold"] = new JsonObject(),
            ["time"] = new JsonObject(), ["assumptions"] = assumptions, ["sources"] = new JsonArray()
        };
        void Issue(string code, string message, string severity = "error") => diagnostics.Add(new JsonObject
        {
            ["code"] = code, ["message"] = message, ["severity"] = severity
        });
        JsonObject Finish(string status)
        {
            result["status"] = status;
            result["sources"] = new JsonArray(sources.Order(StringComparer.Ordinal).Select(value => JsonValue.Create(value)).ToArray());
            return result;
        }

        var economy = Obj(request["economy"]);
        var enemy = Obj(request["enemy"]);
        var mobId = Ref(enemy["mobId"]);
        var targetId = Ref(economy["targetItemId"]);
        var quantity = Int(economy["quantity"], targetId.Length == 0 ? 0 : 1);
        var level = Int(enemy["level"], 1);
        var mode = Str(request["mode"], "strict");
        if (catalog["masters"] is not JsonArray masters || mobId.Length == 0 || level < 1 || quantity < 0 || quantity > 10000
            || mode is not ("strict" or "approximate"))
        {
            Issue("INVALID_REQUEST", "masters、敵ID、レベル、数量または計算モードが不正です。");
            return Finish("invalid");
        }
        if (combatSummary["successRate"] is null
            || (combatSummary["validTrials"] is not null && Int(combatSummary["validTrials"], 0) <= 0)
            || (combatSummary["totalTrials"] is not null && Int(combatSummary["totalTrials"], 0) <= 0))
        {
            Issue("COMBAT_SUMMARY_MISSING", "有効な戦闘試行と勝率がありません。");
            return Finish("unsupported");
        }
        var duplicates = masters.OfType<JsonObject>().Where(value => value["content"]?["id"] is not null)
            .GroupBy(value => Str(value["kind"]) + ":" + Str(value["content"]?["id"]), StringComparer.OrdinalIgnoreCase)
            .Where(group => group.Count() > 1).ToArray();
        if (duplicates.Length > 0)
        {
            Issue("DUPLICATE_MASTER", "マスターIDが重複しています: " + duplicates[0].Key);
            return Finish("invalid");
        }
        var lookup = new Catalog(masters);
        var recipeId = Ref(economy["recipeId"]);
        if (recipeId.Length > 0)
        {
            var recipe = lookup.Find("recipe", recipeId);
            if (recipe is null) Issue("RECIPE_MISSING", $"レシピ {recipeId} がありません。", "warning");
            else
            {
                sources.Add(lookup.Path("recipe", recipeId));
                result["recipe"] = new JsonObject { ["id"] = recipeId, ["category"] = Str(recipe["category"]),
                    ["requiredCurrency"] = Math.Max(0, Num(recipe["requiredCurrency"], 0)),
                    ["ingredients"] = recipe["ingredients"]?.DeepClone() };
                Issue(Str(recipe["category"]) == "SHOP" ? "RECIPE_SHOP_CONTEXT_REQUIRED" : "RECIPE_RUNTIME_UNSUPPORTED",
                    Str(recipe["category"]) == "SHOP"
                        ? "SHOPレシピはショップ項目への参照がある場合だけ追加費用として実行されます。ショップ項目を指定しないため総費用に含めません。"
                        : "現在のPluginは SHOP 以外のレシピを実行経路で読みません。材料と成功率を達成可能な制作経路には算入しません。", "warning");
            }
        }
        var mob = lookup.Find("mob", mobId);
        if (mob is null)
        {
            Issue("MOB_MISSING", $"敵 {mobId} のマスターがありません。");
            return Finish("unsupported");
        }
        sources.Add(lookup.Path("mob", mobId));
        var profileAssumptions = new List<string>();
        var resolvedMob = BalanceCombatData.ResolveMob(mob, level, profileAssumptions);
        foreach (var assumption in profileAssumptions) assumptions.Add(assumption);
        level = Int(resolvedMob["level"], level);
        var drops = Obj(resolvedMob["drops"]).DeepClone().AsObject();
        if (drops.Count == 0)
        {
            Issue("DROPS_MISSING", "選択レベルの敵に drops がありません。");
            return Finish("unsupported");
        }
        assumptions.Add("レベル別 drops は基礎 drops にキーごとに重ねる。配列項目は置換する。直接ドロップと各プールは独立抽選。");
        var combatStats = Obj(combatSummary["playerStats"]);
        var dropRate = Math.Max(0, Num(economy["dropRatePercent"], Num(combatStats["DROP_RATE_INCREASE"], 100)));
        var luck = Math.Max(0, Num(economy["luck"], Num(combatStats["LUCK"], 0)));
        assumptions.Add($"ドロップ補正 {dropRate}%（{(economy["dropRatePercent"] is null ? "ビルド由来" : "設定値")}）、幸運 {luck}（{(economy["luck"] is null ? "ビルド由来" : "設定値")}）。");
        var sellIds = Arr(economy["sellItemIds"]).Select(Ref).Where(id => id.Length > 0).ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (targetId.Length > 0 && quantity > 0 && lookup.Find("item", targetId) is null)
        {
            Issue("TARGET_ITEM_MISSING", $"対象アイテム {targetId} がありません。");
            return Finish("unsupported");
        }

        var rewards = new Dictionary<string, double[]>(StringComparer.OrdinalIgnoreCase);
        var sampledDrops = false;
        var ids = new HashSet<string>(sellIds, StringComparer.OrdinalIgnoreCase);
        if (targetId.Length > 0) ids.Add(targetId);
        var orbId = Ref(economy["orbItemId"]);
        if (orbId.Length > 0) ids.Add(orbId);
        foreach (var id in ids)
        {
            token.ThrowIfCancellationRequested();
            try { rewards[id] = DropDistribution(drops, id, dropRate, luck, lookup, sources, mode, Int(request["seed"], 1), token, ref sampledDrops); }
            catch (NotSupportedException e) { Issue("DROP_UNSUPPORTED", e.Message); return Finish("unsupported"); }
            catch (FormatException e) { Issue("DROP_MASTER_INVALID", e.Message); return Finish("invalid"); }
        }
        var victoryRate = Math.Clamp(Num(combatSummary["successRate"], 1), 0, 1);
        if (victoryRate <= 0)
        {
            Issue("NO_VICTORIES", "戦闘試行に勝利がなく、報酬を得られません。");
            return Finish("unreachable");
        }
        foreach (var id in rewards.Keys.ToArray()) rewards[id] = MixVictory(rewards[id], victoryRate);
        assumptions.Add("勝率は各戦闘試行で独立とし、敗北・時間切れではドロップと経験値を得ない。");
        assumptions.Add("討伐報酬の受取資格があり、AFKではなく、通常育成・転生なしのプレイヤーを想定する。");
        if (Int(combatSummary["deaths"], 0) > 0)
            Issue("DEATH_EXP_PENALTY_UNMODELED", "死亡時の経験値損失は成長見積りに含めていません。", "warning");

        double expectedAttempts = 0;
        if (targetId.Length > 0 && quantity > 0)
        {
            if (!rewards.TryGetValue(targetId, out var distribution) || distribution.Skip(1).Sum() <= 0)
            {
                Issue("TARGET_UNREACHABLE", $"敵 {mobId} から {targetId} を取得できません。");
                return Finish("unreachable");
            }
            if (quantity > ExactQuantityLimit && mode == "strict")
            {
                Issue("QUANTITY_UNSUPPORTED", $"厳密分布の数量上限は {ExactQuantityLimit} です。");
                return Finish("unsupported");
            }
            (double Mean, int Median, int P90, string Method) statistics;
            try { statistics = quantity <= ExactQuantityLimit
                ? CollectionStatistics(distribution, quantity, token)
                : ApproximateCollection(distribution, quantity); }
            catch (NotSupportedException e) { Issue("COLLECTION_TAIL_UNSUPPORTED", e.Message); return Finish("unsupported"); }
            expectedAttempts = statistics.Mean;
            result["collection"] = new JsonObject
            {
                ["itemId"] = targetId, ["quantity"] = quantity, ["expectedItemsPerKill"] = Expected(distribution),
                ["expectedKills"] = statistics.Mean, ["medianKills"] = statistics.Median,
                ["p90Kills"] = statistics.P90,
                ["method"] = sampledDrops ? (statistics.Method == "exact" ? "sampled" : "sampled-normal-approximation") : statistics.Method
            };
            if (statistics.Method != "exact") Issue("COLLECTION_APPROXIMATE", "大きい数量の収集分位は正規近似です。", "warning");
        }
        if (sampledDrops) Issue("DROP_SAMPLED", "大きいルートプールは標本抽選から分布を推定しました。", "warning");

        var enhancement = EstimateEnhancement(economy, lookup, sources, Issue, token);
        if (enhancement.Status is "unsupported" or "unreachable" or "invalid") return Finish(enhancement.Status);
        result["enhancement"] = enhancement.Value;
        var upgradeAttempts = Num(enhancement.Value["expectedAttempts"], 0);
        var stockImpossible = false;
        if (upgradeAttempts > 0 && targetId.Length == 0)
            assumptions.Add("強化対象の装備はすでに所持している。収集戦闘の時間・収入は0として表示する。強化操作そのものの所要時間は未算入。");
        if (upgradeAttempts > 0 && orbId.Length > 0)
        {
            var orbPerKill = rewards.TryGetValue(orbId, out var orbRewards) ? Expected(orbRewards) : 0;
            enhancement.Value["expectedOrbDropsPerKill"] = orbPerKill;
            if (orbPerKill > 0)
                enhancement.Value["estimatedKillsForExpectedOrbs"] = upgradeAttempts / orbPerKill;
            if (orbId.Equals(targetId, StringComparison.OrdinalIgnoreCase) && quantity > 0)
            {
                enhancement.Value["targetQuantityIsOrbStock"] = true;
                var owned = Math.Max(0, Int(economy["orbOwned"], 0));
                var minimum = Math.Max(0, Int(enhancement.Value["targetLevel"], 0) - Int(enhancement.Value["fromLevel"], 0));
                stockImpossible = quantity + owned < minimum;
                if (stockImpossible)
                    Issue("ORB_STOCK_UNREACHABLE", "収集個数と所持オーブ数の合計が最小必要試行数より少なく、この計画の強化は完了できません。");
                if (enhancement.Value["p90Attempts"] is JsonNode p90 && quantity + owned < Int(p90, 0))
                    Issue("ORB_STOCK_BELOW_P90", "収集目標のオーブ個数は強化試行数のp90未満です。途中で不足する場合があります。", "warning");
            }
            if (!orbId.Equals(targetId, StringComparison.OrdinalIgnoreCase)
                && Int(economy["orbOwned"], 0) < Int(enhancement.Value["p90Attempts"], int.MaxValue))
                Issue("ORB_SUPPLY_SEPARATE", orbPerKill > 0
                    ? "オーブの必要試行数は乱数で変動し、対象品と同時ドロップし得ます。オーブ調達戦闘は総時間に加えていません。"
                    : "敵のドロップから強化オーブを調達できません。所持品・ショップなど別の入手元が必要です。", "warning");
        }

        var baseExperience = Math.Max(0, Int(drops["exp"], 0));
        var experienceRate = Math.Max(0, Num(combatStats["EXPERIENCE_GAIN_RATE"], 100));
        var growth = EstimateGrowth(economy, request, lookup, baseExperience, level, experienceRate, victoryRate, expectedAttempts, Issue);
        result["growth"] = growth;
        assumptions.Add("育成経路はLv上昇ごとに敵とのレベル差によるEXP補正を更新します。装備・経験値率と戦闘性能・討伐率は今回のビルドに固定した予測です。");
        var money = Obj(drops["money"]);
        var moneyMean = money.Count == 0 ? 0 : (Num(money["min"], 0) + Num(money["max"], 0)) / 2.0;
        var salePerAttempt = 0.0;
        var saleIncomplete = false;
        var goldLines = new JsonArray();
        foreach (var id in sellIds)
        {
            if (id.Equals(targetId, StringComparison.OrdinalIgnoreCase))
            {
                Issue("SELLS_TARGET", "収集対象を売却対象にも指定しています。収集分は売らない前提です。", "warning");
                saleIncomplete = true;
                continue;
            }
            var item = lookup.Find("item", id);
            if (item is null) { Issue("SELL_ITEM_MISSING", $"売却品 {id} がありません。", "warning"); saleIncomplete = true; continue; }
            if (Bool(item["unSellable"])) { Issue("UNSELLABLE_ITEM", $"{id} は売却不可です。", "warning"); saleIncomplete = true; continue; }
            var value = Math.Max(0, Num(item["saleValue"], 0));
            var amount = Expected(rewards[id]);
            salePerAttempt += amount * value;
            goldLines.Add(new JsonObject { ["type"] = "sale", ["itemId"] = id, ["expectedAmountPerKill"] = amount,
                ["unitGold"] = value, ["expectedGoldPerKill"] = amount * value, ["basis"] = "estimatedDropAndSellAll" });
            sources.Add(lookup.Path("item", id));
        }
        var unknownUpgradeCost = upgradeAttempts > 0 && enhancement.Value["goldPerAttempt"] is null;
        var orbGold = Num(enhancement.Value["goldPerAttempt"], 0);
        var directGold = moneyMean * victoryRate * expectedAttempts;
        var soldGold = salePerAttempt * expectedAttempts;
        var spentGold = orbGold * upgradeAttempts;
        if (money.Count > 0) goldLines.Add(new JsonObject { ["type"] = "mobMoney", ["expectedGoldPerVictory"] = moneyMean,
            ["expectedGold"] = directGold, ["basis"] = "estimatedWinningRewards" });
        if (spentGold > 0) goldLines.Add(new JsonObject { ["type"] = "enhancement", ["expectedAttempts"] = upgradeAttempts,
            ["goldPerAttempt"] = orbGold, ["expectedGold"] = -spentGold, ["basis"] = "masterOrbChargeSaleValue" });
        result["gold"] = new JsonObject { ["income"] = directGold + soldGold, ["expense"] = unknownUpgradeCost ? null : spentGold,
            ["net"] = unknownUpgradeCost ? null : directGold + soldGold - spentGold, ["lines"] = goldLines,
            ["scope"] = "directGoldOnly", ["completeness"] = upgradeAttempts > 0 || recipeId.Length > 0 || saleIncomplete ? "partial" : "estimated",
            ["excludedCosts"] = upgradeAttempts > 0 ? new JsonArray("orbProcurement", "equipmentProcurement") : new JsonArray() };
        assumptions.Add("売却指定品は入手した全量を売る。所持枠、装備個体の取引可否、売却GUIへの搬入時間は含めない。");
        var killSeconds = Math.Max(0, Num(economy["killSeconds"], Num(combatSummary["meanElapsedSeconds"], 0)));
        var hasTimeSource = economy["killSeconds"] is not null || combatSummary["meanElapsedSeconds"] is not null;
        if (!hasTimeSource && expectedAttempts > 0) Issue("COMBAT_TIME_MISSING", "戦闘時間がないため総時間は未確定です。", "warning");
        var moveSeconds = Math.Max(0, Num(economy["moveSeconds"], 0)) * expectedAttempts;
        var waitSeconds = Math.Max(0, Num(economy["waitSeconds"], 0)) * expectedAttempts;
        var resupplySeconds = Math.Max(0, Num(economy["resupplySeconds"], 0));
        var combatSeconds = killSeconds * expectedAttempts;
        var totalSeconds = combatSeconds + moveSeconds + waitSeconds + resupplySeconds;
        result["time"] = new JsonObject { ["combatSeconds"] = combatSeconds, ["moveSeconds"] = moveSeconds, ["waitSeconds"] = waitSeconds,
            ["resupplySeconds"] = resupplySeconds, ["totalSeconds"] = totalSeconds,
            ["completeness"] = hasTimeSource && (expectedAttempts > 0 || upgradeAttempts <= 0) ? "estimated" : "partial" };
        var maxSeconds = Num(economy["timeBudgetSeconds"], 0);
        if (maxSeconds > 0)
        {
            if (totalSeconds > maxSeconds) Issue("TIME_BUDGET_EXCEEDED", "期待所要時間が指定された上限を超えます。", "warning");
            if (targetId.Length > 0 && quantity > 0 && rewards.TryGetValue(targetId, out var distribution) && quantity <= ExactQuantityLimit)
            {
                var perAttempt = killSeconds + Math.Max(0, Num(economy["moveSeconds"], 0)) + Math.Max(0, Num(economy["waitSeconds"], 0));
                if (perAttempt > 0)
                {
                    var possibleAttempts = Math.Max(0, (int)Math.Floor((maxSeconds - resupplySeconds) / perAttempt));
                    Obj(result["collection"])["probabilityWithinBudget"] = CollectionCdf(distribution, quantity, possibleAttempts, token);
                }
            }
        }
        return Finish(stockImpossible ? "unreachable" : "completed");
    }

    private static JsonObject EstimateGrowth(JsonObject economy, JsonObject request, Catalog lookup, int baseExperience, int mobLevel, double experienceRate, double victoryRate, double expectedAttempts,
        Action<string, string, string> issue)
    {
        var build = Arr(request["builds"]).FirstOrDefault() as JsonObject;
        var playerLevel = Int(economy["playerLevel"], Int(build?["playerLevel"], 1));
        var expPerVictory = BalanceGrowthRewards.ExperiencePerVictory(baseExperience, playerLevel, mobLevel, experienceRate);
        var expPerAttempt = expPerVictory * victoryRate;
        var classId = Ref(economy["classId"] ?? build?["classId"]);
        var buildClassId = Ref(build?["classId"]);
        var classLevel = Int(economy["classLevel"], buildClassId.Equals(classId, StringComparison.OrdinalIgnoreCase)
            ? Int(build?["classLevel"], 1) : 1);
        var output = new JsonObject { ["experiencePerVictory"] = expPerVictory, ["expectedExperiencePerKill"] = expPerAttempt,
            ["expectedExperience"] = expPerAttempt * expectedAttempts, ["playerLevel"] = playerLevel, ["classLevel"] = classLevel };
        var uuidText = Str(economy["accountUuid"], "00000000-0000-0000-0000-000000000001");
        if (!Guid.TryParse(uuidText, out var uuid) || uuid == Guid.Empty) { issue("UUID_INVALID", "アカウントUUIDが不正です。", "warning"); return output; }
        var targetPlayer = Int(economy["targetPlayerLevel"], playerLevel);
        if (targetPlayer > 200 || playerLevel < 1 || playerLevel > 200)
            issue("PLAYER_LEVEL_UNSUPPORTED", "通常育成曲線はプレイヤーLv1〜200まで対応します。", "warning");
        else if (targetPlayer > playerLevel)
        {
            long needed = 0;
            for (var level = playerLevel; level < targetPlayer; level++) needed += MasterAnalyticsService.PlayerExperienceForNextLevel(uuid, level);
            output["playerExperienceNeeded"] = needed;
            var wins = BalanceGrowthRewards.WinningKillsToPlayerLevel(uuid, playerLevel, targetPlayer, baseExperience, mobLevel, experienceRate);
            output["expectedKillsToPlayerLevel"] = double.IsFinite(wins) ? wins / victoryRate : null;
            if (expPerAttempt <= 0) issue("PLAYER_EXP_UNREACHABLE", "この敵からプレイヤー経験値を得られません。", "warning");
        }
        var master = lookup.Find("class", classId);
        if (master is not null)
        {
            var activeClassId = buildClassId.Length > 0 ? buildClassId : classId;
            var unlockPlayer = Int(master["unlockLevel"], 1);
            var alreadyActive = activeClassId.Equals(classId, StringComparison.OrdinalIgnoreCase);
            var prerequisites = new JsonArray();
            var progress = Obj(economy["classProgress"]);
            var hasUnknownPrerequisite = false;
            var hasMissingPrerequisite = false;
            foreach (var requirement in Arr(master["unlockClassLevel"]).Select(Obj))
            {
                var requiredId = Ref(requirement["class"] ?? requirement["classId"]);
                var requiredLevel = Int(requirement["level"], 1);
                var actual = progress[requiredId] is null ? (int?)null : Int(progress[requiredId], 0);
                prerequisites.Add(new JsonObject { ["classId"] = requiredId, ["requiredLevel"] = requiredLevel, ["actualLevel"] = actual });
                if (actual is null) hasUnknownPrerequisite = true;
                else if (actual < requiredLevel) hasMissingPrerequisite = true;
            }
            var adminOnly = Bool(master["adminChangeOnly"]);
            var commandOnly = Bool(master["commandOnly"]);
            output["classPath"] = new JsonObject { ["currentClassId"] = activeClassId, ["targetClassId"] = classId,
                ["unlockPlayerLevel"] = unlockPlayer, ["adminChangeOnly"] = adminOnly, ["commandOnly"] = commandOnly,
                ["prerequisites"] = prerequisites,
                ["canSelectNow"] = alreadyActive || (!adminOnly && !commandOnly && playerLevel >= unlockPlayer && !hasUnknownPrerequisite && !hasMissingPrerequisite) };
            if (!alreadyActive)
            {
                if (adminOnly || commandOnly) issue("CLASS_SELECTION_RESTRICTED", $"{classId} は通常の職業変更で選択できません。", "warning");
                if (playerLevel < unlockPlayer) issue("CLASS_PLAYER_LEVEL_LOCKED", $"{classId} はプレイヤーLv.{unlockPlayer}で解放されます。", "warning");
                if (hasUnknownPrerequisite) issue("CLASS_PREREQUISITES_UNVERIFIED", $"{classId} の前提職レベルは入力に履歴がないため検証できません。", "warning");
                if (hasMissingPrerequisite) issue("CLASS_PREREQUISITES_MISSING", $"{classId} の前提職レベルが不足しています。", "warning");
            }
        }
        var targetClass = Int(economy["targetClassLevel"], classLevel);
        if (targetClass > classLevel && master is not null)
        {
            var max = Int(master["maxLevel"], 100);
            if (targetClass > max) issue("CLASS_LEVEL_UNREACHABLE", $"{classId} の上限Lvは {max} です。", "warning");
            else
            {
                long needed = 0;
                for (var level = classLevel; level < targetClass; level++) needed += MasterAnalyticsService.ClassExperienceForNextLevel(level, Int(master["expRate"], 100));
                output["classExperienceNeeded"] = needed;
                var wins = BalanceGrowthRewards.WinningKillsForExperience(uuid, playerLevel, needed, baseExperience, mobLevel, experienceRate);
                output["expectedKillsToClassLevel"] = double.IsFinite(wins) ? wins / victoryRate : null;
                if (expPerAttempt <= 0) issue("CLASS_EXP_UNREACHABLE", "この敵からクラス経験値を得られません。", "warning");
            }
        }
        else if (targetClass > classLevel) issue("CLASS_MISSING", $"職業 {classId} がありません。", "warning");
        return output;
    }

    private static (string Status, JsonObject Value) EstimateEnhancement(JsonObject economy, Catalog lookup, HashSet<string> sources,
        Action<string, string, string> issue, CancellationToken token)
    {
        var itemId = Ref(economy["enhanceItemId"]);
        if (itemId.Length == 0) return ("completed", new JsonObject());
        var item = lookup.Find("item", itemId);
        if (item is null) { issue("EQUIPMENT_MISSING", $"装備 {itemId} がありません。", "error"); return ("unsupported", new JsonObject()); }
        var equipment = Obj(item["equipment"]);
        var enhance = Obj(equipment["enhance"]);
        var start = Int(economy["fromEnhanceLevel"], 0);
        var target = Int(economy["targetEnhanceLevel"], start);
        if (start < 0 || target < start) { issue("ENHANCE_LEVEL_INVALID", "強化レベルの範囲が不正です。", "error"); return ("invalid", new JsonObject()); }
        var rank = Math.Max(0, Int(economy["transcendenceRank"], 0));
        var maxLevel = Int(enhance["maxLevel"], 0);
        foreach (var stage in Arr(equipment["transcendence"]).Select(Obj).OrderBy(stage => Int(stage["rank"], 0)))
            if (Int(stage["rank"], 0) <= rank && stage["overrides"]?["enhance"] is JsonObject replacement)
            {
                enhance = enhance.DeepClone().AsObject();
                Merge(enhance, replacement);
                maxLevel = Int(replacement["maxLevel"], maxLevel);
            }
        if (target > maxLevel) { issue("ENHANCE_CAP", "強化目標が装備の有効上限を超えています。", "error"); return ("unreachable", new JsonObject()); }
        if (target > 200) { issue("ENHANCE_SIZE_UNSUPPORTED", "強化分布は目標Lv200まで対応します。", "error"); return ("unsupported", new JsonObject()); }
        if (target == start) return ("completed", new JsonObject { ["itemId"] = itemId, ["expectedAttempts"] = 0, ["medianAttempts"] = 0, ["p90Attempts"] = 0 });
        var definitions = Arr(enhance["levels"]).Select(Obj).ToDictionary(x => Int(x["level"], -1));
        var transitions = new Dictionary<int, (double Success, int Failure)>();
        for (var current = 0; current < target; current++)
        {
            if (!definitions.TryGetValue(current + 1, out var definition))
            {
                issue("ENHANCE_LEVEL_MISSING", $"{itemId} の強化Lv.{current + 1} が未定義です。", "error");
                return ("unsupported", new JsonObject());
            }
            var success = Math.Clamp(Num(definition["successRate"], 1), 0, 1);
            var action = Str(definition["failAction"], "NONE");
            var failure = action switch
            {
                "NONE" => current, "DECREASE_ONE" => Math.Max(0, current - 1),
                "SET_LEVEL" => Math.Min(target, Math.Clamp(Int(definition["failTargetLevel"], current), 0, maxLevel)),
                _ => -1
            };
            if (failure < 0)
            {
                issue("ENHANCE_FAIL_ACTION", $"{itemId} Lv.{current + 1} の失敗挙動を計算できません。", "error");
                return ("unsupported", new JsonObject());
            }
            transitions[current] = (success, failure);
        }
        if (!CanReachUpgradeTarget(transitions, start, target))
        {
            issue("ENHANCE_ZERO_RATE", "成功率0の強化段階があり、目標へ到達できません。", "error");
            return ("unreachable", new JsonObject());
        }
        var mean = UpgradeMean(transitions, start, target);
        if (!double.IsFinite(mean))
        {
            issue("ENHANCE_TRAP", "失敗後に成功不能な状態へ入る可能性があり、到達期待値が有限ではありません。", "error");
            return ("unreachable", new JsonObject());
        }
        var (median, p90) = UpgradeQuantiles(transitions, start, target, token);
        if (p90 == 1_000_000 && UpgradeCdf(transitions, start, target, p90, token) < 0.9 - 1e-12)
        {
            issue("ENHANCE_TAIL_UNSUPPORTED", "強化試行数のp90が100万回を超えます。", "error");
            return ("unsupported", new JsonObject());
        }
        var orbId = Ref(economy["orbItemId"]);
        double gold = 0;
        if (orbId.Length > 0)
        {
            var orb = lookup.Find("item", orbId);
            if (orb is null) { issue("ORB_MISSING", $"オーブ {orbId} がありません。", "error"); return ("unsupported", new JsonObject()); }
            var effect = Obj(Obj(orb["orb"])["effect"]);
            var slots = Arr(effect["targetSlots"]).Select(value => Str(value)).ToHashSet(StringComparer.OrdinalIgnoreCase);
            if (Str(effect["type"]) != "ENHANCE" || !slots.Contains(Str(equipment["slot"])))
            {
                issue("ORB_INELIGIBLE", "オーブが装備スロットの強化に対応しません。", "error");
                return ("unsupported", new JsonObject());
            }
            if (effect["rank"] is not null)
            {
                var requiredRank = Int(effect["rank"], 0);
                var eligible = Str(effect["rankMode"], "EXACT") == "AT_MOST" ? rank <= requiredRank : rank == requiredRank;
                if (!eligible) { issue("ORB_RANK_INELIGIBLE", "オーブが指定された超越ランクに対応しません。", "error"); return ("unsupported", new JsonObject()); }
            }
            gold = Bool(effect["chargeSaleValue"]) ? Math.Max(0, Num(orb["saleValue"], 0)) : 0;
            sources.Add(lookup.Path("item", orbId));
        }
        else issue("ORB_UNSPECIFIED", "強化オーブ未指定のため適格性とゴールド費用は未確定です。", "warning");
        sources.Add(lookup.Path("item", itemId));
        return ("completed", new JsonObject { ["itemId"] = itemId, ["fromLevel"] = start, ["targetLevel"] = target,
            ["orbItemId"] = orbId, ["expectedAttempts"] = mean, ["medianAttempts"] = median, ["p90Attempts"] = p90,
            ["expectedOrbs"] = mean, ["orbStockCompletionProbability"] =
                orbId.Length > 0 && orbId.Equals(Ref(economy["targetItemId"]), StringComparison.OrdinalIgnoreCase) && Int(economy["quantity"], 0) > 0
                    ? UpgradeCdf(transitions, start, target, Int(economy["quantity"], 0) + Math.Max(0, Int(economy["orbOwned"], 0)), token) : null,
            ["goldPerAttempt"] = orbId.Length == 0 ? null : gold, ["expectedGold"] = orbId.Length == 0 ? null : mean * gold });
    }

    private static double UpgradeMean(Dictionary<int, (double Success, int Failure)> transitions, int start, int target)
    {
        var reachable = ReachableUpgradeStates(transitions, start, target).Where(level => level < target).Order().ToArray();
        var indices = reachable.Select((level, index) => (level, index)).ToDictionary(value => value.level, value => value.index);
        var size = reachable.Length;
        var a = new double[size, size + 1];
        for (var i = 0; i < size; i++)
        {
            a[i, i] = 1;
            var level = reachable[i];
            var (success, failure) = transitions[level];
            if (success > 0 && level + 1 < target) a[i, indices[level + 1]] -= success;
            if (success < 1 && failure < target) a[i, indices[failure]] -= 1 - success;
            a[i, size] = 1;
        }
        for (var col = 0; col < size; col++)
        {
            var pivot = Enumerable.Range(col, size - col).OrderByDescending(row => Math.Abs(a[row, col])).First();
            if (Math.Abs(a[pivot, col]) < 1e-12) return double.PositiveInfinity;
            for (var k = col; k <= size; k++) (a[col, k], a[pivot, k]) = (a[pivot, k], a[col, k]);
            var value = a[col, col];
            for (var k = col; k <= size; k++) a[col, k] /= value;
            for (var row = 0; row < size; row++)
            {
                if (row == col) continue;
                var factor = a[row, col];
                for (var k = col; k <= size; k++) a[row, k] -= factor * a[col, k];
            }
        }
        return a[indices[start], size];
    }

    private static bool CanReachUpgradeTarget(Dictionary<int, (double Success, int Failure)> transitions, int start, int target)
        => ReachableUpgradeStates(transitions, start, target).Contains(target);

    private static HashSet<int> ReachableUpgradeStates(Dictionary<int, (double Success, int Failure)> transitions, int start, int target)
    {
        var visited = new HashSet<int> { start };
        var queue = new Queue<int>(); queue.Enqueue(start);
        while (queue.Count > 0)
        {
            var current = queue.Dequeue();
            if (current >= target) continue;
            var (success, failure) = transitions[current];
            if (success > 0 && visited.Add(current + 1)) queue.Enqueue(current + 1);
            if (success < 1 && visited.Add(failure)) queue.Enqueue(failure);
        }
        return visited;
    }

    private static (int Median, int P90) UpgradeQuantiles(Dictionary<int, (double Success, int Failure)> transitions, int start, int target, CancellationToken token)
    {
        var state = new double[target + 1]; state[start] = 1;
        var median = 0;
        for (var attempt = 1; attempt <= 1_000_000; attempt++)
        {
            if (attempt % 100 == 0) token.ThrowIfCancellationRequested();
            var next = new double[target + 1]; next[target] = state[target];
            for (var level = 0; level < target; level++)
            {
                var (success, failure) = transitions[level];
                next[level + 1] += state[level] * success;
                next[failure] += state[level] * (1 - success);
            }
            state = next;
            if (median == 0 && state[target] >= 0.5 - 1e-12) median = attempt;
            if (state[target] >= 0.9 - 1e-12) return (median, attempt);
        }
        return (median, 1_000_000);
    }

    private static double UpgradeCdf(Dictionary<int, (double Success, int Failure)> transitions, int start, int target, int attempts, CancellationToken token)
    {
        var state = new double[target + 1]; state[start] = 1;
        for (var attempt = 0; attempt < Math.Min(attempts, 1_000_000); attempt++)
        {
            if (attempt % 100 == 0) token.ThrowIfCancellationRequested();
            var next = new double[target + 1]; next[target] = state[target];
            for (var level = 0; level < target; level++)
            {
                var (success, failure) = transitions[level];
                next[level + 1] += state[level] * success;
                next[failure] += state[level] * (1 - success);
            }
            state = next;
        }
        return state[target];
    }

    private static double[] DropDistribution(JsonObject drops, string itemId, double dropRate, double luck, Catalog lookup,
        HashSet<string> sources, string mode, int seed, CancellationToken token, ref bool sampled)
    {
        var pmf = new[] { 1.0 };
        foreach (var entry in Arr(drops["items"]).Select(Obj))
        {
            token.ThrowIfCancellationRequested();
            if (!Ref(entry["itemId"]).Equals(itemId, StringComparison.OrdinalIgnoreCase)) continue;
            var multiplier = entry["luckAffected"] is null || Bool(entry["luckAffected"]) ? 1 + luck / 100 : 1;
            var probability = Math.Clamp(Num(entry["rate"], 0) * multiplier * dropRate / 10000, 0, 1);
            pmf = ConvolveDrop(pmf, BernoulliAmount(probability, Amounts(entry["amount"])));
        }
        var tableId = Ref(drops["lootTable"]);
        if (tableId.Length == 0) return pmf;
        var table = lookup.Find("loot_table", tableId) ?? throw new FormatException($"ルートテーブル {tableId} がありません。");
        sources.Add(lookup.Path("loot_table", tableId));
        var (minRolls, maxRolls) = Range(table["rolls"], 1);
        if (maxRolls > ExactRollLimit) throw new NotSupportedException($"テーブル {tableId} の rolls が計算上限 {ExactRollLimit} を超えます。");
        var perRoll = new[] { 1.0 };
        foreach (var reference in Arr(table["pools"]))
        {
            var poolId = Ref(reference);
            var pool = lookup.Find("loot_pool", poolId) ?? throw new FormatException($"ルートプール {poolId} がありません。");
            sources.Add(lookup.Path("loot_pool", poolId));
            perRoll = ConvolveDrop(perRoll, PoolDistribution(pool, itemId, dropRate, mode, seed, token, ref sampled));
        }
        var tablePmf = new double[] { 0 };
        var power = new[] { 1.0 };
        for (var roll = 0; roll <= maxRolls; roll++)
        {
            if (roll >= minRolls) AddScaled(ref tablePmf, power, 1.0 / (maxRolls - minRolls + 1));
            if (roll < maxRolls) power = ConvolveDrop(power, perRoll);
        }
        return ConvolveDrop(pmf, tablePmf);
    }

    private static double[] PoolDistribution(JsonObject pool, string itemId, double dropRate, string mode, int seed, CancellationToken token, ref bool sampled)
    {
        var contents = Arr(pool["contents"]).Select(Obj).ToArray();
        if (contents.Length == 0) return [1];
        var (minPick, maxPick) = Range(pool["pick"], 1);
        if (contents.Length > ExactPoolLimit)
        {
            if (mode == "strict") throw new NotSupportedException($"プール {Str(pool["id"])} は {ExactPoolLimit} 行を超え、厳密なpick分布を作れません。");
            if (contents.Where(content => Ref(content["itemId"]).Equals(itemId, StringComparison.OrdinalIgnoreCase))
                    .Sum(content => Amounts(content["amount"]).Max) > ExactQuantityLimit)
                throw new NotSupportedException("プールで一度に得る対象品が数量分布上限を超えます。");
            sampled = true;
            return SamplePool(contents, itemId, dropRate, minPick, maxPick, seed, token);
        }
        var rates = contents.Select(item => Math.Clamp(Num(item["rate"], 0) * dropRate / 10000, 0, 1)).ToArray();
        var result = new double[] { 0 };
        var subsets = 1 << contents.Length;
        for (var mask = 0; mask < subsets; mask++)
        {
            token.ThrowIfCancellationRequested();
            var probability = 1.0;
            var selected = new List<int>();
            for (var i = 0; i < contents.Length; i++)
            {
                var hit = (mask & (1 << i)) != 0;
                probability *= hit ? rates[i] : 1 - rates[i];
                if (hit) selected.Add(i);
            }
            if (probability <= 0) continue;
            // Every pick >= successful rows retains all rows. Collapse that identical tail.
            for (var pick = Math.Min(minPick, selected.Count); pick <= Math.Min(maxPick, selected.Count); pick++)
            {
                token.ThrowIfCancellationRequested();
                var count = Math.Min(Math.Max(0, pick), selected.Count);
                var combinations = Combinations(selected, count).ToArray();
                var weight = pick == selected.Count ? maxPick - Math.Max(minPick, selected.Count) + 1 : 1;
                var selectionProbability = probability * weight / (maxPick - minPick + 1) / combinations.Length;
                foreach (var chosen in combinations)
                {
                    token.ThrowIfCancellationRequested();
                    var amountPmf = new[] { 1.0 };
                    foreach (var index in chosen.Where(index => Ref(contents[index]["itemId"]).Equals(itemId, StringComparison.OrdinalIgnoreCase)))
                        amountPmf = ConvolveDrop(amountPmf, AmountDistribution(Amounts(contents[index]["amount"])));
                    AddScaled(ref result, amountPmf, selectionProbability);
                }
            }
        }
        return result;
    }

    private static double[] SamplePool(JsonObject[] contents, string itemId, double dropRate, int minPick, int maxPick, int seed, CancellationToken token)
    {
        var stable = 17;
        foreach (var c in Str(contents[0]["itemId"])) stable = unchecked(stable * 31 + c);
        var rng = new Random(seed ^ stable);
        var result = new double[ExactQuantityLimit + 1];
        const int samples = 20000;
        for (var sample = 0; sample < samples; sample++)
        {
            if (sample % 512 == 0) token.ThrowIfCancellationRequested();
            var selected = Enumerable.Range(0, contents.Length)
                .Where(i => rng.NextDouble() < Math.Clamp(Num(contents[i]["rate"], 0) * dropRate / 10000, 0, 1)).ToArray();
            var pick = rng.Next(minPick, maxPick + 1);
            for (var i = 0; i < Math.Min(pick, selected.Length); i++)
            {
                var index = rng.Next(i, selected.Length);
                (selected[i], selected[index]) = (selected[index], selected[i]);
            }
            var amount = 0;
            foreach (var index in selected.Take(Math.Min(pick, selected.Length)))
                if (Ref(contents[index]["itemId"]).Equals(itemId, StringComparison.OrdinalIgnoreCase))
                {
                    var (low, high) = Amounts(contents[index]["amount"]);
                    amount += rng.Next(low, high + 1);
                }
            result[Math.Min(ExactQuantityLimit, amount)] += 1.0 / samples;
        }
        var last = Array.FindLastIndex(result, value => value > 0);
        return result[..(Math.Max(0, last) + 1)];
    }

    private static IEnumerable<int[]> Combinations(List<int> values, int choose)
    {
        if (choose == 0) { yield return []; yield break; }
        for (var i = 0; i <= values.Count - choose; i++)
            foreach (var suffix in Combinations(values.Skip(i + 1).ToList(), choose - 1))
                yield return [values[i], ..suffix];
    }

    private static (double Mean, int Median, int P90, string Method) CollectionStatistics(double[] pmf, int quantity, CancellationToken token)
    {
        var p0 = pmf[0];
        var expected = new double[quantity + 1];
        for (var remaining = 1; remaining <= quantity; remaining++)
        {
            var numerator = 1.0;
            for (var amount = 1; amount < pmf.Length && amount < remaining; amount++) numerator += pmf[amount] * expected[remaining - amount];
            expected[remaining] = numerator / (1 - p0);
        }
        var median = CollectionQuantile(pmf, quantity, 0.5, token);
        var p90 = CollectionQuantile(pmf, quantity, 0.9, token);
        return (expected[quantity], median, p90, "exact");
    }

    private static (double Mean, int Median, int P90, string Method) ApproximateCollection(double[] pmf, int quantity)
    {
        var mean = Expected(pmf);
        var variance = pmf.Select((p, x) => p * (x - mean) * (x - mean)).Sum();
        var attempts = quantity / mean;
        var spread = Math.Sqrt(Math.Max(0, quantity * variance / (mean * mean * mean)));
        return (attempts, Math.Max(1, (int)Math.Ceiling(attempts)), Math.Max(1, (int)Math.Ceiling(attempts + 1.281551565545 * spread)), "normal-approximation");
    }

    private static int CollectionQuantile(double[] pmf, int quantity, double threshold, CancellationToken token)
    {
        var upper = 1;
        while (CollectionCdf(pmf, quantity, upper, token) < threshold && upper < (1 << 30)) upper *= 2;
        if (CollectionCdf(pmf, quantity, upper, token) < threshold)
            throw new NotSupportedException("目標収集の分位点が計算可能な戦闘回数を超えます。");
        var lower = upper / 2;
        while (lower + 1 < upper)
        {
            var middle = lower + (upper - lower) / 2;
            if (CollectionCdf(pmf, quantity, middle, token) >= threshold) upper = middle;
            else lower = middle;
        }
        return upper;
    }

    private static double CollectionCdf(double[] pmf, int quantity, int attempts, CancellationToken token)
    {
        var power = new[] { 1.0 };
        var basePmf = pmf;
        var count = attempts;
        while (count > 0)
        {
            token.ThrowIfCancellationRequested();
            if ((count & 1) != 0) power = ConvolveBelow(power, basePmf, quantity);
            count >>= 1;
            if (count > 0) basePmf = ConvolveBelow(basePmf, basePmf, quantity);
        }
        return Math.Clamp(1 - power.Sum(), 0, 1);
    }

    private static double[] MixVictory(double[] pmf, double victory)
    {
        var result = pmf.Select(p => p * victory).ToArray();
        result[0] += 1 - victory;
        return result;
    }

    private static double[] BernoulliAmount(double probability, (int Min, int Max) range)
    {
        var result = AmountDistribution(range);
        for (var i = 0; i < result.Length; i++) result[i] *= probability;
        result[0] += 1 - probability;
        return result;
    }

    private static double[] AmountDistribution((int Min, int Max) range)
    {
        var result = new double[Math.Min(ExactQuantityLimit, range.Max) + 1];
        for (var i = range.Min; i <= range.Max; i++) result[Math.Min(i, ExactQuantityLimit)] += 1.0 / (range.Max - range.Min + 1);
        return result;
    }

    private static double[] Convolve(double[] first, double[] second, int cap)
    {
        var result = new double[Math.Min(cap, first.Length + second.Length - 2) + 1];
        for (var i = 0; i < first.Length; i++)
            for (var j = 0; j < second.Length; j++)
                result[Math.Min(cap, i + j)] += first[i] * second[j];
        return result;
    }

    private static double[] ConvolveDrop(double[] first, double[] second)
    {
        if (first.Length + second.Length - 2 > ExactQuantityLimit)
            throw new NotSupportedException($"1戦闘の対象品数量が分布計算上限 {ExactQuantityLimit} を超えます。");
        return Convolve(first, second, ExactQuantityLimit);
    }

    private static double[] ConvolveBelow(double[] first, double[] second, int exclusiveLimit)
    {
        var result = new double[Math.Min(exclusiveLimit, first.Length + second.Length - 1)];
        for (var i = 0; i < first.Length && i < exclusiveLimit; i++)
            for (var j = 0; j < second.Length && i + j < exclusiveLimit; j++)
                result[i + j] += first[i] * second[j];
        return result;
    }

    private static void AddScaled(ref double[] destination, double[] source, double scale)
    {
        if (destination.Length < source.Length) Array.Resize(ref destination, source.Length);
        for (var i = 0; i < source.Length; i++) destination[i] += source[i] * scale;
    }

    private static double Expected(double[] pmf) => pmf.Select((probability, amount) => probability * amount).Sum();
    private static (int Min, int Max) Amounts(JsonNode? node)
    {
        var range = Range(node, 1);
        if (range.Max > ExactQuantityLimit)
            throw new NotSupportedException($"一度に得る数量 {range.Max} が分布計算上限 {ExactQuantityLimit} を超えます。");
        return range;
    }
    private static (int Min, int Max) Range(JsonNode? node, int fallback)
    {
        var raw = Str(node, fallback.ToString(CultureInfo.InvariantCulture));
        var values = raw.Split('~', 2);
        if (!int.TryParse(values[0], CultureInfo.InvariantCulture, out var first) ||
            !int.TryParse(values[^1], CultureInfo.InvariantCulture, out var second))
            throw new FormatException($"範囲 {raw} が不正です。");
        if (first < 0 || second < 0 || Math.Max(first, second) > 10000) throw new FormatException($"範囲 {raw} が計算可能範囲外です。");
        return (Math.Min(first, second), Math.Max(first, second));
    }

    private sealed class Catalog(JsonArray masters)
    {
        private readonly Dictionary<string, (JsonObject Content, string Path)> entries = masters.OfType<JsonObject>()
            .Where(master => master["content"] is JsonObject)
            .Select(master => (Kind: Str(master["kind"]), Id: Ref(Obj(master["content"])["id"]), Content: Obj(master["content"]), Path: Str(master["path"])))
            .Where(master => master.Kind.Length > 0 && master.Id.Length > 0)
            .GroupBy(master => master.Kind + ":" + master.Id, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(group => group.Key, group => (group.First().Content, group.First().Path), StringComparer.OrdinalIgnoreCase);
        public JsonObject? Find(string kind, string id) => entries.GetValueOrDefault(kind + ":" + id).Content;
        public string Path(string kind, string id) => entries.GetValueOrDefault(kind + ":" + id).Path ?? "";
    }

    private static JsonObject Obj(JsonNode? node) => node as JsonObject ?? new JsonObject();
    private static JsonArray Arr(JsonNode? node) => node as JsonArray ?? new JsonArray();
    private static string Str(JsonNode? node, string fallback = "") => node?.ToString() ?? fallback;
    private static string Ref(JsonNode? node)
    {
        var value = Str(node is JsonObject obj ? obj["ref"] : node).Trim();
        var colon = value.IndexOf(':');
        return colon >= 0 ? value[(colon + 1)..].Trim() : value;
    }
    private static int Int(JsonNode? node, int fallback = 0) => int.TryParse(Str(node), CultureInfo.InvariantCulture, out var value) ? value : fallback;
    private static double Num(JsonNode? node, double fallback = 0) => double.TryParse(Str(node), NumberStyles.Float, CultureInfo.InvariantCulture, out var value) && double.IsFinite(value) ? value : fallback;
    private static bool Bool(JsonNode? node) => bool.TryParse(Str(node), out var value) && value;

    private static void Merge(JsonObject destination, JsonObject overrides)
    {
        foreach (var (key, value) in overrides)
        {
            if (value is JsonObject child && destination[key] is JsonObject original) Merge(original, child);
            else destination[key] = value?.DeepClone();
        }
    }
}
