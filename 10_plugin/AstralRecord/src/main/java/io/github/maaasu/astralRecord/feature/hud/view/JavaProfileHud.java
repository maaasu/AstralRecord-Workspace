package io.github.maaasu.astralRecord.feature.hud.view;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

/** Java 版のアクションバーに成長情報の二行を重ねます。 */
public final class JavaProfileHud {
    private static final Key PLAYER_FONT = Key.key("astralrecord", "profile_hud_player");
    private static final Key CLASS_FONT = Key.key("astralrecord", "profile_hud_class");
    private static final String TEXT_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!?+-/()%";
    private static final String NARROW_GLYPHS = ".,:|";
    private static final int LEFT_OFFSET = 8;
    private static final int NARROW_VIEW_WIDTH = 320;
    private static final int VIEW_MARGIN = 6;
    private static final int FIRST_ICON = 0xE940;
    private static final int LAST_ICON = 0xE944;
    private static final int POSITIVE_SPACE = 0xE950;
    private static final int NEGATIVE_SPACE = 0xE955;
    private static final int LARGEST_SPACE_BIT = 4;

    private JavaProfileHud() {
    }

    /**
     * 戦闘行の左端を基準に、専用フォントで成長情報を上へ配置します。
     * @param combat 戦闘情報や一時表示の行
     * @param playerLine プレイヤーの成長情報
     * @param classLine クラスの成長情報
     * @return 成長情報の描画幅を相殺したアクションバー
     */
    public static Component compose(Component combat, Component playerLine, Component classLine) {
        int width = combatWidthUpperBound(combat, false);
        int shift = Math.max(-LEFT_OFFSET, Math.floorDiv(width - NARROW_VIEW_WIDTH + 1, 2) + VIEW_MARGIN);
        return Component.empty()
                .append(overlay(playerLine, PLAYER_FONT, shift))
                .append(overlay(classLine, CLASS_FONT, shift))
                .append(combat);
    }

    /**
     * 長い戦闘行で個人情報の先頭が画面外に出ないよう、使用する字形の幅を安全側に見積もります。
     * @param component 戦闘行の要素
     * @param parentBold 親要素から継承した太字
     * @return 現行の戦闘表示に使うフォントでの字送りの見積もり
     */
    private static int combatWidthUpperBound(Component component, boolean parentBold) {
        boolean bold = switch (component.decoration(TextDecoration.BOLD)) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> parentBold;
        };
        int width = 0;
        if (component instanceof TextComponent text) {
            for (int offset = 0; offset < text.content().length();) {
                int glyph = text.content().codePointAt(offset);
                offset += Character.charCount(glyph);
                if (glyph >= 0xE900 && glyph <= 0xE91A) {
                    width += 19;
                } else if (glyph == ' ') {
                    width += 4;
                } else {
                    width += glyph < 128 ? 6 : 10;
                }
                if (bold) {
                    width++;
                }
            }
        }
        for (Component child : component.children()) {
            width += combatWidthUpperBound(child, bold);
        }
        return width;
    }

    /**
     * 個人情報を指定の位置へ重ね、合成前後の字送りを同じに保ちます。
     * @param source 表示内容
     * @param font 高さを指定するフォント
     * @param shift 戦闘行の左端からの横方向の差
     * @return 戦闘行の位置に影響しない描画要素
     */
    private static Component overlay(Component source, Key font, int shift) {
        OverlayLine line = withProfileFont(source, font);
        if (line.width() == 0) {
            return Component.empty();
        }
        // 各行の送り幅を0に戻し、戦闘行のセンタリングを維持する。
        return Component.empty()
                .append(spacing(shift, font))
                .append(line.text())
                .append(spacing(-shift - line.width(), font));
    }

    private static OverlayLine withProfileFont(Component source, Key font) {
        TextComponent.Builder line = Component.text().font(font);
        if (source.color() != null) {
            line.color(source.color());
        }
        int width = 0;
        if (source instanceof TextComponent text) {
            StringBuilder glyphs = new StringBuilder();
            for (int offset = 0; offset < text.content().length();) {
                int original = text.content().codePointAt(offset);
                int glyph = Character.toUpperCase(original);
                offset += Character.charCount(original);
                if (glyph >= FIRST_ICON && glyph <= LAST_ICON) {
                    width += 8;
                } else if (glyph == ' ') {
                    width += 3;
                } else if (NARROW_GLYPHS.indexOf(glyph) >= 0) {
                    width += 2;
                } else {
                    if (TEXT_GLYPHS.indexOf(glyph) < 0) {
                        glyph = '?';
                    }
                    width += 4;
                }
                glyphs.appendCodePoint(glyph);
            }
            line.content(glyphs.toString());
        }
        for (Component child : source.children()) {
            OverlayLine formatted = withProfileFont(child, font);
            line.append(formatted.text());
            width += formatted.width();
        }
        return new OverlayLine(line.build(), width);
    }

    private static Component spacing(int advance, Key font) {
        int base = advance < 0 ? NEGATIVE_SPACE : POSITIVE_SPACE;
        int remaining = Math.abs(advance);
        StringBuilder glyphs = new StringBuilder();
        for (int bit = LARGEST_SPACE_BIT; bit >= 0; bit--) {
            int width = 1 << bit;
            glyphs.append(String.valueOf((char) (base + bit)).repeat(remaining / width));
            remaining %= width;
        }
        return Component.text(glyphs.toString()).font(font);
    }

    private record OverlayLine(Component text, int width) {
    }
}
