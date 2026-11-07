package io.github.maaasu.astralRecord.feature.hud.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** バフマスターのテクスチャを、Java版HUDに収録した字形へ割り当てます。 */
final class BuffHudIcons {
    static final Key FONT = Key.key("astralrecord", "buff_hud");
    static final Line EMPTY = new Line(Component.empty(), 0);
    private static final int FIRST_GLYPH = 0xEA00;
    private static final int MAX_GLYPHS = 256;
    private static final int MAX_VISIBLE = 6;
    private static final int ICON_ADVANCE = 11;
    private static final int OVERFLOW_GAP = 4;
    private static final int OVERFLOW_CHAR_ADVANCE = 6;
    private static final String GENERAL_BUFF = "astralrecord:buff_hud/general_buff.png";
    private static final String GENERAL_DEBUFF = "astralrecord:buff_hud/general_debuff.png";
    private static final Comparator<ActiveBuff> DISPLAY_ORDER = Comparator
            .<ActiveBuff, Boolean>comparing(buff -> buff.getType().isDebuff()).reversed()
            .thenComparing(ActiveBuff::getStartedAt);
    private final Map<String, Integer> glyphs = loadGlyphs();

    Line render(List<ActiveBuff> buffs) {
        if (buffs.isEmpty() || glyphs.isEmpty()) {
            return EMPTY;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ActiveBuff> active = buffs.stream()
                .filter(buff -> !buff.isExpired(now))
                .sorted(DISPLAY_ORDER)
                .toList();
        int count = Math.min(active.size(), MAX_VISIBLE);
        if (count == 0) {
            return EMPTY;
        }
        StringBuilder icons = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i != 0) {
                icons.append('\ue950');
            }
            icons.appendCodePoint(glyph(active.get(i).getType()));
        }
        Component text = Component.text(icons.toString()).font(FONT);
        int width = count * ICON_ADVANCE + count - 1;
        if (active.size() > count) {
            String overflow = "+" + (active.size() - count);
            text = text.append(Component.text("\ue952").font(FONT))
                    .append(Component.text(overflow, NamedTextColor.GRAY).font(JavaProfileHud.CONDITION_FONT));
            width += OVERFLOW_GAP + overflow.length() * OVERFLOW_CHAR_ADVANCE;
        }
        return new Line(text, width);
    }

    private int glyph(BuffType buff) {
        String texture = buff.getHudIconTexture();
        String fallback = buff.isDebuff() ? GENERAL_DEBUFF : GENERAL_BUFF;
        return texture == null ? glyphs.get(fallback) : glyphs.getOrDefault(texture, glyphs.get(fallback));
    }

    private static Map<String, Integer> loadGlyphs() {
        try (InputStream input = BuffHudIcons.class.getResourceAsStream("/buff-hud-icons.json")) {
            if (input == null) {
                return Map.of();
            }
            JsonArray textures = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonArray();
            if (textures.size() > MAX_GLYPHS) {
                return Map.of();
            }
            Map<String, Integer> glyphs = new HashMap<>();
            int codePoint = FIRST_GLYPH;
            for (JsonElement texture : textures) {
                if (!texture.isJsonPrimitive() || !texture.getAsJsonPrimitive().isString()
                        || glyphs.putIfAbsent(texture.getAsString(), codePoint++) != null) {
                    return Map.of();
                }
            }
            return glyphs.containsKey(GENERAL_BUFF) && glyphs.containsKey(GENERAL_DEBUFF)
                    ? Map.copyOf(glyphs) : Map.of();
        } catch (IOException | IllegalStateException | JsonParseException ignored) {
            return Map.of();
        }
    }

    record Line(Component text, int width) {
    }
}
