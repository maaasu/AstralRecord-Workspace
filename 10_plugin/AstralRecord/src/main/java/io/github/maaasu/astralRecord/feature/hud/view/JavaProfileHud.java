package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.shared.display.CombatHudGlyphs;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** Java 版のアクションバーに成長情報を重ねます。 */
public final class JavaProfileHud {
    private static final Key PLAYER_FONT = Key.key("astralrecord", "profile_hud_player");
    private static final Key CLASS_FONT = Key.key("astralrecord", "profile_hud_class");
    private static final Key GAMEPLAY_FONT = Key.key("astralrecord", "gameplay_hud");
    private static final Key DETAIL_FONT = Key.key("astralrecord", "gameplay_hud_details");
    private static final Key POINTS_FONT = Key.key("astralrecord", "profile_hud_points");
    private static final String TEXT_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789vmaxs?+-/()%[]";
    private static final TextColor PRIMARY = TextColor.color(0xFFFDF5);
    private static final TextColor SECONDARY = TextColor.color(0xDCE5F0);
    private static final TextColor DIVIDER = TextColor.color(0xBCCADA);
    private static final String NARROW_GLYPHS = ".,:|!";
    private static final int LEFT_OFFSET = 8;
    private static final int PROFILE_LEFT_OF_CENTER = 118;
    private static final int PROFILE_VIEW_HALF_WIDTH = 154;
    private static final int READABLE_ICON_GAP = 5;
    private static final int FIRST_ICON = 0xE940;
    private static final int LAST_ICON = 0xE944;
    private static final int POSITIVE_SPACE = 0xE950;
    private static final int NEGATIVE_SPACE = 0xE955;
    private static final int LARGEST_SPACE_BIT = 4;
    private static final int METER_ADVANCE = 13;
    private static final int WIDE_METER_ADVANCE = 25;
    private static final int FIRST_WIDE_METER = 0xE960;
    static final Component READABLE_RESOURCE_GAP = spacing(10, GAMEPLAY_FONT);

    private JavaProfileHud() {
    }

    /**
     * 戦闘行の左端を基準に、専用フォントで成長情報を上へ配置します。
     * @param combat 戦闘情報や一時表示の行
     * @param profile プレイヤーとクラスの成長情報
     * @param readableTypeface 互換版3の字形を使う場合true
     * @return 成長情報の描画幅を相殺したアクションバー
     */
    public static Component compose(Component combat, ProfileHudText profile, boolean readableTypeface) {
        int halfWidth = combatWidthUpperBound(combat, null, false, readableTypeface) / 2;
        int shift = Math.max(-LEFT_OFFSET, halfWidth - PROFILE_LEFT_OF_CENTER);
        return profileLayers(profile, halfWidth, shift, readableTypeface).append(combat);
    }

    /**
     * 補足情報の行を基準に、戦闘リソースと個人情報を別の高さに描画します。
     * @param resources 専用フォントで整形したHP・MP・ENG
     * @param details 補足情報の行。状態異常などの通常書体も含められる
     * @param profile 個人情報を表示する場合の内容
     * @return リソースと補足情報の横幅を互いに加算しないアクションバー
     */
    static Component arrangeCombat(Component resources, Component details, @Nullable ProfileHudText profile) {
        int detailsWidth = combatWidthUpperBound(details, null, false, true);
        if (detailsWidth == 0) {
            return profile == null ? resources : compose(resources, profile, true);
        }
        int resourcesWidth = combatWidthUpperBound(resources, null, false, true);
        int detailsHalf = detailsWidth / 2;
        int resourceShift = detailsHalf - resourcesWidth / 2;
        int profileShift = Math.max(resourceShift - LEFT_OFFSET, detailsHalf - PROFILE_LEFT_OF_CENTER);
        Component layers = profile == null ? Component.empty() : profileLayers(profile, detailsHalf, profileShift, true);
        return layers.append(overlay(new OverlayLine(resources, resourcesWidth), GAMEPLAY_FONT, resourceShift))
                .append(details);
    }

