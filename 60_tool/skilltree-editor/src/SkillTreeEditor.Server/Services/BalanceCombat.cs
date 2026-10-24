using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>
/// A bounded player/mob encounter with explicit positions, inputs and optional party targets.
/// The Plugin adapters share numeric combat calculations; server physics requires an explicit replay.
/// </summary>
public static partial class BalanceCombat
{
    private const string DamageSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/DamageCalculator.java";
    private const string DamageServiceSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/DamageService.java";
    private const string StatusSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/service/StatusService.java";
    private const string TimingSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/CombatTimingCalculator.java";
    private const string WeaponSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/ItemWeaponAttackService.java";
    private const string SkillSource = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/service/SkillService.java";
    private static readonly string[] Sources = BalanceCombatParity.SourceHashes.Keys.ToArray();
    private sealed record Skill(string Id, string Name, string Kind, string Type, string Element, double Ratio,
        double HealPercent, string Resource, double Cost, double AdditionalManaCost, int CooldownTicks, int CastTicks,
        int ImpactDelayTicks, BalanceSkillProfile Profile, JsonObject Definition);

    /// <summary>Simulates one seeded encounter, or returns an explicit invalid/unsupported result.</summary>
    public static JsonObject Simulate(JsonObject catalog, JsonObject request, JsonObject build, int seed, CancellationToken token)
    {
        var diagnostics = new List<(string Code, string Message, string Severity)>();
        var assumptions = new List<string>();
        var unsupported = new List<string>();
        var masters = BalanceCombatData.Masters(catalog).ToArray();
        void CheckUnique(string kind, string id)
        {
            if (id.Length > 0 && masters.Count(master => master.Kind.Equals(kind, StringComparison.OrdinalIgnoreCase)
                && BalanceCombatData.Text(master.Content["id"]).Equals(id, StringComparison.OrdinalIgnoreCase)) > 1)
                diagnostics.Add(("duplicate_master", $"{kind} {id} の定義が複数あります。参照先を一意にしてください。", "error"));
        }
        var strict = !BalanceCombatData.Text(request["mode"]).Equals("approximate", StringComparison.OrdinalIgnoreCase);
        var mobId = BalanceCombatData.Text(BalanceCombatData.Object(request["enemy"])?["mobId"]);
        CheckUnique("mob", mobId);
        var mob = BalanceCombatData.Find(masters, "mob", mobId);
        var classId = BalanceCombatData.Text(build["classId"]);
        CheckUnique("class", classId);
        var playerClass = BalanceCombatData.Find(masters, "class", classId);
        var playerLevel = BalanceCombatData.Integer(build["playerLevel"], 1);
        var classLevel = BalanceCombatData.Integer(build["classLevel"], 1);
        var maxSeconds = BalanceCombatData.Number(request["maxSeconds"], 60);
        foreach (var (input, key) in new[] { (request, "maxSeconds"), (build, "playerLevel"), (build, "classLevel"),
                     (request["enemy"] as JsonObject ?? new(), "distanceBlocks"), (request["enemy"] as JsonObject ?? new(), "targetHalfDepth") })
            if (input.ContainsKey(key) && (input[key]?.GetValueKind() != System.Text.Json.JsonValueKind.Number
                || !double.TryParse(input[key]?.ToString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var number) || !double.IsFinite(number)))
                diagnostics.Add(("invalid_number", $"{key} は有限の数値で指定してください。", "error"));
        var inputDistance = BalanceCombatData.Number(request["enemy"]?["distanceBlocks"], 3);
        var inputHalfDepth = BalanceCombatData.Number(request["enemy"]?["targetHalfDepth"], .3);
        if (inputDistance <= 0 || inputDistance > 128 || inputHalfDepth <= 0 || inputHalfDepth > 16)
            diagnostics.Add(("invalid_geometry", "敵との距離は0超128以下、当たり判定の半奥行きは0超16以下で指定してください。", "error"));
        if (mob is null) diagnostics.Add(("mob_missing", $"Mob {mobId} の定義がカタログにありません。", "error"));
        if (playerClass is null) diagnostics.Add(("class_missing", $"職業 {classId} の定義がカタログにありません。", "error"));
        if (playerLevel < 1 || classLevel < 1 || maxSeconds <= 0 || maxSeconds > 3600)
            diagnostics.Add(("invalid_bounds", "レベルは1以上、最大戦闘時間は0秒超から3600秒以下で指定してください。", "error"));
        var selectedGear = BalanceCombatData.Array(build["equipment"]).OfType<JsonObject>().ToArray();
        foreach (var gear in selectedGear)
        {
            CheckUnique("item", BalanceCombatData.Id(gear["itemId"]));
            foreach (var runeRef in BalanceCombatData.Array(gear["runes"]))
                CheckUnique("item", BalanceCombatData.Id(runeRef is JsonObject runeEntry ? runeEntry["itemId"] : runeRef));
        }
        if (selectedGear.Length > 20) diagnostics.Add(("too_many_equipment", "装備は20個以下で指定してください。", "error"));
        var requestedSkills = BalanceCombatData.Array(build["skillIds"]).Select(BalanceCombatData.Id).Where(id => id.Length > 0).ToArray();
        if (requestedSkills.Distinct(StringComparer.OrdinalIgnoreCase).Count() != requestedSkills.Length)
            diagnostics.Add(("duplicate_skill", "同じスキルを優先順位へ重複指定できません。", "error"));
        foreach (var field in new[] { "buffIds", "statusEffects", "followUpSkills" })
            if (BalanceCombatData.Array(build[field]).Any()) unsupported.Add($"build.{field} の戦闘効果は未対応です。");
        foreach (var field in new[] { "skillLevels", "skillSigils", "sigils", "skillModifiers" })
            if (build[field] is not null) unsupported.Add($"build.{field} は未対応です。対応スキルはLv1・シジル未装着で計算します。");
        foreach (var field in new[] { "buffIds", "statusEffects", "environmentEffects" })
            if (BalanceCombatData.Array(request[field]).Any()) unsupported.Add($"request.{field} の戦闘効果は未対応です。");
        var skills = new List<Skill>();
        var passiveIds = BalanceCombatData.Array(build["passiveSkillIds"]).Select(BalanceCombatData.Id).Where(id => id.Length > 0).ToArray();
        if (passiveIds.Distinct(StringComparer.Ordinal).Count() != passiveIds.Length || passiveIds.Length > 32)
            diagnostics.Add(("invalid_passives", "パッシブは重複なしで32件以下にしてください。", "error"));
        var passives = new List<Skill>();
        foreach (var message in BalanceRuntimeWorld.Validate(request)) diagnostics.Add(("invalid_world", message, "error"));
        foreach (var id in requestedSkills.Concat(passiveIds))
        {
            CheckUnique("skill", id);
            var master = BalanceCombatData.Find(masters, "skill", id);
            if (master is null) { diagnostics.Add(("skill_missing", $"スキル {id} の定義がありません。", "error")); continue; }
            var skill = ResolveSkill(master.Content, catalog);
            if (skill is null) unsupported.Add($"スキル {id}: {string.Join(" / ", BalanceSkills.Resolve(master.Content, catalog).Reasons)}");
            else
            {
                if (skill.Profile.Passive != passiveIds.Contains(id, StringComparer.Ordinal))
                    diagnostics.Add(("skill_kind", $"{id} は{(skill.Profile.Passive ? "パッシブのバインド" : "アクティブ優先順位")}へ指定してください。", "error"));
                else if (skill.Profile.Passive) passives.Add(skill);
                else skills.Add(skill);
                assumptions.AddRange(skill.Profile.Assumptions.Select(message => $"{id}: {message}"));
                var changed = BalanceSkillSourceParity.Mismatches(catalog, skill.Profile.Sources);
                if (changed.Count > 0) unsupported.Add($"{id} のPluginソースが照合済み版と異なります: {string.Join(", ", changed)}");
                var distance = BalanceCombatData.Number(request["enemy"]?["distanceBlocks"], 3);
                var halfDepth = BalanceCombatData.Number(request["enemy"]?["targetHalfDepth"], .3);
            }
        }
        if (skills.Concat(passives).GroupBy(skill => skill.Profile.ImplementationId, StringComparer.Ordinal).Any(group => group.Count() > 1))
            diagnostics.Add(("duplicate_implementation", "同じimplementationIdの別名スキルを同時に装着できません。", "error"));
        if (diagnostics.Count > 0) return Result("invalid", 0, 0, 0, 0, null, null, [],
            new Dictionary<string, double>(), new Dictionary<string, double>(), [], diagnostics, assumptions, catalog);

        var player = BalanceCombatData.DefaultPlayerStats();
        BalanceCombatData.AddClassStats(player, playerClass!.Content, classLevel);
        if (classLevel > BalanceCombatData.Integer(playerClass.Content["maxLevel"], 100))
            diagnostics.Add(("class_level", "クラスLvがマスターの上限を超えています。", "error"));
        if (playerLevel < BalanceCombatData.Integer(playerClass.Content["unlockLevel"], 1))
            diagnostics.Add(("class_unlock", "職業の解放プレイヤーLvを満たしていません。", "error"));
        assumptions.Add("選択した職業は解放・転職済みのビルドです。前提職の育成履歴と管理者による付与可否は戦闘へ含めません。");
        var setCounts = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        string? weaponTag = null;
        string? weaponName = null;
        var weaponCount = 0;
        var slotCounts = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        var twoHanded = selectedGear.Any(item =>
        {
            var master = BalanceCombatData.Find(masters, "item", BalanceCombatData.Id(item["itemId"]));
            var equipment = BalanceCombatData.Object(master?.Content["equipment"]);
            return BalanceCombatData.Text(equipment?["slot"]).Equals("WEAPON", StringComparison.OrdinalIgnoreCase)
                && BalanceCombatData.Text(equipment?["handType"]).Equals("TWO", StringComparison.OrdinalIgnoreCase);
        });
        foreach (var selected in selectedGear)
        {
            token.ThrowIfCancellationRequested();
            var itemId = BalanceCombatData.Id(selected["itemId"]);
            var item = BalanceCombatData.Find(masters, "item", itemId);
            var equipment = BalanceCombatData.Object(item?.Content["equipment"]);
            if (equipment is null)
            {
                diagnostics.Add(("equipment_missing", $"装備 {itemId} の定義がありません。", "error"));
                continue;
            }
            if (BalanceCombatData.Array(equipment["usableSkills"]).Any()
                || BalanceCombatData.Array(equipment["passiveSkills"]).Any()
                || BalanceCombatData.Array(equipment["buffs"]).Any())
                unsupported.Add($"装備 {itemId} の付与スキル・パッシブ・バフは未対応です。");
            if (selected["enchantments"] is not null)
                unsupported.Add($"装備 {itemId} の enchantments は未対応です。個体効果は enchants で指定してください。");
            var slot = BalanceCombatData.Text(equipment["slot"]);
            var tag = BalanceCombatData.Text(equipment["tag"]);
            var slotKey = slot.Equals("ACCESSORY", StringComparison.OrdinalIgnoreCase) ? slot + ":" + tag : slot;
            var capacity = slot.Equals("ACCESSORY", StringComparison.OrdinalIgnoreCase)
                ? tag.ToUpperInvariant() switch { "AMULET" or "CORE" => 1, "TALISMAN" or "RELIC" => 2, "CHARM" => 3, _ => 0 } : 1;
            slotCounts[slotKey] = slotCounts.GetValueOrDefault(slotKey) + 1;
            if (slotCounts[slotKey] > capacity)
                diagnostics.Add(("equipment_slot", $"装備スロット {slotKey} の上限 {capacity} を超えています。", "error"));
            if (twoHanded && slot.Equals("SUBWEAPON", StringComparison.OrdinalIgnoreCase)) continue;
            var neededLevel = Math.Max(0, BalanceCombatData.Integer(equipment["requiredLevel"]));
            if (playerLevel < neededLevel)
            {
                diagnostics.Add(("equipment_level", $"{itemId} はプレイヤー Lv.{neededLevel} が必要です。", "error"));
                continue;
            }
            var classRequirements = BalanceCombatData.Array(equipment["requiredClasses"]).OfType<JsonObject>().ToArray();
            if (classRequirements.Length > 0 && !classRequirements.Any(requirement =>
                BalanceCombatData.Text(requirement["classId"]).Equals(classId, StringComparison.OrdinalIgnoreCase)
                && classLevel >= Math.Max(1, BalanceCombatData.Integer(requirement["level"], 1))))
            {
                diagnostics.Add(("equipment_class", $"{itemId} の職業条件を満たしていません。", "error"));
                continue;
            }
            if (BalanceCombatData.Integer(selected["durabilityMax"]) > 0 && BalanceCombatData.Number(selected["durabilityValue"]) <= 0)
                continue;
            if (slot.Equals("WEAPON", StringComparison.OrdinalIgnoreCase))
            {
                weaponCount++;
                weaponTag = BalanceCombatData.Text(equipment["tag"]);
                weaponName = BalanceCombatData.Text(item!.Content["name"]);
            }
            BalanceCombatData.ApplyEquipmentItem(player, equipment, selected, masters, strict, assumptions, unsupported);
            var setId = BalanceCombatData.Id(equipment["setId"]);
            if (setId.Length > 0) setCounts[setId] = setCounts.GetValueOrDefault(setId) + 1;
        }
        if (weaponCount != 1) diagnostics.Add(("weapon_count", "使用可能な武器を1個指定してください。", "error"));
        foreach (var (setId, count) in setCounts)
        {
            CheckUnique("set_effect", setId);
            var set = BalanceCombatData.Find(masters, "set_effect", setId);
            if (set is null) { unsupported.Add($"セット効果 {setId} の定義がありません。"); continue; }
            foreach (var piece in BalanceCombatData.Array(set.Content["pieces"]).OfType<JsonObject>())
                if (count >= BalanceCombatData.Integer(piece["count"]))
                    foreach (var stat in BalanceCombatData.Array(piece["stats"]).OfType<JsonObject>())
                        if (BalanceCombatData.Text(stat["type"]).Equals("SCALAR", StringComparison.OrdinalIgnoreCase))
                            unsupported.Add($"セット効果 {setId} の SCALAR は未対応です。");
                        else BalanceCombatData.Add(player, BalanceCombatData.Text(stat["status"]), BalanceCombatData.Number(stat["value"]));
        }
        if (build["baseStats"] is JsonObject overrides)
        {
            foreach (var (key, value) in overrides) player[key] = BalanceCombatData.Number(value);
            assumptions.Add("build.baseStats は派生能力値の計算前に、組み立てた基礎ステータスを上書きします。");
        }
        BalanceCombatData.AddPrimaryDerived(player);
        var enemyLevel = BalanceCombatData.Integer(BalanceCombatData.Object(request["enemy"])?["level"], BalanceCombatData.Integer(mob!.Content["level"], 1));
        var effectiveMob = BalanceCombatData.ResolveMob(mob!.Content, enemyLevel, assumptions);
        var enemy = BalanceCombatData.MobStats(effectiveMob, enemyLevel, assumptions);
        var enemyCombat = BalanceCombatData.Object(effectiveMob["ai"])?["combat"] as JsonObject;
        var enemyNormal = BalanceCombatData.Object(enemyCombat?["normalAttack"]);
        if (enemyNormal is null) unsupported.Add("Mob に通常攻撃設定がありません。AIのスキル専用戦闘は未対応です。");
        if (BalanceCombatData.Array(enemyCombat?["skills"]).Any() || BalanceCombatData.Array(mob.Content["skills"]).Any())
            unsupported.Add("Mob の個別スキルは未対応です。");
        if (BalanceCombatData.Array(mob.Content["buffs"]).Any() || BalanceCombatData.Array(mob.Content["statusEffects"]).Any())
            unsupported.Add("Mob のバフ・状態異常は未対応です。");
        var weapon = Weapon(weaponTag);
        var useNormalAttacks = build["normalAttacks"]?.GetValue<bool>() != false;
        var dynamic = skills.Any(skill => skill.Profile.Runtime) || passives.Count > 0 || request["world"] is not null || request["inputs"] is not null || request["allies"] is not null;
        if (!dynamic)
            foreach (var skill in skills.Where(skill => skill.Profile.Effects.Any(effect => effect.Kind == "damage" && BalanceSkillDamage.OutOfRange(inputDistance, inputHalfDepth, effect))))
                unsupported.Add($"{skill.Id}: 固定距離が射程外です。対象なしでの発動成否・消費を再現できないため実行条件を見直してください。");
        if (!dynamic && useNormalAttacks && inputDistance > 3)
            unsupported.Add("通常攻撃は固定距離3ブロック以内の条件に限定します。遠距離では通常攻撃を無効にしてください。");
        if (useNormalAttacks && weapon is null) unsupported.Add($"武器タグ {weaponTag} に組み込み通常攻撃がありません。");
        if (!dynamic && useNormalAttacks && weaponTag is ("SPEAR" or "BOW" or "SHORTBOW" or "LONGBOW" or "WAND" or "STAFF"))
            unsupported.Add($"{weaponTag} の多段・飛翔体挙動は未対応です。");
        foreach (var skill in skills.Concat(passives))
            foreach (var requirement in skill.Profile.Requirements ?? [])
                if (requirement == "bow" && weaponTag is not ("BOW" or "SHORTBOW" or "LONGBOW"))
                    diagnostics.Add(("weapon_required", $"{skill.Id} は弓を装備してください。", "error"));
                else if (requirement == "velocity_replay" && BalanceCombatData.Text(request["world"]?["velocityMode"]) != "replay")
                    unsupported.Add($"{skill.Id} の速度変更にはworld.velocityMode=replayと各tickの座標軌跡が必要です。");
                else if (requirement == "party" && !BalanceCombatData.Array(request["allies"]).Any()) unsupported.Add($"{skill.Id} の検証にはalliesにパーティの対象を指定してください。");
                else if (requirement == "static_only" && dynamic) unsupported.Add($"{skill.Id} の動的位置判定は未検証です。静止条件だけに対応します。");
                else if (requirement is not ("bow" or "velocity_replay" or "party" or "static_only")) unsupported.Add($"{skill.Id} の追加条件 {requirement} は未対応です。");
        if (BalanceCombatData.Boolean(BalanceCombatData.Object(effectiveMob["shield"])?["enabled"])
            && BalanceCombatData.Object(effectiveMob["shield"])?["rechargeTimeSeconds"] is not null)
            unsupported.Add("Mob のシールド再充填は未対応です。");
        if (BalanceCombatData.Boolean(effectiveMob["damageImmune"])) unsupported.Add("無敵Mobのダメージ受付条件は未対応です。");
        foreach (var (name, stats) in new[] { ("プレイヤー", player), ("敵", enemy) })
            foreach (var (key, value) in stats.Where(entry => entry.Value != 0 && !SupportedStatuses.Contains(entry.Key)))
                unsupported.Add($"{name}のステータス {key}={value} の効果は未対応です。");
        foreach (var ally in BalanceCombatData.Array(request["allies"]).OfType<JsonObject>())
            foreach (var (key, value) in ally["stats"] as JsonObject ?? new())
                if (value?.GetValueKind() != System.Text.Json.JsonValueKind.Number || !double.TryParse(value.ToString(), out var statValue) || !double.IsFinite(statValue))
                    diagnostics.Add(("invalid_ally_status", $"味方{key}は有限の数値です。", "error"));
                else if (statValue != 0 && !SupportedStatuses.Contains(key)) unsupported.Add($"味方のステータス {key} は未対応です。");
        if (diagnostics.Any(item => item.Severity == "error")) return Result("invalid", 0, 0, 0, 0, null, null, [], player, enemy, [], diagnostics, assumptions, catalog);
        var changedSources = BalanceCombatParity.Mismatches(catalog);
        if (changedSources.Count > 0)
        {
            diagnostics.Add(("plugin_source_changed",
                $"照合済み Plugin ソースの指紋が {changedSources.Count} 件一致しません。先頭: {changedSources[0]}",
                strict ? "error" : "warning"));
            if (strict) return Result("unsupported", 0, 0, 0, 0, null, null, [], player, enemy, [], diagnostics, assumptions, catalog);
            assumptions.Add("Plugin ソースの変更後に再照合されていないため、この結果は近似です。");
        }
        if (unsupported.Count > 0)
        {
            foreach (var message in unsupported) diagnostics.Add(("unsupported_mechanic", message, strict ? "error" : "warning"));
            if (strict) return Result("unsupported", 0, 0, 0, 0, null, null, [], player, enemy, [], diagnostics, assumptions, catalog);
        }
        assumptions.Add(dynamic ? "一対一・読み込み済みの遮蔽なし平面です。移動は明示したtick入力とPluginの位置移動処理に限定し、Bukkit物理・地形・敵AIの接近は再現しません。"
            : "一対一の静止戦闘です。対象は射程内で標的に取られ続け、ボス・ダンジョン制限や環境効果はありません。");
        assumptions.Add("乱数列は Plugin の ThreadLocalRandom と一致しません。指定seedで本モデルの判定を再現します。");
        assumptions.Add("skillIds はLv1・シジル未装着・習得/装着/使用許可済みとし、記載順に使用します。実アカウント解放状態は参照しません。");
        if (unsupported.Count > 0) assumptions.Add("未対応のスキル・エンチャント・ルーン効果は近似モードで省略します。");
        return Run(request, seed, maxSeconds, player, enemy, effectiveMob, enemyNormal, weapon ?? ("MELEE", 10),
            weaponName ?? "通常攻撃", skills, classId, diagnostics, assumptions, catalog, build, token, passives, weaponTag ?? "", dynamic);
    }

