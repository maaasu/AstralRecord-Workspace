package io.github.maaasu.astralrecordproxy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.math.BigDecimal;
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

    /** ネットワーク表示対象のうち、有効なブーストがあるチャンネルだけをTAB用に整形します。 */
    static List<Component> tabRows(JsonObject snapshot, Instant now) {
        if (snapshot == null || !snapshot.has("channels") || !snapshot.get("channels").isJsonArray()) return List.of();
        List<JsonObject> enabled = new ArrayList<>();
        for (JsonElement element : snapshot.getAsJsonArray("channels")) {
            if (!element.isJsonObject()) continue;
            JsonObject channel = element.getAsJsonObject();
            JsonElement flag = channel.get("networkBoostEnabled");
            if (flag != null && !flag.isJsonNull() && flag.getAsBoolean()) enabled.add(channel);
        }
        enabled.sort(Comparator.comparing(channel -> channel.get("channelId").getAsString()));
        List<Component> rows = new ArrayList<>();
        for (JsonObject channel : enabled) {
            JsonObject exp = activeBoost(channel, "exp", now);
            JsonObject drop = activeBoost(channel, "drop", now);
            if (exp == null && drop == null) continue;
            JsonElement name = channel.get("displayName");
            String displayName = name == null || name.isJsonNull() || name.getAsString().isBlank()
                ? "チャンネル" : name.getAsString().trim();
            Component row = Component.empty()
                .append(Component.text(displayName, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text("  ›  ", NamedTextColor.DARK_GRAY));
            if (exp != null) row = row.append(tabBoost(exp, "EXP", NamedTextColor.GREEN, now));
            if (drop != null) {
                if (exp != null) row = row.append(Component.text("   ·   ", NamedTextColor.DARK_GRAY));
                row = row.append(tabBoost(drop, "DROP", NamedTextColor.AQUA, now));
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    /** 指定時刻に有効なブーストだけを返します。 */
    private static JsonObject activeBoost(JsonObject channel, String key, Instant now) {
        if (!channel.has(key) || channel.get(key).isJsonNull()) return null;
        JsonObject boost = channel.getAsJsonObject(key);
        Instant expiry = Instant.parse(boost.get("expiresAt").getAsString());
        double multiplier = boost.get("multiplier").getAsDouble();
        return expiry.isAfter(now) && multiplier > 1.0D ? boost : null;
    }

    /** 有効なブーストの倍率と期限までの残分数をTAB用に整形します。 */
    private static Component tabBoost(JsonObject boost, String label, NamedTextColor color, Instant now) {
        Instant expiry = Instant.parse(boost.get("expiresAt").getAsString());
        double multiplier = boost.get("multiplier").getAsDouble();
        long minutes = Math.max(1, (Duration.between(now, expiry).getSeconds() + 59) / 60);
        return Component.text(label + " ×" + BigDecimal.valueOf(multiplier).stripTrailingZeros().toPlainString(), color)
            .append(Component.text("  残り" + minutes + "分", NamedTextColor.GRAY));
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
