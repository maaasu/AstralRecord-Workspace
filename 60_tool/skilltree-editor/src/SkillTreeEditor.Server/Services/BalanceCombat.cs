using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>
/// A bounded, stationary one-player/one-mob encounter model. The numeric combat core tracks the
/// cited Plugin implementation; dynamic Bukkit skill executors are explicitly unsupported.
/// </summary>
public static class BalanceCombat
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
        int ImpactDelayTicks);

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
        foreach (var field in new[] { "buffIds", "passiveSkillIds", "statusEffects", "followUpSkills" })
            if (BalanceCombatData.Array(build[field]).Any()) unsupported.Add($"build.{field} の戦闘効果は未対応です。");
        foreach (var field in new[] { "buffIds", "statusEffects", "environmentEffects" })
            if (BalanceCombatData.Array(request[field]).Any()) unsupported.Add($"request.{field} の戦闘効果は未対応です。");
        var skills = new List<Skill>();
        foreach (var id in requestedSkills)
        {
            CheckUnique("skill", id);
            var master = BalanceCombatData.Find(masters, "skill", id);
            if (master is null) { diagnostics.Add(("skill_missing", $"スキル {id} の定義がありません。", "error")); continue; }
            var skill = ResolveSkill(master.Content);
            if (skill is null) unsupported.Add($"スキル {id} の実行処理は、このモデルで未対応です。");
            else skills.Add(skill);
        }
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
        if (weapon is null) unsupported.Add($"武器タグ {weaponTag} に組み込み通常攻撃がありません。");
        if (weaponTag is "SPEAR" or "BOW" or "SHORTBOW" or "LONGBOW" or "WAND" or "STAFF")
            unsupported.Add($"{weaponTag} の多段・飛翔体挙動は未対応です。");
        if (BalanceCombatData.Boolean(BalanceCombatData.Object(effectiveMob["shield"])?["enabled"])
            && BalanceCombatData.Object(effectiveMob["shield"])?["rechargeTimeSeconds"] is not null)
            unsupported.Add("Mob のシールド再充填は未対応です。");
        if (BalanceCombatData.Boolean(effectiveMob["damageImmune"])) unsupported.Add("無敵Mobのダメージ受付条件は未対応です。");
        foreach (var (name, stats) in new[] { ("プレイヤー", player), ("敵", enemy) })
            foreach (var (key, value) in stats.Where(entry => entry.Value != 0 && !SupportedStatuses.Contains(entry.Key)))
                unsupported.Add($"{name}のステータス {key}={value} の効果は未対応です。");
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
        assumptions.Add("一対一の静止戦闘です。対象は射程内で標的に取られ続け、ボス・ダンジョン制限や環境効果はありません。");
        assumptions.Add("乱数列は Plugin の ThreadLocalRandom と一致しません。指定seedで本モデルの判定を再現します。");
        assumptions.Add("skillIds は習得・装着・使用許可済みとし、記載順に使用します。詠唱中断と飛翔体の外れは発生しません。");
        if (unsupported.Count > 0) assumptions.Add("未対応のスキル・エンチャント・ルーン効果は近似モードで省略します。");
        return Run(request, seed, maxSeconds, player, enemy, effectiveMob, enemyNormal, weapon ?? ("MELEE", 10),
            weaponName ?? "通常攻撃", skills, classId, diagnostics, assumptions, catalog, token);
    }

    internal static bool SupportsSkill(JsonObject definition) => ResolveSkill(definition) is not null;

    private static Skill? ResolveSkill(JsonObject definition)
    {
        var id = BalanceCombatData.Text(definition["id"]);
        var implementation = BalanceCombatData.Text(definition["implementationId"]);
        if (id != implementation) return null;
        var kind = id switch
        {
            "adventurer_quick_shot" => "damage",
            "adventurer_mana_burst" => "damage",
            "adventurer_smash" => "damage",
            "wizard_self_heal" => "heal",
            _ => null
        };
        if (kind is null) return null;
        var parameters = BalanceCombatData.Object(definition["params"]);
        var ratio = id == "adventurer_quick_shot" ? 2.25 : BalanceCombatData.Number(parameters?["damageRatio"]);
        var heal = BalanceCombatData.Number(parameters?["healPercent"]);
        if (kind == "damage" && ratio <= 0 || kind == "heal" && (heal <= 0 || heal > 100)) return null;
        var resource = BalanceCombatData.Text(definition["resourceType"]);
        if (resource is not ("MANA" or "ENERGY")) return null;
        return new Skill(id, BalanceCombatData.Text(definition["name"]), kind,
            id == "adventurer_quick_shot" ? "RANGED" : id == "adventurer_smash" ? "MELEE" : "MAGIC", "NONE", ratio, heal,
            resource, BalanceCombatData.Number(definition["resourceCost"], BalanceCombatData.Number(definition["manaCost"])),
            resource == "ENERGY" ? BalanceCombatData.Number(definition["manaCost"]) : 0,
            BalanceCombatData.Integer(definition["cooldownTicks"]), BalanceCombatData.Integer(definition["castTimeTicks"]),
            id == "adventurer_smash" ? Math.Max(0, BalanceCombatData.Integer(parameters?["impactDelayTicks"], 8)) : 0);
    }

    private static (string Type, int Ticks)? Weapon(string? tag) => tag?.ToUpperInvariant() switch
    {
        "SWORD" => ("MELEE", 10), "HAMMER" => ("MELEE", 22), "SPEAR" => ("MELEE", 14),
        "BOW" => ("RANGED", 12), "SHORTBOW" => ("RANGED", 7), "LONGBOW" => ("RANGED", 17),
        "WAND" => ("MAGIC", 9), "STAFF" => ("MAGIC", 14), _ => null
    };

    private static JsonObject Run(JsonObject request, int seed, double maxSeconds, Dictionary<string, double> player,
        Dictionary<string, double> enemy, JsonObject mob, JsonObject? enemyNormal, (string Type, int Ticks) weapon,
        string weaponName, IReadOnlyList<Skill> skills, string classId,
        List<(string Code, string Message, string Severity)> diagnostics, List<string> assumptions,
        JsonObject catalog, CancellationToken token)
    {
        var rng = new Random(seed);
        var maxTicks = Math.Min(72_000, (int)Math.Ceiling(maxSeconds * 20));
        var hp = Math.Max(1, BalanceCombatData.Stat(player, "MAX_HEALTH"));
        var mp = Math.Max(0, BalanceCombatData.Stat(player, "MAX_MANA"));
        var eng = Math.Max(0, BalanceCombatData.Stat(player, "MAX_ENERGY"));
        var shield = 0.0;
        var enemyHp = Math.Max(1, BalanceCombatData.Stat(enemy, "MAX_HEALTH"));
        var enemyShieldConfig = BalanceCombatData.Object(mob["shield"]);
        var enemyShield = BalanceCombatData.Boolean(enemyShieldConfig?["enabled"])
            ? BalanceCombatData.Number(enemyShieldConfig?["max"]) : 0;
        var playerCooldown = BalanceCombatMath.CooldownTicks(weapon.Ticks, BalanceCombatData.Stat(player, "COOLDOWN_REDUCTION"));
        var enemyBaseInterval = BalanceCombatData.Integer(BalanceCombatData.Object(request["enemy"])?["attackIntervalTicks"],
            BalanceCombatData.Integer(enemyNormal?["intervalTicks"]));
        var enemyInterval = BalanceCombatMath.AttackIntervalTicks(enemyBaseInterval, BalanceCombatData.Stat(enemy, "ATTACK_SPEED"));
        if (enemyInterval <= 0) enemyInterval = long.MaxValue;
        var enemyType = BalanceCombatData.Text(BalanceCombatData.Object(mob["ai"])?["combat"]?["style"]);
        if (enemyType is not ("MELEE" or "RANGED" or "MAGIC")) enemyType = "MELEE";
        long nextPlayer = 0, nextEnemy = enemyInterval;
        var normalCount = 0;
        var lastNormalTick = long.MinValue;
        var damageDealt = 0.0;
        var damageTaken = 0.0;
        var contributions = new Dictionary<string, (string Name, int Uses, double Damage)>(StringComparer.OrdinalIgnoreCase);
        var skillReady = skills.ToDictionary(skill => skill.Id, _ => 0L, StringComparer.OrdinalIgnoreCase);
        var pendingImpacts = new List<(Skill Skill, long Tick)>();
        Skill? castingSkill = null;
        long castCompleteTick = long.MaxValue;
        int? mpExhausted = null, engExhausted = null;
        var resources = new JsonArray();
        var outcome = "timeout";
        var elapsed = maxTicks;
        for (var tick = 0; tick <= maxTicks; tick++)
        {
            token.ThrowIfCancellationRequested();
            if (tick > 0 && tick % 20 == 0)
            {
                var support = 1 + Math.Max(0, BalanceCombatData.Stat(player, "SUPPORT_POWER")) / 100;
                hp = Math.Min(BalanceCombatData.Stat(player, "MAX_HEALTH"), hp + BalanceCombatData.Stat(player, "HP_REGEN") / 5 * support
                    * (1 + Math.Max(0, BalanceCombatData.Stat(player, "HEALING_INCREASE")) / 100));
                mp = Math.Min(BalanceCombatData.Stat(player, "MAX_MANA"), mp + BalanceCombatData.Stat(player, "MP_REGEN") / 5 * support);
                eng = Math.Min(BalanceCombatData.Stat(player, "MAX_ENERGY"), eng + BalanceCombatData.Stat(player, "ENERGY_REGEN") / 5 * support);
            }
            foreach (var pending in pendingImpacts.Where(pending => pending.Tick <= tick).ToArray())
            {
                ApplySkillEffect(pending.Skill);
                pendingImpacts.Remove(pending);
            }
            if (enemyHp <= 0) { outcome = "victory"; elapsed = tick; break; }
            var usedSkill = false;
            if (castingSkill is not null && tick >= castCompleteTick)
            {
                ExecuteSkill(castingSkill, tick);
                castingSkill = null;
                usedSkill = true;
            }
            if (castingSkill is null && !usedSkill)
                foreach (var skill in skills)
                {
                    if (tick < skillReady[skill.Id]) continue;
                    if (skill.Kind == "heal" && hp >= BalanceCombatData.Stat(player, "MAX_HEALTH") * 0.75) continue;
                    var cost = Math.Max(0, skill.Cost * (1 - Math.Max(0, BalanceCombatData.Stat(player,
                        skill.Resource == "MANA" ? "MANA_COST_REDUCTION" : "ENERGY_COST_REDUCTION")) / 100));
                    var extraMana = Math.Max(0, skill.AdditionalManaCost * (1 - Math.Max(0,
                        BalanceCombatData.Stat(player, "MANA_COST_REDUCTION")) / 100));
                    if (skill.Resource == "MANA" && mp + 1e-6 < cost || skill.Resource == "ENERGY" && mp + 1e-6 < extraMana)
                    { mpExhausted ??= tick; continue; }
                    if (skill.Resource == "ENERGY" && eng + 1e-6 < cost)
                    { engExhausted ??= tick; continue; }
                    if (skill.CastTicks > 0)
                    {
                        castingSkill = skill;
                        castCompleteTick = tick + BalanceCombatMath.CastTimeTicks(skill.CastTicks, BalanceCombatData.Stat(player, "CAST_TIME_REDUCTION"));
                    }
                    else ExecuteSkill(skill, tick);
                    usedSkill = true;
                    break;
                }
            if (!usedSkill && castingSkill is null && tick >= nextPlayer)
            {
                if (tick - lastNormalTick >= 200) normalCount = 0;
                normalCount++;
                lastNormalTick = tick;
                var delay = (int)Math.Floor(Math.Max(0, BalanceCombatData.Stat(player, "NORMAL_ATTACK_DEGRADATION_DELAY")));
                var stage = classId.Equals("adventurer", StringComparison.OrdinalIgnoreCase) || normalCount < 5 + delay
                    ? 0 : Math.Min(11, normalCount - (5 + delay) + 1);
                var multiplier = BalanceCombatMath.DegradationDamageMultiplier(stage);
                var hit = Strike(player, enemy, weapon.Type, "NONE", "NORMAL_ATTACK", 1, multiplier, true, rng);
                var dealt = 0.0;
                if (enemyShield > 0 && hit > 0)
                {
                    var shieldHit = BalanceCombatMath.ShieldDamage(hit, BalanceCombatData.Stat(enemy, "MAX_HEALTH"),
                        BalanceCombatData.Stat(player, "SHIELD_BREAK"), 1, enemyShield);
                    enemyShield -= shieldHit;
                    dealt = shieldHit;
                }
                else { enemyHp -= hit; dealt = Math.Min(Math.Max(0, hit), Math.Max(0, enemyHp + hit)); }
                damageDealt += dealt;
                var normal = contributions.GetValueOrDefault("normal_attack", (weaponName, 0, 0));
                contributions["normal_attack"] = (weaponName, normal.Uses + 1, normal.Damage + dealt);
                var interval = BalanceCombatMath.AttackIntervalTicks(playerCooldown,
                    BalanceCombatData.Stat(player, "ATTACK_SPEED") * BalanceCombatMath.DegradationSpeedMultiplier(stage));
                nextPlayer = tick + Math.Max(1, interval);
                if (enemyHp <= 0) { outcome = "victory"; elapsed = tick; break; }
            }
            if (enemyHp <= 0) { outcome = "victory"; elapsed = tick; break; }
            if (tick >= nextEnemy)
            {
                var hit = Strike(enemy, player, enemyType, "NONE", "NORMAL_ATTACK", 1, 1, false, rng);
                if (shield > 0 && hit > 0) shield -= BalanceCombatMath.ShieldDamage(hit,
                    BalanceCombatData.Stat(player, "MAX_HEALTH"), BalanceCombatData.Stat(enemy, "SHIELD_BREAK"), 1, shield);
                else { var actual = Math.Min(Math.Max(0, hp), hit); hp = Math.Max(0, hp - hit); damageTaken += actual; }
                nextEnemy += enemyInterval;
                if (hp <= 0) { outcome = "death"; elapsed = tick; break; }
            }
            if (tick % 20 == 0 || tick == maxTicks)
                resources.Add(new JsonObject { ["tick"] = tick, ["hp"] = hp, ["mp"] = mp, ["eng"] = eng,
                    ["shield"] = shield, ["enemyHp"] = enemyHp, ["enemyShield"] = enemyShield });
        }
        if (resources.Count == 0 || BalanceCombatData.Integer((resources[^1] as JsonObject)?["tick"]) != elapsed)
            resources.Add(new JsonObject { ["tick"] = elapsed, ["hp"] = hp, ["mp"] = mp, ["eng"] = eng,
                ["shield"] = shield, ["enemyHp"] = enemyHp, ["enemyShield"] = enemyShield });
        var contributionRows = new JsonArray(contributions.Select(pair => (JsonNode?)new JsonObject
        { ["id"] = pair.Key, ["name"] = pair.Value.Name, ["uses"] = pair.Value.Uses, ["damage"] = pair.Value.Damage }).ToArray());
        return Result(outcome, elapsed, damageDealt, damageTaken,
            elapsed > 0 ? damageDealt / elapsed * 20 : damageDealt * 20, mpExhausted, engExhausted,
            contributionRows, player, enemy,
            resources, diagnostics, assumptions, catalog);

        void ExecuteSkill(Skill skill, int tick)
        {
            normalCount = 0;
            var cost = Math.Max(0, skill.Cost * (1 - Math.Max(0, BalanceCombatData.Stat(player,
                skill.Resource == "MANA" ? "MANA_COST_REDUCTION" : "ENERGY_COST_REDUCTION")) / 100));
            var extraMana = Math.Max(0, skill.AdditionalManaCost * (1 - Math.Max(0,
                BalanceCombatData.Stat(player, "MANA_COST_REDUCTION")) / 100));
            if (skill.Resource == "MANA") mp = Math.Max(0, mp - cost);
            else { eng = Math.Max(0, eng - cost); mp = Math.Max(0, mp - extraMana); }
            skillReady[skill.Id] = tick + BalanceCombatMath.CooldownTicks(skill.CooldownTicks,
                BalanceCombatData.Stat(player, "COOLDOWN_REDUCTION"));
            var current = contributions.GetValueOrDefault(skill.Id, (skill.Name, 0, 0));
            contributions[skill.Id] = (skill.Name, current.Uses + 1, current.Damage);
            if (skill.ImpactDelayTicks > 0) pendingImpacts.Add((skill, tick + skill.ImpactDelayTicks));
            else ApplySkillEffect(skill);
        }

        void ApplySkillEffect(Skill skill)
        {
            var dealt = 0.0;
            if (skill.Kind == "heal")
            {
                var maxHealth = BalanceCombatData.Stat(player, "MAX_HEALTH");
                var amount = maxHealth * skill.HealPercent / 100
                    * (1 + Math.Max(0, BalanceCombatData.Stat(player, "SUPPORT_POWER")) / 100)
                    * (1 + Math.Max(0, BalanceCombatData.Stat(player, "HEALING_INCREASE")) / 100);
                hp = Math.Min(maxHealth, hp + amount);
            }
            else
            {
                var hit = Strike(player, enemy, skill.Type, skill.Element, "SKILL", skill.Ratio, 1, true, rng);
                if (enemyShield > 0 && hit > 0)
                {
                    dealt = BalanceCombatMath.ShieldDamage(hit, BalanceCombatData.Stat(enemy, "MAX_HEALTH"),
                        BalanceCombatData.Stat(player, "SHIELD_BREAK"), 1, enemyShield);
                    enemyShield -= dealt;
                }
                else { enemyHp -= hit; dealt = Math.Min(Math.Max(0, hit), Math.Max(0, enemyHp + hit)); }
                damageDealt += dealt;
            }
            var current = contributions.GetValueOrDefault(skill.Id, (skill.Name, 0, 0));
            contributions[skill.Id] = (skill.Name, current.Uses, current.Damage + dealt);
        }
    }

    // Nonzero battle effects outside this list must be diagnosed, including unknown future IDs.
    // Movement/tool/economy values are neutral in the fixed-distance combat scenario.
    private static readonly HashSet<string> SupportedStatuses = new(StringComparer.OrdinalIgnoreCase)
    {
        "MAX_HEALTH", "MAX_MANA", "MAX_ENERGY", "STRENGTH", "DEXTERITY", "INTELLIGENCE", "VITALITY", "AGILITY", "LUCK",
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
    };

    private static double Strike(Dictionary<string, double> attacker, Dictionary<string, double> victim,
        string type, string element, string source, double ratio, double attackerMultiplier, bool playerAgainstMob, Random rng)
    {
        string typed = type switch { "RANGED" => "RANGED_ATTACK", "MAGIC" => "MAGIC_ATTACK", _ => "MELEE_ATTACK" };
        string primary = type switch { "RANGED" => "DEXTERITY", "MAGIC" => "INTELLIGENCE", _ => "STRENGTH" };
        string defense = type switch { "RANGED" => "RANGED_DEFENSE", "MAGIC" => "MAGIC_DEFENSE", _ => "MELEE_DEFENSE" };
        string penetration = type switch { "RANGED" => "RANGED_DEFENSE_PENETRATION_RATE", "MAGIC" => "MAGIC_DEFENSE_PENETRATION_RATE", _ => "MELEE_DEFENSE_PENETRATION_RATE" };
        var accuracy = BalanceCombatData.Stat(attacker, "ACCURACY", playerAgainstMob ? 0 : 100);
        if (!playerAgainstMob && accuracy <= 0) accuracy = 100;
        var hitChance = BalanceCombatMath.HitChance(Math.Max(0, accuracy), Math.Max(0, BalanceCombatData.Stat(victim, "EVASION")));
        if (hitChance < 100 && rng.NextDouble() * 100 >= hitChance) return 0;
        var attack = BalanceCombatMath.AttackPower(BalanceCombatData.Stat(attacker, "ATTACK"),
            BalanceCombatData.Stat(attacker, typed), BalanceCombatData.Stat(attacker, primary));
        attack = Math.Max(0, attack);
        var increaseType = source == "SKILL" ? "SKILL_DAMAGE_INCREASE" : "NORMAL_ATTACK_DAMAGE_INCREASE";
        var damage = attack * ratio * Math.Max(0, 1 + BalanceCombatData.Stat(attacker, increaseType) / 100) * attackerMultiplier;
        var critRate = Math.Max(0, BalanceCombatData.Stat(attacker, "CRITICAL_RATE"));
        if (critRate > 0 && rng.NextDouble() * 100 < critRate)
        {
            var critDamage = BalanceCombatData.Stat(attacker, "CRITICAL_DAMAGE");
            damage *= (critDamage <= 0 ? 150 : critDamage) / 100;
        }
        var superRate = Math.Max(0, BalanceCombatData.Stat(attacker, "SUPER_CRITICAL_RATE"));
        if (playerAgainstMob && superRate > 0 && rng.NextDouble() * 100 < superRate)
        {
            var extra = BalanceCombatData.Stat(attacker, "SUPER_CRITICAL_DAMAGE");
            damage *= 1 + (extra <= 0 ? 30 : extra) / 100;
        }
        var effectiveDefense = BalanceCombatMath.EffectiveDefense(BalanceCombatData.Stat(victim, "DEFENSE"),
            BalanceCombatData.Stat(victim, defense), BalanceCombatData.Stat(attacker, "DEFENSE_PENETRATION_RATE"),
            BalanceCombatData.Stat(attacker, penetration));
        damage *= BalanceCombatMath.DefenseMultiplier(attack, effectiveDefense);
        var resistanceCap = BalanceCombatData.Stat(victim, element + "_RESISTANCE_CAP", element == "NONE" ? 50 : 75);
        var resistance = Math.Min(BalanceCombatData.Stat(victim, element + "_RESISTANCE"), resistanceCap)
            - (element == "NONE" ? 0 : BalanceCombatData.Stat(attacker, element + "_PENETRATION"));
        damage *= Math.Max(0, 1 + BalanceCombatData.Stat(attacker, element + "_DAMAGE_INCREASE") / 100)
            * Math.Max(0, 1 - resistance / 100);
        var finalMultiplier = BalanceCombatData.Stat(attacker, "FINAL_DAMAGE_MULTIPLIER", playerAgainstMob ? 100 : 100);
        damage *= Math.Max(0, finalMultiplier) / 100;
        return Math.Max(0, damage);
    }

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
