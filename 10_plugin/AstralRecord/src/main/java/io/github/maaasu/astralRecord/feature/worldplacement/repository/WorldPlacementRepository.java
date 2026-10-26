package io.github.maaasu.astralRecord.feature.worldplacement.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.mob.model.NpcPlacement;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** API が保持する全配置種別の一貫した revision を読み取るリポジトリです。 */
public final class WorldPlacementRepository {
    private final Plugin plugin;

    /**
     * @param plugin 配置設定を持つプラグイン
     */
    public WorldPlacementRepository(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * API モードの設定を返します。
     * @return API モードなら true
     */
    public boolean isApiMode() {
        return "api".equalsIgnoreCase(plugin.getConfig().getString("worldPlacements.mode", "local"));
    }

    /**
     * 全種別を一回の API 要求で取得、完全検証して準備します。非同期スレッドから呼び出します。
     * @return 公開可能な不変スナップショット
     * @throws IllegalStateException 通信または契約検証の失敗時
     */
    public @NotNull Snapshot fetchSnapshot() {
        String environment = plugin.getConfig().getString("worldPlacements.environment", "");
        if (!"dev".equals(environment) && !"channels".equals(environment)) {
            throw new IllegalStateException("worldPlacements.environment must be dev or channels");
        }
        String baseUrl = plugin.getConfig().getString("worldPlacements.baseUrl", "").trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("worldPlacements.baseUrl is required in API mode");
        }
        URI base = URI.create(baseUrl);
        if (!"https".equalsIgnoreCase(base.getScheme()) &&
                !("http".equalsIgnoreCase(base.getScheme()) &&
                    ("localhost".equalsIgnoreCase(base.getHost()) || "127.0.0.1".equals(base.getHost())
                        || "[::1]".equals(base.getHost())))) {
            throw new IllegalStateException("worldPlacements.baseUrl requires HTTPS or loopback HTTP");
        }
        String path = "/api/world-placements/" + environment;
        String normalizedBase = baseUrl.replaceAll("/+$", "");
        if (normalizedBase.endsWith("/api")) {
            path = path.substring(4);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(normalizedBase + path))
            .timeout(Duration.ofMillis(Math.max(1000, plugin.getConfig().getLong("api.timeout", 30000))))
            .header("Accept", "application/json").GET();
        String apiKey = plugin.getConfig().getString("api.auth.apiKey", "");
        if (!apiKey.isBlank()) {
            request.header("X-Api-Key", apiKey);
        }
        try {
            HttpResponse<String> response = ApiRequestUtil.sharedClient().send(
                request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("World placements HTTP " + response.statusCode());
            }
            return parseSnapshot(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("World placements request interrupted", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("World placements request failed", ex);
        }
    }

    /**
     * API 応答を公開前に全件検証します。ロードされていない Bukkit world の行も保持します。
     * @param json API 応答本文
     * @return 検証済みスナップショット
     * @throws IllegalStateException 応答契約に反する行がある場合
     */
    public static @NotNull Snapshot parseSnapshot(@NotNull String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.get("schemaVersion").getAsInt() != 1) {
            throw new IllegalStateException("Unsupported placement schema version");
        }
        String revision = requiredString(root, "revision");
        if (!root.has("placements") || !root.get("placements").isJsonArray()) {
            throw new IllegalStateException("Placement array is missing");
        }
        List<MobSpawnerLocation> spawners = new ArrayList<>();
        List<NpcPlacement> npcs = new ArrayList<>();
        List<WaystoneDefinition> waystones = new ArrayList<>();
        Set<String> placementIds = new HashSet<>();
        Set<String> spawnerCoordinates = new HashSet<>();
        Set<String> npcCoordinates = new HashSet<>();
        Set<String> waystoneIds = new HashSet<>();
        for (JsonElement element : root.getAsJsonArray("placements")) {
            JsonObject row = element.getAsJsonObject();
            String placementId = requiredString(row, "placementId");
            if (!placementId.matches("[A-Za-z0-9_.:-]{1,128}") || !placementIds.add(placementId)) {
                throw new IllegalStateException("Duplicate or invalid placement ID");
            }
            String kind = requiredString(row, "kind");
            String worldId = requiredString(row, "worldId");
            String worldName = requiredString(row, "worldName");
            if (!validWorldName(worldId) || !validWorldName(worldName)) {
                throw new IllegalStateException("Invalid placement world");
            }
            double x = finiteNumber(row, "x");
            double y = finiteNumber(row, "y");
            double z = finiteNumber(row, "z");
            float yaw = (float) finiteNumber(row, "yaw");
            float pitch = (float) finiteNumber(row, "pitch");
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalStateException("Non-finite placement rotation");
            }
            boolean enabled = Boolean.parseBoolean(requiredBoolean(row, "enabled"));
            switch (kind) {
                case "spawner" -> {
                    String templateId = requiredId(row, "templateId");
                    requireNoWaystone(row);
                    if (x != Math.floor(x) || y != Math.floor(y) || z != Math.floor(z)
                        || x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                        || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                        || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
                        throw new IllegalStateException("Spawner coordinates must be block integers");
                    }
                    MobSpawnerLocation placement = new MobSpawnerLocation(templateId, worldName, (int)x, (int)y, (int)z);
                    if (enabled && !spawnerCoordinates.add(placement.locationKey())) {
                        throw new IllegalStateException("Duplicate spawner coordinate");
                    }
                    if (enabled) spawners.add(placement);
                }
                case "npc" -> {
                    String templateId = requiredId(row, "templateId");
                    requireNoWaystone(row);
                    NpcPlacement placement = new NpcPlacement(templateId, worldName, x, y, z, yaw, pitch);
                    if (enabled && !npcCoordinates.add(placement.locationKey())) {
                        throw new IllegalStateException("Duplicate NPC coordinate");
                    }
                    if (enabled) npcs.add(placement);
                }
                case "waystone" -> {
                    if (!row.has("templateId") || !row.get("templateId").isJsonNull()) {
                        throw new IllegalStateException("Waystone templateId must be null");
                    }
                    if (!row.has("waystone") || !row.get("waystone").isJsonObject()) {
                        throw new IllegalStateException("Waystone payload is missing");
                    }
                    JsonObject detail = row.getAsJsonObject("waystone");
                    String id = requiredId(detail, "id");
                    if (enabled && !waystoneIds.add(id)) {
                        throw new IllegalStateException("Duplicate waystone ID");
                    }
                    Material icon = null;
                    String iconName = nullableString(detail, "icon");
                    if (iconName != null) {
                        icon = Material.matchMaterial(iconName);
                        if (icon != null && (icon.isAir() || !icon.isItem())) icon = null;
                    }
                    long unlockGold = detail.get("unlockGold").getAsLong();
                    if (unlockGold < 0) {
                        throw new IllegalStateException("Invalid waystone unlockGold");
                    }
                    WaystoneDefinition placement = new WaystoneDefinition(id, requiredString(detail, "name"), worldName,
                        x, y, z, yaw, pitch, Boolean.parseBoolean(requiredBoolean(detail, "lockEnabled")),
                        unlockGold, icon, nullableString(detail, "createdAt") == null
                            ? Instant.EPOCH : Instant.parse(requiredString(detail, "createdAt")),
                        nullableString(detail, "createdBy") == null ? "system" : requiredString(detail, "createdBy"));
                    if (enabled) waystones.add(placement);
                }
                default -> throw new IllegalStateException("Unknown placement kind");
            }
        }
        return new Snapshot(revision, List.copyOf(spawners), List.copyOf(npcs), List.copyOf(waystones));
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank()) {
            throw new IllegalStateException("Invalid placement field: " + key);
        }
        return value.getAsString();
    }

    private static String nullableString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()
            || value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && value.getAsString().isBlank()) return null;
        return requiredString(object, key);
    }

    private static String requiredId(JsonObject object, String key) {
        String id = requiredString(object, key);
        if (!id.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalStateException("Invalid placement ID field: " + key);
        }
        return id;
    }

    private static void requireNoWaystone(JsonObject row) {
        if (!row.has("waystone") || !row.get("waystone").isJsonNull()) {
            throw new IllegalStateException("Non-waystone payload must be null");
        }
    }

    private static boolean validWorldName(String value) {
        return value.length() <= 512 && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static String requiredBoolean(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalStateException("Invalid placement field: " + key);
        }
        return value.getAsString();
    }

    private static double finiteNumber(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Invalid placement field: " + key);
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) {
            throw new IllegalStateException("Non-finite placement coordinate");
        }
        return number;
    }

    /** 全種別で同じ revision を持つ公開単位です。 */
    public record Snapshot(String revision, List<MobSpawnerLocation> spawners,
                           List<NpcPlacement> npcs, List<WaystoneDefinition> waystones) {}
}
