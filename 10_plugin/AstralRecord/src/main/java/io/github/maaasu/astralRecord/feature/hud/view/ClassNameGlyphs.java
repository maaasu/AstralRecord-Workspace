package io.github.maaasu.astralRecord.feature.hud.view;

import net.kyori.adventure.key.Key;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Java版パックに収録した正式クラス名用の字形を照合します。 */
final class ClassNameGlyphs {
    static final Key FONT = Key.key("astralrecord", "profile_hud_class_name");
    // 320pxの表示幅から左右の余白と、アイコン・最大桁のクラスLv・EXPの幅を引く。
    private static final int MAX_NAME_WIDTH = 156;
    private static final Map<Integer, Integer> ADVANCES = loadAdvances();

    private ClassNameGlyphs() {
    }

    static boolean supports(String name) {
        if (name.isBlank()) {
            return false;
        }
        int width = 0;
        for (int offset = 0; offset < name.length();) {
            int codePoint = name.codePointAt(offset);
            int advance = advance(codePoint);
            if (advance == 0 || width + advance > MAX_NAME_WIDTH) {
                return false;
            }
            width += advance;
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    static int advance(int codePoint) {
        return ADVANCES.getOrDefault(codePoint, 0);
    }

    private static Map<Integer, Integer> loadAdvances() {
        try (InputStream input = ClassNameGlyphs.class.getResourceAsStream("/class-name-glyph-advances.txt")) {
            if (input == null) {
                return Map.of();
            }
            Map<Integer, Integer> advances = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split(" ", 2);
                    int codePoint = Integer.parseInt(fields[0], 16);
                    int advance = Integer.parseInt(fields[1]);
                    if (!Character.isValidCodePoint(codePoint) || advance <= 0 || advances.putIfAbsent(codePoint, advance) != null) {
                        return Map.of();
                    }
                }
            }
            return Map.copyOf(advances);
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            return Map.of();
        }
    }
}
