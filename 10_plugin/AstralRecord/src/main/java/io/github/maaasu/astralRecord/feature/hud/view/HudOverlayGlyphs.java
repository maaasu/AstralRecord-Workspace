package io.github.maaasu.astralRecord.feature.hud.view;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** ActionBarの幅を変えずに、画面固定HUDへ渡すマーカー付き字形を組み立てます。 */
final class HudOverlayGlyphs {
    static final Typeface QUEST_TEXT = new Typeface(Key.key("astralrecord", "quest_hud"),
            codePoint -> ClassNameGlyphs.advance(codePoint, true));
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

    /** 単色の矩形へ変換する字形を追加します。 */
    static void appendPixel(TextComponent.Builder result, int red, int green, int blue) {
        appendGlyph(result, SOLID_PIXEL, 7, red, green, blue);
    }

    /** 共通フォントから座標マーカー付き字形を追加します。 */
    static void appendGlyph(TextComponent.Builder result, int codePoint, int advance, int red, int green, int blue) {
        appendGlyph(result, QUEST_TEXT.font(), codePoint, advance, red, green, blue);
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

    record Typeface(Key font, IntUnaryOperator advances, int maxAdvance) {
        Typeface(Key font, IntUnaryOperator advances) {
            this(font, advances, 10);
        }
        /** 指定座標へ文字を並べ、影を使う場合は行番号に専用オフセットを加えます。 */
        void appendLine(TextComponent.Builder result, List<Integer> text, int x, int row, int marker, int shadowRowOffset) {
            for (int codePoint : text) {
                int advance = advances.applyAsInt(codePoint);
                if (codePoint != ' ' && codePoint != 0x3000) {
                    ShadowColor shadow = shadowRowOffset == 0 ? ShadowColor.none()
                            : ShadowColor.shadowColor(0xFF000000 | (marker << 16) | (x << 8) | (row + shadowRowOffset));
                    appendGlyph(result, font, codePoint, advance, marker, x, row, shadow);
                }
                x += advance;
            }
        }

        /** 表示幅に収まる字形へ変換し、切り詰めた場合は末尾に省略記号を付けます。 */
        List<Integer> fit(String value, int maxWidth) {
            List<Integer> text = new ArrayList<>();
            (value == null ? "" : value).codePoints().filter(c -> !Character.isISOControl(c))
                    .map(this::supportedGlyph).forEach(text::add);
            if (width(text) <= maxWidth) {
                return text;
            }
            int ellipsis = '…';
            int used = advances.applyAsInt(ellipsis);
            int end = 0;
            while (end < text.size()) {
                int advance = advances.applyAsInt(text.get(end));
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
        int prefixEnd(String text, int maxWidth) {
            int used = 0;
            int end = 0;
            while (end < text.length()) {
                int codePoint = text.codePointAt(end);
                if (!Character.isISOControl(codePoint)) {
                    used += advances.applyAsInt(supportedGlyph(codePoint));
                    if (used > maxWidth) {
                        break;
                    }
                }
                end += Character.charCount(codePoint);
            }
            return end;
        }

        /** @return パックに未収録の文字を代替した字形番号 */
        private int supportedGlyph(int codePoint) {
            int advance = advances.applyAsInt(codePoint);
            return advance > 0 && advance <= maxAdvance ? codePoint : '?';
        }

        /** @return 小型フォントでの字送りの合計 */
        int width(List<Integer> text) {
            return text.stream().mapToInt(advances::applyAsInt).sum();
        }
    }
}