    internal static bool SupportsSkill(JsonObject definition) => BalanceSkills.Resolve(definition).Supported;
    internal static bool SupportsStatus(string key) => SupportedStatuses.Contains(key);

    private static Skill? ResolveSkill(JsonObject definition, JsonObject catalog)
    {
        var profile = BalanceSkills.Resolve(definition, catalog);
        if (!profile.Supported) return null;
        var resource = BalanceCombatData.Text(definition["resourceType"]);
        return new Skill(BalanceCombatData.Text(definition["id"]), BalanceCombatData.Text(definition["name"]),
            profile.Effects.Count > 0 && profile.Effects.All(effect => effect.Kind is "heal" or "resource") ? "heal" : "effect",
            "MAGIC", "NONE", 0, 0, resource,
            BalanceCombatData.Number(definition["resourceCost"], BalanceCombatData.Number(definition["manaCost"])),
            resource == "ENERGY" ? BalanceCombatData.Number(definition["manaCost"]) : 0,
            BalanceCombatData.Integer(definition["cooldownTicks"]), BalanceCombatData.Integer(definition["castTimeTicks"]), 0, profile, definition);
    }

    private static (string Type, int Ticks)? Weapon(string? tag) => tag?.ToUpperInvariant() switch
    {
        "SWORD" => ("MELEE", 10), "HAMMER" => ("MELEE", 22), "SPEAR" => ("MELEE", 14),
        "BOW" => ("RANGED", 12), "SHORTBOW" => ("RANGED", 7), "LONGBOW" => ("RANGED", 17),
        "WAND" => ("MAGIC", 9), "STAFF" => ("MAGIC", 14), _ => null
    };

