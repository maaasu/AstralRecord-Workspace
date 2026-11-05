package io.github.maaasu.astralrecordgeyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** API起動時スナップショット。無効入力を登録イベントへ渡さない。 */
record HeadCatalog(List<String> textures, List<UUID> playerUuids) {
    private static final Pattern SKIN_HASH = Pattern.compile("[0-9a-fA-F]{1,64}");

    /** レスポンス全体を検証してから重複のない登録一覧を返す。 */
    static HeadCatalog parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        LinkedHashSet<String> textures = new LinkedHashSet<>();
        for (JsonElement entry : requiredArray(root, "textures")) {
            String texture = entry.getAsString().trim();
            validateTexture(texture);
            textures.add(texture);
        }
        LinkedHashSet<UUID> players = new LinkedHashSet<>();
        for (JsonElement entry : requiredArray(root, "playerUuids")) {
            String value = entry.getAsString();
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid player UUID");
            players.add(uuid);
        }
        return new HeadCatalog(List.copyOf(textures), List.copyOf(players));
    }

    /** Minecraftの公開スキンテクスチャのみ許可する。 */
    static void validateTexture(String texture) {
        skinHash(decodeTexture(texture));
    }

    static JsonObject decodeTexture(String texture) {
        if (texture.length() > 16384) throw new IllegalArgumentException("Texture profile too large");
        String decoded = new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8);
        return JsonParser.parseString(decoded).getAsJsonObject();
    }

    static String skinHash(JsonObject profile) {
        String url = requireString(profile.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url"));
        URI uri = URI.create(url);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
            || !"textures.minecraft.net".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null
            || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
            || uri.getRawPath() == null || !uri.getRawPath().startsWith("/texture/")) {
            throw new IllegalArgumentException("Invalid Minecraft texture URL");
        }
        return requireSkinHash(uri.getRawPath().substring("/texture/".length()));
    }

    static String requireSkinHash(String hash) {
        if (!SKIN_HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException("Invalid Minecraft skin hash");
        }
        return hash.toLowerCase(Locale.ROOT);
    }

    static String requireString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected JSON string");
        }
        return element.getAsString();
    }

    private static JsonArray requiredArray(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonArray()) throw new IllegalArgumentException("Missing catalog array");
        return root.getAsJsonArray(name);
    }
}
