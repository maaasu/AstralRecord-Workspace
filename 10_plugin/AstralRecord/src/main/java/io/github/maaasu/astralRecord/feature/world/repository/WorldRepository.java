package io.github.maaasu.astralRecord.feature.world.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.world.model.OverworldTeleportGuiSetting;
import io.github.maaasu.astralRecord.feature.world.model.WorldAdventureGuide;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AstralRecord API から WorldMasterData を取得するリポジトリです。
 */
public class WorldRepository {

    /**
     * API 側の MasterDataDB Seeder を実行します。
     */
    public void seedMasterData() {
        String path = "/api/master-data/seed";

        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    Logger.log(LogId.E_5751, "seed status=" + response.statusCode());
                    throw new IOException("Unexpected status " + response.statusCode() + " for POST " + path);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * WorldMasterData の全件を取得します。
     *
     * @return WorldMasterData 一覧
     */
    @NotNull
    public List<WorldMasterData> findAll() {
        String path = "/api/world";

        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    Logger.log(LogId.E_5751, "status=" + response.statusCode());
                    throw new IOException("Unexpected status " + response.statusCode() + " for GET " + path);
                }

                JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
                return resolveListPayload(array);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * World 一覧レスポンスを完全なマスタデータへ展開します。
     * 一覧 API は要約 DTO を返すため、詳細項目を含まない行は ID ごとの詳細 API で補完します。
     *
     * @param array World 一覧レスポンス
     * @return 完全な WorldMasterData 一覧
     */
    @NotNull
    List<WorldMasterData> resolveListPayload(@NotNull JsonArray array) {
        List<WorldMasterData> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject obj = element.getAsJsonObject();
            WorldMasterData world;
            if (obj.has("baseWorldPath") && !obj.get("baseWorldPath").isJsonNull()) {
                world = parse(obj);
            } else {
                String worldId = optionalString(obj, "id");
                if (worldId == null) {
                    continue;
                }
                world = findById(worldId);
                if (world == null) {
                    throw new IllegalStateException("World detail not found: " + worldId);
                }
            }

            if (world != null) {
                result.add(world);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 指定 ID の WorldMasterData を取得します。
     *
     * @param worldId WorldMasterData ID
     * @return WorldMasterData。存在しない場合は {@code null}
     */
    @Nullable
    public WorldMasterData findById(@NotNull String worldId) {
        String encoded = URLEncoder.encode(worldId, StandardCharsets.UTF_8).replace("+", "%20");
        String path = "/api/world/" + encoded;

        try {
            try (var client = ApiRequestUtil.buildClient()) {
                var request = ApiRequestUtil.buildRequestBuilder(path).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                return switch (response.statusCode()) {
                    case 200 -> parse(JsonParser.parseString(response.body()).getAsJsonObject());
                    case 404 -> {
                        Logger.log(LogId.W_5750, worldId);
                        yield null;
                    }
                    default -> {
                        Logger.log(LogId.E_5750, worldId + " status=" + response.statusCode());
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
    private WorldMasterData parse(@NotNull JsonObject obj) {
        String id = optionalString(obj, "id");
        if (id == null) {
            return null;
        }

        Logger.log(LogId.D_5750, id);
        return new WorldMasterData(
                obj.has("schemaVersion") ? obj.get("schemaVersion").getAsInt() : 1,
                id,
                optionalString(obj, "displayName", id),
                WorldType.from(optionalString(obj, "worldType")),
                optionalString(obj, "baseWorldPath", id),
                optionalString(obj, "instanceRootPath", "world_instances"),
                obj.has("autoLoad") && obj.get("autoLoad").getAsBoolean(),
                obj.has("instanceEnabled") && obj.get("instanceEnabled").getAsBoolean(),
                obj.has("maxPlayers") ? obj.get("maxPlayers").getAsInt() : 0,
                obj.has("allowBlockBreak") && obj.get("allowBlockBreak").getAsBoolean(),
                obj.has("allowBlockPlace") && obj.get("allowBlockPlace").getAsBoolean(),
                obj.has("allowMobSpawn") && obj.get("allowMobSpawn").getAsBoolean(),
                !obj.has("showSpawnParticle") || obj.get("showSpawnParticle").getAsBoolean(),
                parseSpawnLocation(obj),
                optionalString(obj, "description", ""),
                optionalString(obj, "guiIconMaterial"),
                parseAdventureGuide(obj),
                parseOverworldTeleportGui(obj)
        );
    }

    @Nullable
    private static OverworldTeleportGuiSetting parseOverworldTeleportGui(@NotNull JsonObject obj) {
        JsonObject setting = optionalObject(obj, "overworldTeleportGui");
        if (setting == null) {
            return null;
        }
        return new OverworldTeleportGuiSetting(optionalInteger(setting, "slot"));
    }

    @NotNull
    private static WorldSpawnLocation parseSpawnLocation(@NotNull JsonObject obj) {
        JsonObject spawn = optionalObject(obj, "spawnLocation");
        if (spawn == null) {
            return WorldSpawnLocation.defaultLocation();
        }

        return new WorldSpawnLocation(
                optionalDouble(spawn, "x", 0.5D),
                optionalDouble(spawn, "y", 64.0D),
                optionalDouble(spawn, "z", 0.5D),
                (float) optionalDouble(spawn, "yaw", 0.0D),
                (float) optionalDouble(spawn, "pitch", 0.0D)
        );
    }

    @Nullable
    private static WorldAdventureGuide parseAdventureGuide(@NotNull JsonObject obj) {
        JsonObject guide = optionalObject(obj, "adventureGuide");
        if (guide == null) {
            return null;
        }

        Integer levelMin = optionalInteger(guide, "recommendedLevelMin");
        Integer levelMax = optionalInteger(guide, "recommendedLevelMax");
        Integer partySizeMin = optionalInteger(guide, "recommendedPartySizeMin");
        Integer partySizeMax = optionalInteger(guide, "recommendedPartySizeMax");
        List<String> notes = optionalStringList(guide, "notes");
        if (levelMin == null && levelMax == null
                && partySizeMin == null && partySizeMax == null
                && notes.isEmpty()) {
            return null;
        }

        return new WorldAdventureGuide(levelMin, levelMax, partySizeMin, partySizeMax, notes);
    }

    @Nullable
    private static String optionalString(@NotNull JsonObject obj, @NotNull String key) {
        return optionalString(obj, key, null);
    }

    @Nullable
    private static String optionalString(@NotNull JsonObject obj, @NotNull String key, @Nullable String fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement element = obj.get(key);
        return element.isJsonPrimitive() ? element.getAsString() : fallback;
    }

    @Nullable
    private static JsonObject optionalObject(@NotNull JsonObject obj, @NotNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = obj.get(key);
        return element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static double optionalDouble(@NotNull JsonObject obj, @NotNull String key, double fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        JsonElement element = obj.get(key);
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsDouble()
                : fallback;
    }

    @Nullable
    private static Integer optionalInteger(@NotNull JsonObject obj, @NotNull String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement element = obj.get(key);
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()
                ? element.getAsInt()
                : null;
    }

    @NotNull
    private static List<String> optionalStringList(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        for (JsonElement child : element.getAsJsonArray()) {
            if (child != null && child.isJsonPrimitive()) {
                values.add(child.getAsString());
            }
        }
        return List.copyOf(values);
    }
}
