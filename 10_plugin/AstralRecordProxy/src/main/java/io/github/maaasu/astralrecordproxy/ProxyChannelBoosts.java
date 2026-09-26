package io.github.maaasu.astralrecordproxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Proxy TABとロビー用にAPIの全チャンネル状態を整形します。 */
final class ProxyChannelBoosts {
    private ProxyChannelBoosts() { }

    /** 通常チャンネルも含めて表示行を作ります。 */
    static List<String> lines(JsonObject snapshot, Instant now) {
        if (snapshot == null || !snapshot.has("channels")) return List.of("ブースト情報を取得中");
        List<String> lines = new ArrayList<>();
        for (JsonElement element : snapshot.getAsJsonArray("channels")) {
            JsonObject channel = element.getAsJsonObject();
            lines.add(channel.get("channelId").getAsString() + " EXP " + boost(channel, "exp", now)
                + " / DROP " + boost(channel, "drop", now));
        }
        lines.sort(Comparator.naturalOrder());
        return List.copyOf(lines);
    }

    private static String boost(JsonObject channel, String key, Instant now) {
        if (!channel.has(key) || channel.get(key).isJsonNull()) return "通常";
        JsonObject value = channel.getAsJsonObject(key);
        Instant expiry = Instant.parse(value.get("expiresAt").getAsString());
        if (!expiry.isAfter(now)) return "通常";
        long minutes = Math.max(1, (Duration.between(now, expiry).getSeconds() + 59) / 60);
        return value.get("multiplier").getAsDouble() + "倍 残り" + minutes + "分";
    }
}