    private static Component profileLayers(ProfileHudText profile, int halfWidth, int preferredShift,
            boolean readableTypeface) {
        OverlayLine player = withHudFont(profile.playerLine(), PLAYER_FONT, readableTypeface);
        OverlayLine playerClass = withHudFont(profile.classLineWithPoints(), CLASS_FONT, readableTypeface);
        OverlayLine points = new OverlayLine(Component.empty(), 0);
        int shift = preferredShift;
        if (readableTypeface) {
            if (playerClass.width() > PROFILE_VIEW_HALF_WIDTH * 2 && !profile.points().equals(Component.empty())) {
                playerClass = withHudFont(profile.classLine(), CLASS_FONT, true);
                points = withHudFont(profile.points(), POINTS_FONT, true);
            }
            int maxLineWidth = Math.max(points.width(), Math.max(player.width(), playerClass.width()));
            int rightLimit = halfWidth + PROFILE_VIEW_HALF_WIDTH - maxLineWidth;
            int leftLimit = halfWidth - PROFILE_VIEW_HALF_WIDTH;
            shift = Math.max(leftLimit, Math.min(shift, rightLimit));
        }
        return Component.empty()
                .append(overlay(points, POINTS_FONT, shift))
                .append(overlay(player, PLAYER_FONT, shift))
                .append(overlay(playerClass, CLASS_FONT, shift));
    }

    /**
     * 長い戦闘行で個人情報の先頭が画面外に出ないよう、使用する字形の幅を安全側に見積もります。
     * @param component 戦闘行の要素
     * @param parentFont 親要素から継承したフォント
     * @param parentBold 親要素から継承した太字
     * @param readableTypeface 互換版3の字形を使う場合true
     * @return 現行の戦闘表示に使うフォントでの字送りの見積もり
     */
    private static int combatWidthUpperBound(Component component, Key parentFont, boolean parentBold,
            boolean readableTypeface) {
        Key font = component.font() == null ? parentFont : component.font();
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
                if (GAMEPLAY_FONT.equals(font) || DETAIL_FONT.equals(font)) {
                    width += gameplayAdvance(glyph, readableTypeface);
                } else if (glyph >= 0xE900 && glyph <= 0xE91A) {
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
            width += combatWidthUpperBound(child, font, bold, readableTypeface);
        }
        return width;
    }

    /**
     * 個人情報を指定の位置へ重ね、合成前後の字送りを同じに保ちます。
     * @param line 書体を適用した表示と字送り
     * @param font 高さを指定するフォント
     * @param shift 戦闘行の左端からの横方向の差
     * @return 戦闘行の位置に影響しない描画要素
     */
    private static Component overlay(OverlayLine line, Key font, int shift) {
        if (line.width() == 0) {
            return Component.empty();
        }
        // 各行の送り幅を0に戻し、戦闘行のセンタリングを維持する。
        return Component.empty()
                .append(spacing(shift, font))
                .append(line.text())
                .append(spacing(-shift - line.width(), font));
    }

    /**
     * 個人情報の項目間に余白付きの区切り線を描き、専用フォントの字送りを合計します。
     * @param source 表示内容
     * @param font 表示に使う専用フォント
     * @param readableTypeface 互換版3の字形を使う場合true
     * @return 変換後の表示と字送り
     */
    private static OverlayLine withHudFont(Component source, Key font, boolean readableTypeface) {
        TextComponent.Builder line = Component.text().font(font);
        if (source.color() != null) {
            line.color(profileColor(source.color()));
        }
        int width = 0;
        if (source instanceof TextComponent text) {
            String content = text.content();
            if ("   ".equals(content) && source.children().isEmpty()) {
                String separator = "   |   ";
                int advance = textAdvance(separator, readableTypeface);
                return new OverlayLine(Component.text(separator, DIVIDER).font(font), advance);
            }
            StringBuilder glyphs = new StringBuilder();
            for (int offset = 0; offset < content.length();) {
                int original = content.codePointAt(offset);
                offset += Character.charCount(original);
                int glyph = TEXT_GLYPHS.indexOf(original) >= 0 ? original : Character.toUpperCase(original);
                if (glyph == '〚' || glyph == '〛') {
                    glyph = glyph == '〚' ? '[' : ']';
                }
                if (glyph != ' ' && (glyph < FIRST_ICON || glyph > LAST_ICON)
                        && TEXT_GLYPHS.indexOf(glyph) < 0 && NARROW_GLYPHS.indexOf(glyph) < 0) {
                    glyph = '?';
                }
                glyphs.appendCodePoint(glyph);
                width += gameplayAdvance(glyph, readableTypeface);
            }
            line.content(glyphs.toString());
        }
        for (Component child : source.children()) {
            OverlayLine formatted = withHudFont(child, font, readableTypeface);
            line.append(formatted.text());
            width += formatted.width();
        }
        return new OverlayLine(line.build(), width);
    }

