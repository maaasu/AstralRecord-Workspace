package io.github.maaasu.astralRecord.feature.worldplacement.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** API の配置スナップショットを読み取り、Dev のウェイストーンを新規登録します。 */
public final class WorldPlacementRepository {
    private static final String DEV_ENVIRONMENT = "dev";
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

    /** @return Dev の配置 API モードなら true */
    public boolean isDevApiMode() {
        return isApiMode() && DEV_ENVIRONMENT.equals(plugin.getConfig().getString("worldPlacements.environment"));
    }

    /** @return Dev 配置更新キーが設定されていれば true */
    public boolean hasWriteKey() {
        return !plugin.getConfig().getString("worldPlacements.writeKey", "").isBlank();
    }

    /**
     * 全種別を一回の API 要求で取得、完全検証して準備します。非同期スレッドから呼び出します。
     * @return 公開可能な不変スナップショット
     * @throws IllegalStateException 通信または契約検証の失敗時
     */
    public @NotNull Snapshot fetchSnapshot() {
        String environment = plugin.getConfig().getString("worldPlacements.environment", "");
        if (!DEV_ENVIRONMENT.equals(environment) && !"channels".equals(environment)) {
            throw new IllegalStateException("worldPlacements.environment must be dev or channels");
        }
        return fetchSnapshot(ApiRequestUtil.sharedClient(), snapshotUri(environment));
    }

    /**
     * Dev の最新 revision を取得してウェイストーンを一件登録します。非同期スレッドから呼び出します。
     *
     * @param worldId ワールドマスターの論理 ID
     * @param definition 実行時 API に依存しない登録用定義
     * @return 登録を確認できた場合 true、revision が競合した場合 false
     * @throws IllegalStateException 設定、通信、応答検証または API エラー時
     */
    public boolean createDevWaystone(@NotNull String worldId, @NotNull WaystoneDefinition definition) {
        String writeKey = plugin.getConfig().getString("worldPlacements.writeKey", "");
        if (!isDevApiMode() || writeKey.isBlank()) {
            throw new IllegalStateException("Dev world placement write is disabled");
        }
        if (writeKey.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw new IllegalStateException("Invalid Dev world placement write key");
        }
        URI uri = snapshotUri(DEV_ENVIRONMENT);
        // 配置更新キーの通信には、共通 API の証明書検証無効設定を引き継がない。
        HttpClient client = HttpClient.newHttpClient();
        try {
            String revision = fetchSnapshot(client, uri).revision();
            String placementId = UUID.randomUUID().toString();
            HttpRequest request = requestBuilder(URI.create(uri + "/" + placementId))
                .header("Content-Type", "application/json")
                .header("X-World-Placement-Key", writeKey)
                .PUT(HttpRequest.BodyPublishers.ofString(createWaystoneBody(revision, placementId, worldId, definition)))
                .build();
            HttpResponse<String> response = send(client, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                return false;
            }
            requireSuccess(response.statusCode());
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            Snapshot saved = parseSnapshot(root);
            if (saved.revision().equals(revision) || !containsWaystone(root, placementId, definition.id())) {
                throw new IllegalStateException("Dev placement response did not confirm the saved waystone");
            }
            return true;
        } finally {
            client.shutdownNow();
        }
    }

    /** 検証済み応答の同一行に、送信した両方の ID があることを確認します。 */
    private static boolean containsWaystone(JsonObject root, String placementId, String waystoneId) {
        for (JsonElement element : root.getAsJsonArray("placements")) {
            JsonObject row = element.getAsJsonObject();
            if (placementId.equals(row.get("placementId").getAsString())) {
                return "waystone".equals(row.get("kind").getAsString()) && row.get("enabled").getAsBoolean()
                    && waystoneId.equals(row.getAsJsonObject("waystone").get("id").getAsString());
            }
        }
        return false;
    }

    /** 指定環境の完全なスナップショットを読み込みます。 */
    private @NotNull Snapshot fetchSnapshot(HttpClient client, @NotNull URI uri) {
        HttpResponse<String> response = send(client, requestBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode());
        return parseSnapshot(response.body());
    }

