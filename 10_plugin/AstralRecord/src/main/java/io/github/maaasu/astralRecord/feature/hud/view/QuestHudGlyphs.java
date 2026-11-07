package io.github.maaasu.astralRecord.feature.hud.view;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/** 専用パックで画面左上へ移す、送り幅ゼロのクエスト字形を組み立てます。 */
public final class QuestHudGlyphs {
    private static final Key FONT = Key.key("astralrecord", "quest_hud");
    private static final Key SPACE_FONT = Key.key("astralrecord", "quest_hud_space");
    private static final int LEFT = 8;
    private static final int MAX_WIDTH = 280;
    private static final int PRESERVED_TAIL_GLYPHS = 18;

    private QuestHudGlyphs() {
    }

    /**
     * 既存ActionBarの合成後に追加できる、全体の字送りがゼロの2行を返します。
     * 対応パックの適用確認後だけ呼び出してください。各字形のRGBに絶対x・行・幅を持たせます。
     * 未収録文字を疑問符へ置換し、下段は残り数を含む末尾を優先して幅内に収めます。
     *
     * @param title 上段の公開用クエスト名
     * @param objective 下段の公開用目標と残り数
     * @return 元のActionBarの中央寄せに影響しない追加要素
     */
    public static Component render(String title, String objective) {
        TextComponent.Builder result = Component.text().shadowColor(ShadowColor.none());
        for (TextDecoration decoration : TextDecoration.values()) {
            result.decoration(decoration, false);
        }
        appendLine(result, fit(normalize(title), false), 0);
        appendLine(result, fit(normalize(objective), true), 1);
        return result.build();
    }

    private static void appendLine(TextComponent.Builder result, List<Integer> text, int row) {
        int x = LEFT;
        for (int codePoint : text) {
            int advance = ClassNameGlyphs.advance(codePoint, true);
            if (codePoint != ' ' && codePoint != 0x3000) {
                int red = 252 + (x >> 8);
                int blue = row * 16 + advance - 1;
                result.append(Component.text(new String(Character.toChars(codePoint)))
                    .font(FONT).color(TextColor.color(red, x & 255, blue))
                    .shadowColor(ShadowColor.none()));
                // Every bitmap advance is immediately cancelled; ordinary combat stays centered.
                result.append(Component.text(String.valueOf((char) (0xE000 + advance)))
                    .font(SPACE_FONT).color(TextColor.color(0xFFFFFF)).shadowColor(ShadowColor.none()));
            }
            x += advance;
        }
    }

    private static List<Integer> normalize(String value) {
        List<Integer> result = new ArrayList<>();
        String text = value == null ? "" : value.replaceAll("(?i)[&§][0-9A-FK-ORX]", "");
        text.codePoints().filter(c -> !Character.isISOControl(c)).forEach(c -> {
            int advance = ClassNameGlyphs.advance(c, true);
            result.add(advance > 0 && advance <= 10 ? c : (int) '?');
        });
        return result;
    }

    private static List<Integer> fit(List<Integer> input, boolean preserveTail) {
        if (width(input) <= MAX_WIDTH) {
            return input;
        }
        List<Integer> result = new ArrayList<>();
        List<Integer> suffix = preserveTail
            ? input.subList(Math.max(0, input.size() - PRESERVED_TAIL_GLYPHS), input.size()) : List.of();
        int suffixWidth = width(suffix);
        int dotsWidth = ClassNameGlyphs.advance('.', true) * 2;
        int current = 0;
        for (int index = 0; index < input.size() - suffix.size(); index++) {
            int codePoint = input.get(index);
            int next = ClassNameGlyphs.advance(codePoint, true);
            if (current + next + dotsWidth + suffixWidth > MAX_WIDTH) {
                break;
            }
            result.add(codePoint);
            current += next;
        }
        result.add((int) '.');
        result.add((int) '.');
        result.addAll(suffix);
        return result;
    }

    private static int width(List<Integer> text) {
        return text.stream().mapToInt(c -> ClassNameGlyphs.advance(c, true)).sum();
    }
}