    /**
     * リソースの数値の下にゲージを描画し、短い数値の場合もゲージ分の幅を確保します。
     * @param icon リソース種別の字形
     * @param current 現在値
     * @param max 最大値
     * @param fill 0〜10の充填段階
     * @param color ゲージ色
     * @param wide 横長ゲージに対応するパックを適用済みならtrue
     * @param readableTypeface 互換版3の字形を使う場合true
     * @return 通常戦闘行のリソース1項目
     */
    static Component resource(TextComponent icon, double current, double max, int fill, TextColor color, boolean wide,
            boolean readableTypeface) {
        int iconGap = readableTypeface ? READABLE_ICON_GAP : gameplayAdvance(' ', false);
        int valueGap = readableTypeface ? 1 : 0;
        int meterStart = gameplayAdvance(icon.content().codePointAt(0), readableTypeface) + iconGap;
        int meterAdvance = wide ? WIDE_METER_ADVANCE : METER_ADVANCE;
        Component meter = wide ? Component.text(Character.toString(FIRST_WIDE_METER + fill))
                : CombatHudGlyphs.meter(fill);
        String currentText = String.format(Locale.ROOT, "%.0f", current);
        String maxText = String.format(Locale.ROOT, "%.0f", max);
        int valueWidth = textAdvance(currentText, readableTypeface) + textAdvance(maxText, readableTypeface)
                + gameplayAdvance('/', readableTypeface) + valueGap * 2;
        Component valuePadding = readableTypeface ? spacing(valueGap, GAMEPLAY_FONT) : Component.empty();
        return Component.empty().font(GAMEPLAY_FONT)
                .append(spacing(meterStart, GAMEPLAY_FONT))
                .append(meter.color(color).font(GAMEPLAY_FONT))
                .append(spacing(-meterStart - meterAdvance, GAMEPLAY_FONT))
                .append(icon.font(GAMEPLAY_FONT))
                .append(spacing(iconGap, GAMEPLAY_FONT))
                .append(Component.text(currentText, PRIMARY))
                .append(valuePadding)
                .append(Component.text("/", DIVIDER))
                .append(valuePadding)
                .append(Component.text(maxText, SECONDARY))
                .append(spacing(Math.max(0, meterAdvance - valueWidth), GAMEPLAY_FONT));
    }

    static Component styleStat(Component stat, boolean enabled, boolean readableTypeface) {
        Key font = readableTypeface ? DETAIL_FONT : GAMEPLAY_FONT;
        return enabled ? withHudFont(stat, font, readableTypeface).text() : stat;
    }

    private static int textAdvance(String text, boolean readableTypeface) {
        return text.codePoints().map(glyph -> gameplayAdvance(glyph, readableTypeface)).sum();
    }

    private static int gameplayAdvance(int glyph, boolean readableTypeface) {
        if (glyph >= POSITIVE_SPACE && glyph < NEGATIVE_SPACE) {
            return 1 << (glyph - POSITIVE_SPACE);
        }
        if (glyph >= NEGATIVE_SPACE && glyph <= NEGATIVE_SPACE + LARGEST_SPACE_BIT) {
            return -(1 << (glyph - NEGATIVE_SPACE));
        }
        if (glyph == FIRST_ICON) {
            return 7;
        }
        if ((glyph > FIRST_ICON && glyph <= LAST_ICON) || glyph == 0xE902) {
            return 8;
        }
        if (glyph == 0xE900 || glyph == 0xE901) {
            return 10;
        }
        if (glyph >= 0xE910 && glyph <= 0xE91A) {
            return METER_ADVANCE;
        }
        if (glyph >= FIRST_WIDE_METER && glyph <= FIRST_WIDE_METER + 10) {
            return WIDE_METER_ADVANCE;
        }
        if (glyph == ' ' || glyph == '(' || glyph == ')' || glyph == '[' || glyph == ']') {
            return 3;
        }
        if (NARROW_GLYPHS.indexOf(glyph) >= 0) {
            return 2;
        }
        return readableTypeface ? 6 : 5;
    }

    private static TextColor profileColor(TextColor color) {
        if (color.equals(NamedTextColor.WHITE)) {
            return PRIMARY;
        }
        if (color.equals(NamedTextColor.GRAY)) {
            return SECONDARY;
        }
        if (color.equals(NamedTextColor.AQUA)) {
            return TextColor.color(0x8CE1E1);
        }
        if (color.equals(NamedTextColor.LIGHT_PURPLE)) {
            return TextColor.color(0xD4AAEE);
        }
        if (color.equals(NamedTextColor.GOLD) || color.equals(NamedTextColor.YELLOW)) {
            return TextColor.color(0xF5CC83);
        }
        if (color.equals(NamedTextColor.RED)) {
            return TextColor.color(0xF4A5A0);
        }
        if (color.equals(NamedTextColor.GREEN)) {
            return TextColor.color(0xADE6AD);
        }
        return color;
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
