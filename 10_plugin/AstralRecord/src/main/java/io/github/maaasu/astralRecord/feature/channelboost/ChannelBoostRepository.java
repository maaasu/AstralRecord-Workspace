package io.github.maaasu.astralRecord.feature.channelboost;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** チャンネルブーストAPIだけを扱う非同期呼出用リポジトリです。 */
public final class ChannelBoostRepository {
    /** 全チャンネルの現在値を取得します。呼出元が非同期スレッドを選びます。 */
    public JsonObject snapshot() throws IOException, InterruptedException {
        return request("/api/channel-boosts", null);
    }

    /** イベントカーソルより新しい発動だけを取得します。 */
    public JsonObject events(long after) throws IOException, InterruptedException {
        return request("/api/channel-boosts/events?after=" + after, null);
    }

    /** API側でチケット消費と発動を原子的に確定します。 */
    public JsonObject activate(String channelId, JsonObject body) throws IOException, InterruptedException {
        return request("/api/channel-boosts/" + URLEncoder.encode(channelId, StandardCharsets.UTF_8)
            + "/activate", body);
    }

    private JsonObject request(String path, JsonObject body) throws IOException, InterruptedException {
        var builder = ApiRequestUtil.buildRequestBuilder(path);
        HttpRequest request = body == null ? builder.GET().build() : builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 409)
            throw new IOException("Channel boost HTTP " + response.statusCode());
        try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid channel boost response", e); }
    }

    /** APIのUTC時刻と二つの効果を解析します。 */
    public static ChannelBoostState parse(JsonObject response) {
        Map<String, ChannelBoostState.Channel> channels = new HashMap<>();
        var values = response.getAsJsonArray("channels");
        if (values != null) for (JsonElement value : values) {
            JsonObject channel = value.getAsJsonObject();
            String channelId = channel.get("channelId").getAsString().toLowerCase(Locale.ROOT);
            JsonElement name = channel.get("displayName");
            JsonElement enabled = channel.get("networkBoostEnabled");
            channels.put(channelId, new ChannelBoostState.Channel(channelId,
                name == null || name.isJsonNull() || name.getAsString().isBlank() ? "チャンネル" : name.getAsString().trim(),
                enabled != null && !enabled.isJsonNull() && enabled.getAsBoolean(),
                boost(channel, "exp"), boost(channel, "drop")));
        }
        return new ChannelBoostState(Map.copyOf(channels), response.get("eventCursor").getAsLong());
    }

    private static ChannelBoostState.Boost boost(JsonObject parent, String key) {
        if (!parent.has(key) || parent.get(key).isJsonNull()) return null;
        JsonObject value = parent.getAsJsonObject(key);
        JsonElement expiry = value.get("expiresAt");
        if (expiry == null || expiry.isJsonNull()) return null;
        String text = expiry.getAsString();
        Instant instant;
        try { instant = Instant.parse(text); }
        catch (java.time.format.DateTimeParseException ignored) {
            instant = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        }
        JsonElement activator = value.get("activatorAccountName");
        return new ChannelBoostState.Boost(value.get("multiplier").getAsDouble(), instant,
            activator == null || activator.isJsonNull() ? "" : activator.getAsString());
    }
}