    // Nonzero battle effects outside this list must be diagnosed, including unknown future IDs.
    // Movement/tool/economy values are neutral in the fixed-distance combat scenario.
    private static readonly HashSet<string> SupportedStatuses = new(StringComparer.OrdinalIgnoreCase)
    {
        "MAX_HEALTH", "MAX_MANA", "MAX_ENERGY", "MAX_SHIELD", "STRENGTH", "DEXTERITY", "INTELLIGENCE", "VITALITY", "AGILITY", "LUCK",
        "ATTACK", "MELEE_ATTACK", "RANGED_ATTACK", "MAGIC_ATTACK", "DEFENSE", "MELEE_DEFENSE", "RANGED_DEFENSE", "MAGIC_DEFENSE",
        "ACCURACY", "EVASION", "ATTACK_SPEED", "CRITICAL_RATE", "CRITICAL_DAMAGE", "SUPER_CRITICAL_DAMAGE", "FINAL_DAMAGE_MULTIPLIER",
        "HP_REGEN", "MP_REGEN", "ENERGY_REGEN", "SUPPORT_POWER", "HEALING_INCREASE", "SHIELD_BREAK",
        "COOLDOWN_REDUCTION", "CAST_TIME_REDUCTION", "MANA_COST_REDUCTION", "ENERGY_COST_REDUCTION",
        "NORMAL_ATTACK_DEGRADATION_DELAY", "NORMAL_ATTACK_DAMAGE_INCREASE", "SKILL_DAMAGE_INCREASE",
        "DEFENSE_PENETRATION_RATE", "MELEE_DEFENSE_PENETRATION_RATE", "RANGED_DEFENSE_PENETRATION_RATE", "MAGIC_DEFENSE_PENETRATION_RATE",
        "NONE_DAMAGE_INCREASE", "NONE_RESISTANCE", "NONE_RESISTANCE_CAP",
        "FIRE_DAMAGE_INCREASE", "FIRE_RESISTANCE", "FIRE_RESISTANCE_CAP", "FIRE_PENETRATION",
        "ICE_DAMAGE_INCREASE", "ICE_RESISTANCE", "ICE_RESISTANCE_CAP", "ICE_PENETRATION",
        "LIGHTNING_DAMAGE_INCREASE", "LIGHTNING_RESISTANCE", "LIGHTNING_RESISTANCE_CAP", "LIGHTNING_PENETRATION",
        "MOVEMENT_SPEED", "MOVEMENT_SPEED_CAP", "KNOCKBACK_RESISTANCE", "CAST_DISTANCE", "MINING_SPEED", "FISHING_LEVEL", "GATHERING_LEVEL",
        "CONSUMABLE_USE_MOVEMENT_ALLOWANCE", "CONSUMABLE_USE_MOVEMENT_SLOWDOWN_RATE", "DROP_RATE_INCREASE", "EXPERIENCE_GAIN_RATE"
        , "CONDITION_DURATION_INCREASE", "BURNING_APPLY_CHANCE", "BURNING_RESISTANCE", "BURNING_DAMAGE_INCREASE", "BURNING_DAMAGE_RESISTANCE", "BURNING_DAMAGE_PENETRATION",
        "SHOCKED_APPLY_CHANCE", "SHOCKED_RESISTANCE", "SHOCKED_DAMAGE_INCREASE", "SHOCKED_DAMAGE_RESISTANCE", "SHOCKED_DAMAGE_PENETRATION",
        "CHILLED_APPLY_CHANCE", "CHILLED_RESISTANCE", "FROZEN_APPLY_CHANCE", "FROZEN_RESISTANCE", "WEAKNESS_APPLY_CHANCE", "WEAKNESS_RESISTANCE"
        , "POISONED_RESISTANCE", "BLINDNESS_RESISTANCE", "HEALING_INHIBITION_RESISTANCE", "BLINDNESS_APPLY_CHANCE", "PHOENIX_EXTRA_ATTACK_COUNT"
    };

