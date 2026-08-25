package io.github.maaasu.astralRecord.feature.mob.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeConfig;
import io.github.maaasu.astralRecord.feature.boss.model.BossLocation;
import io.github.maaasu.astralRecord.feature.boss.model.BossScalingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.CombatStyle;
import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCombatConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionActionConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobLevelProfile;
import io.github.maaasu.astralRecord.feature.mob.model.MobMoneyDrop;
import io.github.maaasu.astralRecord.feature.mob.model.MobNormalAttackConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkin;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * AstralRecord API を通じて Mob テンプレートを取得するリポジトリ。
 *
 * <p>API レスポンスは {@code item:rusty_sword} のような prefix 付き参照値で返るため、
 * 本リポジトリで {@code :} 区切りの suffix のみを抽出して保持する。</p>
 */
public class MobRepository {

    /** レベルプロファイルで上書きを許可する共通 Mob 項目です。 */
    private static final Set<String> LEVEL_PROFILE_FIELDS = Set.of(
            "name", "title", "nameVisible", "icon", "lore", "tags", "skin", "variant",
            "equipment", "baseStats", "shield", "ai", "damageImmune", "interactions",
            "drops", "challenge"
    );

    /**
     * Mob 一覧を取得します。
     *
     * @return ロードした Mob テンプレートのリスト（順序は API レスポンス順）
     */
    @NotNull
    public List<MobTemplate> findAll() {
        String path = "/api/mob";

        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    Logger.log(LogId.E_5701, "status=" + response.statusCode());
                    throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                }

                JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
                List<MobTemplate> result = new ArrayList<>();
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) continue;
                    // summary には category や level のみが入る。詳細を取得するため findById で再取得する
                    JsonObject summary = element.getAsJsonObject();
                    String id = optionalString(summary, "id");
                    if (id == null) continue;
                    MobTemplate template = findById(id);
                    if (template != null) {
                        result.add(template);
                    }
                }
                return result;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 指定 ID の Mob テンプレートを取得します。
     *
     * @param mobId Mob テンプレート ID
     * @return 取得したテンプレート。存在しない場合は {@code null}
     */
    @Nullable
    public MobTemplate findById(@NotNull String mobId) {
        String encoded = URLEncoder.encode(mobId, StandardCharsets.UTF_8).replace("+", "%20");
        String path = "/api/mob/" + encoded;

        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                return switch (response.statusCode()) {
                    case 200 -> parseTemplate(JsonParser.parseString(response.body()).getAsJsonObject());
                    case 404 -> {
                        Logger.log(LogId.W_5700, mobId);
                        yield null;
                    }
                    default -> {
                        Logger.log(LogId.E_5700, mobId + " status=" + response.statusCode());
                        throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                    }
                };
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    MobTemplate parseTemplate(@NotNull JsonObject obj) {
        String id = optionalString(obj, "id");
        if (id == null) return null;

        MobCategory category = MobCategory.from(optionalString(obj, "category"));
        String entityTypeName = optionalString(obj, "entityType");
        EntityType entityType = resolveEntityType(entityTypeName, category);
        Material blockMaterial = entityType == null ? resolveBlockMaterial(entityTypeName) : null;
        if (blockMaterial != null) {
            entityType = EntityType.BLOCK_DISPLAY;
        }
        if (entityType == null) {
            Logger.log(LogId.W_5705, entityTypeName, id);
            return null;
        }

        Logger.log(LogId.D_5700, id);
        MobTemplate base = parseTemplateFields(obj, id, category, entityType, entityTypeName, blockMaterial);
        List<MobLevelProfile> profiles = parseLevelProfiles(
                obj, id, category, entityType, entityTypeName, blockMaterial
        );
        return base.withLevelProfiles(profiles).resolveLevel(null);
    }

    private @NotNull MobTemplate parseTemplateFields(
            @NotNull JsonObject obj,
            @NotNull String id,
            @NotNull MobCategory category,
            @NotNull EntityType entityType,
            @Nullable String entityTypeName,
            @Nullable Material blockMaterial
    ) {
        return new MobTemplate(
                obj.has("schemaVersion") ? obj.get("schemaVersion").getAsInt() : 1,
                id,
                category,
                optionalString(obj, "name", id),
                optionalString(obj, "title"),
                obj.has("level") ? obj.get("level").getAsInt() : 1,
                entityType,
                entityTypeName,
                blockMaterial,
                obj.has("nameVisible") ? obj.get("nameVisible").getAsBoolean() : true,
                optionalString(obj, "icon"),
                parseStringArray(obj.getAsJsonArray("lore")),
                parseStringArray(obj.getAsJsonArray("tags")),
                parseSkin(getObject(obj, "skin")),
                parseVariant(getObject(obj, "variant")),
                parseEquipment(getObject(obj, "equipment")),
                parseBaseStats(obj.getAsJsonArray("baseStats"), id),
                parseShield(getObject(obj, "shield")),
                parseIdle(getObject(getObject(obj, "ai"), "idle")),
                obj.has("damageImmune") ? obj.get("damageImmune").getAsBoolean() : category == MobCategory.NPC,
                category == MobCategory.NPC ? parseInteractions(getObject(obj, "interactions")) : MobInteractionsConfig.EMPTY,
                category == MobCategory.NPC ? null : parseTargeting(getObject(getObject(obj, "ai"), "targeting")),
                category == MobCategory.NPC ? null : parseCombat(getObject(getObject(obj, "ai"), "combat")),
                category == MobCategory.NPC ? null : parseDrops(getObject(obj, "drops")),
                category == MobCategory.BOSS ? parseChallenge(getObject(obj, "challenge")) : null
        );
    }

    private @NotNull List<MobLevelProfile> parseLevelProfiles(
            @NotNull JsonObject root,
            @NotNull String mobId,
            @NotNull MobCategory category,
            @NotNull EntityType entityType,
            @Nullable String entityTypeName,
            @Nullable Material blockMaterial
    ) {
        JsonElement levelsElement = root.get("levels");
        if (levelsElement == null || !levelsElement.isJsonArray()) {
            return List.of();
        }

        List<MobLevelProfile> profiles = new ArrayList<>();
        Set<Integer> registeredLevels = new HashSet<>();
        for (JsonElement element : levelsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject profile = element.getAsJsonObject();
            Integer level = integerValue(profile, "level");
            if (level == null || level < 1 || !registeredLevels.add(level)) {
                continue;
            }

            JsonObject merged = mergeLevelProfile(root, profile);
            merged.addProperty("level", level);
            MobTemplate effective = parseTemplateFields(
                    merged, mobId, category, entityType, entityTypeName, blockMaterial
            );
            profiles.add(MobLevelProfile.from(effective));
        }
        return List.copyOf(profiles);
    }

    /** 共通定義へプロファイルの許可項目だけを再帰的に重ねます。 */
    private @NotNull JsonObject mergeLevelProfile(
            @NotNull JsonObject root,
            @NotNull JsonObject profile
    ) {
        JsonObject merged = root.deepCopy();
        merged.remove("levels");
        for (Map.Entry<String, JsonElement> entry : profile.entrySet()) {
            if (!LEVEL_PROFILE_FIELDS.contains(entry.getKey())) {
                continue;
            }
            JsonElement current = merged.get(entry.getKey());
            JsonElement override = entry.getValue();
            if (current != null && current.isJsonObject() && override.isJsonObject()) {
                merged.add(entry.getKey(), mergeJsonObjects(current.getAsJsonObject(), override.getAsJsonObject()));
            } else {
                merged.add(entry.getKey(), override.deepCopy());
            }
        }
        return merged;
    }

    private @NotNull JsonObject mergeJsonObjects(
            @NotNull JsonObject base,
            @NotNull JsonObject override
    ) {
        JsonObject merged = base.deepCopy();
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            JsonElement current = merged.get(entry.getKey());
            JsonElement value = entry.getValue();
            if (current != null && current.isJsonObject() && value.isJsonObject()) {
                merged.add(entry.getKey(), mergeJsonObjects(current.getAsJsonObject(), value.getAsJsonObject()));
            } else {
                merged.add(entry.getKey(), value.deepCopy());
            }
        }
        return merged;
    }

    @Nullable
    private EntityType resolveEntityType(@Nullable String entityTypeName, @NotNull MobCategory category) {
        if (entityTypeName == null || entityTypeName.isBlank()) {
            return null;
        }

        String normalized = entityTypeName.trim().toUpperCase();
        if ("PLAYER".equals(normalized)) {
            return category == MobCategory.NPC ? EntityType.VILLAGER : EntityType.ZOMBIE;
        }

        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Nullable
    private Material resolveBlockMaterial(@Nullable String entityTypeName) {
        if (entityTypeName == null || entityTypeName.isBlank()) {
            return null;
        }

        try {
            Material material = Material.valueOf(entityTypeName.trim().toUpperCase());
            return material.isBlock() ? material : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Nullable
    private MobSkin parseSkin(@Nullable JsonObject obj) {
        if (obj == null) return null;
        return new MobSkin(optionalString(obj, "texture"), optionalString(obj, "signature"));
    }

    @NotNull
    private MobVariantConfig parseVariant(@Nullable JsonObject obj) {
        if (obj == null) return MobVariantConfig.DEFAULT;
        Integer villagerLevel = null;
        if (obj.has("villagerLevel") && !obj.get("villagerLevel").isJsonNull() && obj.get("villagerLevel").isJsonPrimitive()) {
            villagerLevel = obj.get("villagerLevel").getAsInt();
        }
        return new MobVariantConfig(
                MobVariantConfig.Age.fromRaw(optionalString(obj, "age")),
                optionalString(obj, "kind"),
                optionalString(obj, "color"),
                optionalString(obj, "style"),
                optionalString(obj, "profession"),
                optionalString(obj, "villagerType"),
                villagerLevel,
                optionalString(obj, "pattern"),
                optionalString(obj, "bodyColor"),
                optionalString(obj, "patternColor"),
                optionalString(obj, "mainGene"),
                optionalString(obj, "hiddenGene")
        );
    }

    @NotNull
    private MobEquipmentConfig parseEquipment(@Nullable JsonObject obj) {
        if (obj == null) return MobEquipmentConfig.EMPTY;
        return new MobEquipmentConfig(
                stripPrefix(optionalString(obj, "mainHand")),
                stripPrefix(optionalString(obj, "offHand")),
                stripPrefix(optionalString(obj, "helmet")),
                stripPrefix(optionalString(obj, "chestplate")),
                stripPrefix(optionalString(obj, "leggings")),
                stripPrefix(optionalString(obj, "boots"))
        );
    }

    @NotNull
    private MobShieldConfig parseShield(@Nullable JsonObject obj) {
        if (obj == null) return MobShieldConfig.EMPTY;
        boolean enabled = obj.has("enabled") && obj.get("enabled").getAsBoolean();
        double max = obj.has("max") && !obj.get("max").isJsonNull()
                ? obj.get("max").getAsDouble()
                : 0.0D;
        Double rechargeTimeSeconds = obj.has("rechargeTimeSeconds") && !obj.get("rechargeTimeSeconds").isJsonNull()
                ? obj.get("rechargeTimeSeconds").getAsDouble()
                : null;
        Double rechargeAmount = obj.has("rechargeAmount") && !obj.get("rechargeAmount").isJsonNull()
                ? obj.get("rechargeAmount").getAsDouble()
                : null;
        return new MobShieldConfig(enabled, max, rechargeTimeSeconds, rechargeAmount).normalized();
    }

    @NotNull
    private List<MobBaseStat> parseBaseStats(@Nullable JsonArray array, @NotNull String mobId) {
        if (array == null) return List.of();
        List<MobBaseStat> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            String status = optionalString(obj, "status");
            JsonElement valueElement = obj.get("value");
            if (status == null || valueElement == null || !valueElement.isJsonPrimitive()) continue;

            // StatusType に存在しない status はスキップ（forward-compat）
            try {
                StatusType.valueOf(status);
            } catch (IllegalArgumentException ex) {
                Logger.log(LogId.W_5704, status, mobId);
                continue;
            }
            result.add(new MobBaseStat(status, valueElement.getAsDouble()));
        }
        return Collections.unmodifiableList(result);
    }

    @NotNull
    private MobIdleConfig parseIdle(@Nullable JsonObject obj) {
        if (obj == null) return MobIdleConfig.defaults();
        return new MobIdleConfig(
                IdleBehavior.from(optionalString(obj, "behavior")),
                obj.has("wanderRadius") ? obj.get("wanderRadius").getAsDouble() : 10.0,
                obj.has("speed") ? obj.get("speed").getAsDouble() : 1.0
        );
    }

    @Nullable
    private MobTargetingConfig parseTargeting(@Nullable JsonObject obj) {
        if (obj == null) return null;
        double aggro = obj.has("aggroRange") ? obj.get("aggroRange").getAsDouble() : 0.0;
        double deaggro = obj.has("deaggroRange") && !obj.get("deaggroRange").isJsonNull()
                ? obj.get("deaggroRange").getAsDouble()
                : aggro * 2.0;
        double leash = obj.has("leashRange") ? obj.get("leashRange").getAsDouble() : 30.0;
        boolean retaliateOnly = obj.has("retaliateOnly") && obj.get("retaliateOnly").getAsBoolean();
        return new MobTargetingConfig(
                TargetStrategyFrom(optionalString(obj, "strategy")),
                aggro,
                deaggro,
                leash,
                retaliateOnly
        );
    }

    @Nullable
    private MobCombatConfig parseCombat(@Nullable JsonObject obj) {
        if (obj == null) return null;
        double preferredRange = obj.has("preferredRange") ? obj.get("preferredRange").getAsDouble() : 1.0D;
        JsonObject normalAttackObj = getObject(obj, "normalAttack");
        MobNormalAttackConfig normalAttack = normalAttackObj == null
                ? legacyNormalAttack(obj, preferredRange)
                : new MobNormalAttackConfig(
                        normalAttackObj.has("range") ? normalAttackObj.get("range").getAsDouble() : preferredRange,
                        normalAttackObj.has("intervalTicks") ? normalAttackObj.get("intervalTicks").getAsLong() : 20L
                );
        return new MobCombatConfig(
                CombatStyle.from(optionalString(obj, "style")),
                preferredRange,
                normalAttack,
                parseSkillBindings(getArray(obj, "skills"))
        );
    }

    @Nullable
    private MobNormalAttackConfig legacyNormalAttack(@NotNull JsonObject combat, double preferredRange) {
        if (!combat.has("attackIntervalTicks")) {
            return null;
        }
        return new MobNormalAttackConfig(preferredRange, combat.get("attackIntervalTicks").getAsLong());
    }

    @NotNull
    private List<MobSkillBinding> parseSkillBindings(@Nullable JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<MobSkillBinding> bindings = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String id = element.getAsString();
                if (id.isBlank()) {
                    throw new IllegalArgumentException("ai.combat.skills の文字列IDは空にできません");
                }
                bindings.add(new MobSkillBinding(id, null, null, null, Map.of()));
                continue;
            }
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("ai.combat.skills の各要素は文字列IDまたはobjectで指定してください");
            }
            JsonObject object = element.getAsJsonObject();
            String id = optionalString(object, "id");
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("ai.combat.skills[].id は必須です");
            }
            Map<String, Double> params = new LinkedHashMap<>();
            JsonObject paramsObject = getObject(object, "params");
            if (paramsObject != null) {
                for (Map.Entry<String, JsonElement> entry : paramsObject.entrySet()) {
                    if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isNumber()) {
                        throw new IllegalArgumentException("Mob skill params は数値で指定してください: " + id + "." + entry.getKey());
                    }
                    params.put(entry.getKey(), entry.getValue().getAsDouble());
                }
            }
            bindings.add(new MobSkillBinding(
                    id,
                    optionalDouble(object, "activationRange"),
                    optionalLong(object, "cooldownTicks"),
                    optionalLong(object, "castTimeTicks"),
                    params
            ));
        }
        return List.copyOf(bindings);
    }

    @Nullable
    private Double optionalDouble(@NotNull JsonObject object, @NotNull String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : null;
    }

    @Nullable
    private Long optionalLong(@NotNull JsonObject object, @NotNull String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : null;
    }

    @Nullable
    private JsonArray getArray(@Nullable JsonObject object, @NotNull String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(key);
    }

    @Nullable
    private BossChallengeConfig parseChallenge(@Nullable JsonObject obj) {
        if (obj == null) return null;
        String fieldWorldId = optionalString(obj, "fieldWorldId");
        if (fieldWorldId == null || fieldWorldId.isBlank()) {
            return null;
        }
        return new BossChallengeConfig(
                fieldWorldId,
                parseBossLocation(getObject(obj, "entryLocation")),
                obj.has("entryRadius") ? obj.get("entryRadius").getAsDouble() : 3.0D,
                parseBossLocation(getObject(obj, "playerSpawnLocation")),
                parseBossLocation(getObject(obj, "bossSpawnLocation")),
                obj.has("partyMin") ? obj.get("partyMin").getAsInt() : 1,
                obj.has("partyMax") ? obj.get("partyMax").getAsInt() : 6,
                obj.has("timeLimitSeconds") ? obj.get("timeLimitSeconds").getAsLong() : 600L,
                obj.has("deathLimit") ? obj.get("deathLimit").getAsInt() : 5,
                obj.has("reviveDelaySeconds") ? obj.get("reviveDelaySeconds").getAsLong() : 5L,
                parseBossScaling(getObject(obj, "scaling"))
        );
    }

    @NotNull
    private BossLocation parseBossLocation(@Nullable JsonObject obj) {
        if (obj == null) {
            return new BossLocation(null, 0.5D, 64.0D, 0.5D, 0.0F, 0.0F);
        }
        return new BossLocation(
                optionalString(obj, "worldId"),
                obj.has("x") ? obj.get("x").getAsDouble() : 0.5D,
                obj.has("y") ? obj.get("y").getAsDouble() : 64.0D,
                obj.has("z") ? obj.get("z").getAsDouble() : 0.5D,
                obj.has("yaw") ? obj.get("yaw").getAsFloat() : 0.0F,
                obj.has("pitch") ? obj.get("pitch").getAsFloat() : 0.0F
        );
    }

    @NotNull
    private BossScalingConfig parseBossScaling(@Nullable JsonObject obj) {
        if (obj == null) return BossScalingConfig.EMPTY;
        return new BossScalingConfig(
                obj.has("enabled") && obj.get("enabled").getAsBoolean(),
                obj.has("healthPerExtraPlayer") ? obj.get("healthPerExtraPlayer").getAsDouble() : 0.0D,
                obj.has("attackPerExtraPlayer") ? obj.get("attackPerExtraPlayer").getAsDouble() : 0.0D
        );
    }

    @NotNull
    private MobInteractionsConfig parseInteractions(@Nullable JsonObject obj) {
        if (obj == null) return MobInteractionsConfig.EMPTY;
        return new MobInteractionsConfig(
                parseInteractionActions(obj.getAsJsonArray("leftClick")),
                parseInteractionActions(obj.getAsJsonArray("rightClick"))
        );
    }

    @NotNull
    private List<MobInteractionActionConfig> parseInteractionActions(@Nullable JsonArray array) {
        if (array == null) return List.of();
        List<MobInteractionActionConfig> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject actionObj = element.getAsJsonObject();
            String id = optionalString(actionObj, "id");
            if (id == null || id.isBlank()) continue;
            result.add(new MobInteractionActionConfig(id, parseInteractionParams(getObject(actionObj, "params"))));
        }
        return Collections.unmodifiableList(result);
    }

    @NotNull
    private Map<String, String> parseInteractionParams(@Nullable JsonObject obj) {
        if (obj == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String value = primitiveToString(entry.getValue());
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Nullable
    private MobDropConfig parseDrops(@Nullable JsonObject obj) {
        if (obj == null) return null;
        int exp = obj.has("exp") ? obj.get("exp").getAsInt() : 0;

        MobMoneyDrop money = null;
        JsonObject moneyObj = getObject(obj, "money");
        if (moneyObj != null) {
            money = new MobMoneyDrop(
                    moneyObj.has("min") ? moneyObj.get("min").getAsInt() : 0,
                    moneyObj.has("max") ? moneyObj.get("max").getAsInt() : 0
            );
        }

        List<MobDropItem> items = new ArrayList<>();
        JsonArray itemsArray = obj.getAsJsonArray("items");
        if (itemsArray != null) {
            for (JsonElement element : itemsArray) {
                if (!element.isJsonObject()) continue;
                JsonObject itemObj = element.getAsJsonObject();
                String itemId = stripPrefix(optionalString(itemObj, "itemId"));
                if (itemId == null) continue;
                items.add(new MobDropItem(
                        itemId,
                        itemObj.has("rate") ? itemObj.get("rate").getAsDouble() : 0.0,
                        optionalString(itemObj, "amount", "1"),
                        !itemObj.has("luckAffected") || itemObj.get("luckAffected").getAsBoolean(),
                        itemObj.has("hidden") && itemObj.get("hidden").getAsBoolean()
                ));
            }
        }

        return new MobDropConfig(exp, money, items, stripPrefix(optionalString(obj, "lootTable")));
    }

    @Nullable
    private static String primitiveToString(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        return element.getAsString();
    }

    /**
     * {@code "item:rusty_sword"} のような prefix 付き参照値から、{@code ":"} 以降の suffix を返します。
     * prefix がない場合はそのまま返します。
     *
     * @param raw 参照値
     * @return suffix のみの素の ID
     */
    @Nullable
    private static String stripPrefix(@Nullable String raw) {
        if (raw == null) return null;
        int idx = raw.indexOf(':');
        return idx < 0 ? raw : raw.substring(idx + 1);
    }

    @NotNull
    private static List<String> parseStringArray(@Nullable JsonArray array) {
        if (array == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Nullable
    private static JsonObject getObject(@Nullable JsonObject obj, @NotNull String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement element = obj.get(key);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    @Nullable
    private static String optionalString(@Nullable JsonObject obj, @NotNull String key) {
        return optionalString(obj, key, null);
    }

    private static String optionalString(@Nullable JsonObject obj, @NotNull String key, @Nullable String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        JsonElement element = obj.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    @Nullable
    private static Integer integerValue(@Nullable JsonObject obj, @NotNull String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = obj.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 戦略文字列から {@link io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy} を解決します。
     * <p>循環インポートを避けるためメソッド単位で参照を分離しています。</p>
     *
     * @param raw 戦略文字列
     * @return 解決された戦略
     */
    private static io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy TargetStrategyFrom(@Nullable String raw) {
        return io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy.from(raw);
    }
}
