package io.github.maaasu.astralRecord.feature.skill.service;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** パック生成と同じスキル ID・スプライト番号の台帳を読み取ります。 */
final class ActionRingIconCatalog {
    private static final String RESOURCE = "/action-ring-icons.json";
    private static final ActionRingIconCatalog INSTANCE = load();

    private final Map<String, Integer> icons;
    private final String revision;

    private ActionRingIconCatalog(Map<String, Integer> icons, String revision) {
        this.icons = icons;
        this.revision = revision;
    }

    static int sprite(String skillId) {
        return INSTANCE.icons.getOrDefault(skillId, 0);
    }

    static String revision() {
        return INSTANCE.revision;
    }

    private static ActionRingIconCatalog load() {
        try (var stream = ActionRingIconCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("Missing action ring sprite catalog");
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            Map<String, Integer> icons = new HashMap<>();
            JsonParser.parseString(json).getAsJsonObject().entrySet().forEach(entry ->
                icons.put(entry.getKey(), entry.getValue().getAsInt())
            );
            String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8)));
            return new ActionRingIconCatalog(Map.copyOf(icons), revision);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot read action ring sprite catalog", exception);
        }
    }
}