    private static double Strike(Dictionary<string, double> attacker, Dictionary<string, double> victim,
        string type, string element, string source, double ratio, double attackerMultiplier, bool playerAgainstMob, Random rng)
        => BalanceSkillDamage.Calculate(attacker, victim, type, [new(element, ratio)], source,
            attackerMultiplier, playerAgainstMob, rng).Damage;

    private static JsonObject Result(string outcome, int ticks, double dealt, double taken, double dps,
        int? mpExhausted, int? engExhausted, JsonArray contributions, IReadOnlyDictionary<string, double> player,
        IReadOnlyDictionary<string, double> enemy, JsonArray resources,
        IEnumerable<(string Code, string Message, string Severity)> diagnostics, IEnumerable<string> assumptions,
        JsonObject catalog)
    {
        var result = new JsonObject
        {
            ["outcome"] = outcome, ["elapsedTicks"] = ticks, ["damageDealt"] = dealt, ["damageTaken"] = taken,
            ["dps"] = dps, ["mpExhaustedTick"] = mpExhausted, ["engExhaustedTick"] = engExhausted,
            ["contributions"] = contributions, ["resources"] = resources,
            ["playerStats"] = new JsonObject(player.Select(pair => new KeyValuePair<string, JsonNode?>(pair.Key, JsonValue.Create(pair.Value)))),
            ["enemyStats"] = new JsonObject(enemy.Select(pair => new KeyValuePair<string, JsonNode?>(pair.Key, JsonValue.Create(pair.Value)))),
            ["diagnostics"] = new JsonArray(diagnostics.Select(item => (JsonNode?)new JsonObject
                { ["code"] = item.Code, ["message"] = item.Message, ["severity"] = item.Severity }).ToArray()),
            ["assumptions"] = new JsonArray(assumptions.Select(item => (JsonNode?)JsonValue.Create(item)).ToArray()),
            ["sources"] = new JsonArray(Sources.Select(item => (JsonNode?)JsonValue.Create(item)).ToArray()),
            ["modelVersion"] = BalanceCombatData.Text(catalog["modelVersion"]),
            ["masterHash"] = BalanceCombatData.Text(catalog["masterHash"])
        };
        return result;
    }
}