    /** 配置 API 専用のエンドポイントを構成し、平文は loopback に限定します。 */
    private @NotNull URI snapshotUri(@NotNull String environment) {
        String baseUrl = plugin.getConfig().getString("worldPlacements.baseUrl", "").trim();
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("worldPlacements.baseUrl is required in API mode");
        }
        URI base = URI.create(baseUrl);
        String host = base.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        boolean secureTransport = "https".equalsIgnoreCase(base.getScheme())
            || ("http".equalsIgnoreCase(base.getScheme()) && loopback);
        if (!secureTransport || host == null || base.getRawQuery() != null || base.getRawFragment() != null
                || base.getRawUserInfo() != null) {
            throw new IllegalStateException("worldPlacements.baseUrl requires HTTPS or loopback HTTP");
        }
        String path = "/api/world-placements/" + environment;
        String normalizedBase = baseUrl.replaceAll("/+$", "");
        if (normalizedBase.endsWith("/api")) {
            path = path.substring(4);
        }
        return URI.create(normalizedBase + path);
    }

    /** 配置 API 共通の認証、タイムアウトを設定します。 */
    private HttpRequest.Builder requestBuilder(@NotNull URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(Math.max(1000, plugin.getConfig().getLong("api.timeout", 30000))))
            .header("Accept", "application/json");
        String apiKey = plugin.getConfig().getString("api.auth.apiKey", "");
        if (!apiKey.isBlank()) {
            request.header("X-Api-Key", apiKey);
        }
        return request;
    }

    /** ステータスを検証し、応答本文は例外に含めません。 */
    private static void requireSuccess(int status) {
        if (status != 200) {
            throw new IllegalStateException("World placements HTTP " + status);
        }
    }

    /** 指定した HTTP クライアントで本文受信まで待機し、期限切れや割り込み時は要求を取り消します。 */
    private static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        var pending = client.sendAsync(request, handler);
        try {
            return pending.get(request.timeout().orElseThrow().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("World placements request interrupted", ex);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new IllegalStateException("World placements request timed out", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("World placements request failed", ex.getCause());
        }
    }

    /** 単件更新 API の JSON 契約へウェイストーンの定義を変換します。 */
    private static @NotNull String createWaystoneBody(String revision, String placementId, String worldId,
                                                      WaystoneDefinition definition) {
        JsonObject detail = new JsonObject();
        detail.addProperty("id", definition.id());
        detail.addProperty("name", definition.name());
        detail.addProperty("lockEnabled", definition.lockEnabled());
        detail.addProperty("unlockGold", definition.unlockGold());
        detail.addProperty("icon", definition.icon() == null ? null : definition.icon().name());
        detail.addProperty("createdAt", definition.createdAt().toString());
        detail.addProperty("createdBy", definition.createdBy());

        JsonObject placement = new JsonObject();
        placement.addProperty("placementId", placementId);
        placement.addProperty("kind", "waystone");
        placement.addProperty("worldId", worldId);
        placement.addProperty("worldName", definition.worldName());
        placement.add("templateId", JsonNull.INSTANCE);
        placement.addProperty("x", definition.x());
        placement.addProperty("y", definition.y());
        placement.addProperty("z", definition.z());
        placement.addProperty("yaw", definition.yaw());
        placement.addProperty("pitch", definition.pitch());
        placement.addProperty("enabled", true);
        placement.add("waystone", detail);

        JsonObject request = new JsonObject();
        request.addProperty("expectedRevision", revision);
        request.add("placement", placement);
        return request.toString();
    }

    /**
     * API 応答を公開前に全件検証します。ロードされていない Bukkit world の行も保持します。
     * @param json API 応答本文
     * @return 検証済みスナップショット
     * @throws IllegalStateException 応答契約に反する行がある場合
     */
    public static @NotNull Snapshot parseSnapshot(@NotNull String json) {
        return parseSnapshot(JsonParser.parseString(json).getAsJsonObject());
    }

    /** JSON オブジェクトを検証して公開可能なスナップショットに変換します。 */
    private static @NotNull Snapshot parseSnapshot(JsonObject root) {
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
