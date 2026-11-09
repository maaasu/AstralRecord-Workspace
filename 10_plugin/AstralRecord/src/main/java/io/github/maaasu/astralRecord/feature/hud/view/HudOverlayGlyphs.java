package io.github.maaasu.astralRecord.feature.hud.view;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/** ActionBarの幅を変えずに、画面固定HUDへ渡すマーカー付き字形を組み立てます。 */
final class HudOverlayGlyphs {
    private static final Key FONT = Key.key("astralrecord", "quest_hud");
    private static final Key SPACE_FONT = Key.key("astralrecord", "quest_hud_space");
    static final int SOLID_PIXEL = 0xE010;

    private HudOverlayGlyphs() {
    }

    /** @return マーカーの色と字形を変える装飾を解除した合成先 */
    static TextComponent.Builder builder() {
        TextComponent.Builder result = Component.text().shadowColor(ShadowColor.none());
        for (TextDecoration decoration : TextDecoration.values()) {
            result.decoration(decoration, false);
        }
        return result;
    }

    /** 小型日本語フォントの字形を、指定の行と開始位置から並べます。 */
    static void appendLine(TextComponent.Builder result, List<Integer> text, int x, int row, int marker) {
        appendLine(result, FONT, text, x, row, marker, 0);
    }

    /** 影の座標マーカーがある場合は、同じ字形の影へ本文と同じ座標を渡します。 */
    static void appendLine(TextComponent.Builder result, Key font, List<Integer> text, int x, int row, int marker, int shadowMarker) {
        for (int codePoint : text) {
            int advance = ClassNameGlyphs.advance(codePoint, true);
            if (codePoint != ' ' && codePoint != 0x3000) {
                ShadowColor shadow = shadowMarker == 0 ? ShadowColor.none()
                        : ShadowColor.shadowColor(0xFF000000 | (shadowMarker << 16) | (x << 8) | row);
                appendGlyph(result, font, codePoint, advance, marker, x, row, shadow);
            }
            x += advance;
        }
    }

    /** 単色の矩形へ変換する字形を追加します。 */
    static void appendPixel(TextComponent.Builder result, int red, int green, int blue) {
        appendGlyph(result, SOLID_PIXEL, 7, red, green, blue);
    }

    /** 共通フォントから座標マーカー付き字形を追加します。 */
    static void appendGlyph(TextComponent.Builder result, int codePoint, int advance, int red, int green, int blue) {
        appendGlyph(result, FONT, codePoint, advance, red, green, blue);
    }

    /** 座標マーカー付き字形の直後に負のスペースを置き、戦闘HUDの中央寄せを保ちます。 */
    static void appendGlyph(TextComponent.Builder result, Key font, int codePoint, int advance,
                            int red, int green, int blue) {
        appendGlyph(result, font, codePoint, advance, red, green, blue, ShadowColor.none());
    }

    /** 本文と影の色を渡し、どちらにも共通の字形と相殺用スペースを使います。 */
    private static void appendGlyph(TextComponent.Builder result, Key font, int codePoint, int advance,
                                    int red, int green, int blue, ShadowColor shadow) {
        result.append(Component.text(new String(Character.toChars(codePoint)))
            .font(font).color(TextColor.color(red, green, blue)).shadowColor(shadow));
        result.append(Component.text(String.valueOf((char) (0xE000 + advance)))
            .font(SPACE_FONT).color(TextColor.color(0xFFFFFF)).shadowColor(ShadowColor.none()));
    }

    /** 表示幅に収まる字形へ変換し、切り詰めた場合は末尾に省略記号を付けます。 */
    static List<Integer> fit(String value, int maxWidth) {
        List<Integer> text = new ArrayList<>();
        (value == null ? "" : value).codePoints().filter(c -> !Character.isISOControl(c))
                .map(HudOverlayGlyphs::supportedGlyph).forEach(text::add);
        if (width(text) <= maxWidth) {
            return text;
        }
        int ellipsis = '…';
        int used = ClassNameGlyphs.advance(ellipsis, true);
        int end = 0;
        while (end < text.size()) {
            int advance = ClassNameGlyphs.advance(text.get(end), true);
            if (used + advance > maxWidth) {
                break;
            }
            used += advance;
            end++;
        }
        text.subList(end, text.size()).clear();
        text.add(ellipsis);
        return text;
    }

    /**
     * @param text 折り返す名前
     * @param maxWidth 先頭行に使える字送り
     * @return 最初の行に収まる部分の末尾の文字列インデックス
     */
    static int prefixEnd(String text, int maxWidth) {
        int used = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            if (!Character.isISOControl(codePoint)) {
                used += ClassNameGlyphs.advance(supportedGlyph(codePoint), true);
                if (used > maxWidth) {
                    break;
                }
            }
            end += Character.charCount(codePoint);
        }
        return end;
    }

    /** @return パックに未収録の文字を代替した字形番号 */
    private static int supportedGlyph(int codePoint) {
        int advance = ClassNameGlyphs.advance(codePoint, true);
        return advance > 0 && advance <= 10 ? codePoint : '?';
    }

    /** @return 小型フォントでの字送りの合計 */
    static int width(List<Integer> text) {
        return text.stream().mapToInt(c -> ClassNameGlyphs.advance(c, true)).sum();
    }
}
