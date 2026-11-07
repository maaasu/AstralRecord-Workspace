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
    static final Key CONDITION_FONT = Key.key("astralrecord", "condition_hud");
    private static final Key DEFAULT_FONT = Key.key("minecraft", "default");
    // Java 1.21.11 の標準・Unicode書体のうち、小さい方の送り幅（ASCII 0x20～0x7E）。
    private static final String DEFAULT_ASCII_MIN_ADVANCES = "4134444122442324"
            + "4344444444223434"
            + "4444444443444444"
            + "4444444444424244"
            + "2444443442343444"
            + "444434444443134";
    static final int FIRST_CONDITION_ICON = 0xE970;
    private static final int LAST_CONDITION_ICON = 0xE977;
    private static final String TEXT_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789vmaxs?+-/()%[]";
    private static final TextColor PRIMARY = NamedTextColor.WHITE;
    private static final TextColor SECONDARY = NamedTextColor.GRAY;
    private static final TextColor DIVIDER = NamedTextColor.GRAY;
    private static final String NARROW_GLYPHS = ".,:|!";
    private static final int LEFT_OFFSET = 8;
    private static final int PROFILE_LEFT_OF_CENTER = 118;
    private static final int PROFILE_VIEW_HALF_WIDTH = 154;
    private static final int WIDE_PROFILE_LEFT_OF_CENTER = 142;
    private static final int WIDE_RESOURCE_TARGET_WIDTH = 268;
    private static final int RESOURCE_GAP = 10;
    private static final int BUFF_CONDITION_GAP = 10;
    private static final int READABLE_ICON_GAP = 5;
    private static final int FIRST_ICON = 0xE940;
    private static final int LAST_ICON = 0xE944;
    private static final int POSITIVE_SPACE = 0xE950;
    private static final int NEGATIVE_SPACE = 0xE955;
    private static final int LARGEST_SPACE_BIT = 4;
    private static final int METER_ADVANCE = 13;
    private static final int WIDE_METER_ADVANCE = 25;
    private static final int FIRST_WIDE_METER = 0xE960;
    static final Component READABLE_RESOURCE_GAP = spacing(RESOURCE_GAP, GAMEPLAY_FONT);

    private JavaProfileHud() {
    }

    /**
     * 戦闘行を基準に、成長情報と状態異常を別の高さへ配置します。
     * @param combat 戦闘情報や一時表示の行
     * @param profile プレイヤーとクラスの成長情報
     * @param conditions 状態異常のアイコンと残り秒数
     * @param buffs 有効なバフのアイコン列
     * @param vanillaTypeface 互換版5の小文字を使う場合true
     * @param wideLayout 互換版6の横長配置と小さい正式クラス名を使う場合true
     * @return 上側の各行の描画幅を相殺したアクションバー
     */
    static Component compose(Component combat, @Nullable ProfileHudText profile, Component conditions,
            BuffHudIcons.Line buffs, boolean vanillaTypeface, boolean wideLayout) {
        if (profile == null && conditions.equals(Component.empty()) && buffs.width() == 0) {
            return combat;
        }
        int halfWidth = combatWidthUpperBound(combat, null, false, true) / 2;
        int shift = wideLayout ? halfWidth - WIDE_PROFILE_LEFT_OF_CENTER
                : Math.max(-LEFT_OFFSET, halfWidth - PROFILE_LEFT_OF_CENTER);
        return upperLayers(profile, conditions, buffs, combat, halfWidth, shift, vanillaTypeface, wideLayout)
                .append(combat);
    }

    /**
     * 補足情報の行を基準に、戦闘リソース・個人情報・状態異常を別の高さに描画します。
     * @param resources 専用フォントで整形したHP・MP・ENG
     * @param details 補足情報の行
     * @param profile 個人情報を表示する場合の内容
     * @param conditions 状態異常のアイコンと残り秒数
     * @param buffs 有効なバフのアイコン列
     * @param vanillaTypeface 互換版5の小文字を使う場合true
     * @param wideLayout 互換版6の横長配置と小さい正式クラス名を使う場合true
     * @return リソースと補足情報の横幅を互いに加算しないアクションバー
     */
    static Component arrangeCombat(Component resources, Component details, @Nullable ProfileHudText profile,
            Component conditions, BuffHudIcons.Line buffs, boolean vanillaTypeface, boolean wideLayout) {
        int detailsWidth = combatWidthUpperBound(details, null, false, true);
        if (detailsWidth == 0) {
            return compose(resources, profile, conditions, buffs, vanillaTypeface, wideLayout);
        }
        int resourcesWidth = combatWidthUpperBound(resources, null, false, true);
        int detailsHalf = detailsWidth / 2;
        int resourceShift = detailsHalf - resourcesWidth / 2;
        int profileShift = wideLayout ? detailsHalf - WIDE_PROFILE_LEFT_OF_CENTER
                : Math.max(resourceShift - LEFT_OFFSET, detailsHalf - PROFILE_LEFT_OF_CENTER);
        return upperLayers(profile, conditions, buffs, details, detailsHalf, profileShift, vanillaTypeface, wideLayout)
                .append(overlay(new OverlayLine(resources, resourcesWidth), GAMEPLAY_FONT, resourceShift))
                .append(details);
    }

    private static Component upperLayers(@Nullable ProfileHudText profile, Component conditions,
            BuffHudIcons.Line buffs, Component base, int halfWidth, int preferredShift, boolean vanillaTypeface,
            boolean wideLayout) {
        Component layers = profile == null ? Component.empty()
                : profileLayers(profile, halfWidth, preferredShift, vanillaTypeface, wideLayout);
        boolean hasConditions = !conditions.equals(Component.empty());
        if (!hasConditions && buffs.width() == 0) {
            return layers;
        }
        // 標準書体を含む行での過大な幅見積もりで、秒数が右端から出ないようにする。
        int knownHalfWidth = Math.max(0, combatWidth(base, null, false, true, false) / 2);
        int conditionLeft = knownHalfWidth + PROFILE_VIEW_HALF_WIDTH;
        if (hasConditions) {
            int conditionWidth = combatWidthUpperBound(conditions, CONDITION_FONT, false, true);
            conditionLeft -= conditionWidth;
            layers = layers.append(overlay(new OverlayLine(conditions.font(CONDITION_FONT), conditionWidth),
                    CONDITION_FONT, conditionLeft));
        }
        if (buffs.width() > 0) {
            int buffShift = halfWidth - buffs.width() / 2;
            if (hasConditions) {
                buffShift = Math.min(buffShift, conditionLeft - BUFF_CONDITION_GAP - buffs.width());
            }
            layers = layers.append(overlay(new OverlayLine(buffs.text(), buffs.width()), BuffHudIcons.FONT, buffShift));
        }
        return layers;
    }

    private static Component profileLayers(ProfileHudText profile, int halfWidth, int preferredShift,
            boolean vanillaTypeface, boolean compactClassName) {
        OverlayLine player = withHudFont(profile.playerLine(), PLAYER_FONT, true, vanillaTypeface, compactClassName);
        OverlayLine playerClass = withHudFont(profile.classLineWithPoints(), CLASS_FONT, true, vanillaTypeface,
                compactClassName);
        OverlayLine points = new OverlayLine(Component.empty(), 0);
        if (playerClass.width() > PROFILE_VIEW_HALF_WIDTH * 2 && !profile.points().equals(Component.empty())) {
            playerClass = withHudFont(profile.classLine(), CLASS_FONT, true, vanillaTypeface, compactClassName);
            points = withHudFont(profile.points(), POINTS_FONT, true, vanillaTypeface, compactClassName);
        }
        int maxLineWidth = Math.max(points.width(), Math.max(player.width(), playerClass.width()));
        int rightLimit = halfWidth + PROFILE_VIEW_HALF_WIDTH - maxLineWidth;
        int leftLimit = halfWidth - PROFILE_VIEW_HALF_WIDTH;
        int shift = Math.max(leftLimit, Math.min(preferredShift, rightLimit));
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
        return combatWidth(component, parentFont, parentBold, readableTypeface, true);
    }

    private static int combatWidth(Component component, Key parentFont, boolean parentBold, boolean readableTypeface,
            boolean estimateOtherFonts) {
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
                if (CONDITION_FONT.equals(font) && glyph >= FIRST_CONDITION_ICON && glyph <= LAST_CONDITION_ICON) {
                    width += 11;
                } else if (GAMEPLAY_FONT.equals(font) || DETAIL_FONT.equals(font) || CONDITION_FONT.equals(font)) {
                    width += gameplayAdvance(glyph, readableTypeface);
                } else if (!estimateOtherFonts) {
                    if (font == null || DEFAULT_FONT.equals(font)) {
                        width += defaultFontMinimumAdvance(glyph);
                    }
                    continue;
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
            width += combatWidth(child, font, bold, readableTypeface, estimateOtherFonts);
        }
        return width;
    }

    private static int defaultFontMinimumAdvance(int glyph) {
        if (glyph >= 0x20 && glyph <= 0x7E) {
            return DEFAULT_ASCII_MIN_ADVANCES.charAt(glyph - 0x20) - '0';
        }
        if (glyph == '█' || glyph == '▰') {
            return 5;
        }
        if ((glyph >= 0x3001 && glyph <= 0x30FF) || (glyph >= 0x3400 && glyph <= 0x9FFF)) {
            return 8;
        }
        return 0;
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
     * 個人情報と戦闘の補足情報を専用フォントへ変換し、字送りを合計します。
     * @param source 表示内容
     * @param font 表示に使う専用フォント
     * @param readableTypeface 互換版3の字形を使う場合true
     * @param vanillaTypeface 互換版5の小文字を使う場合true
     * @param compactClassName 互換版6の小さい正式クラス名を使う場合true
     * @return 変換後の表示と字送り
     */
    private static OverlayLine withHudFont(Component source, Key font, boolean readableTypeface,
            boolean vanillaTypeface, boolean compactClassName) {
        Key classNameFont = compactClassName ? ClassNameGlyphs.COMPACT_FONT : ClassNameGlyphs.FONT;
        Key renderedFont = ClassNameGlyphs.FONT.equals(source.font()) ? classNameFont : font;
        TextComponent.Builder line = Component.text().font(renderedFont);
        if (source.color() != null) {
            line.color(source.color());
        }
        int width = 0;
        if (source instanceof TextComponent text && classNameFont.equals(renderedFont)) {
            line.content(text.content());
            width += text.content().codePoints().map(glyph -> ClassNameGlyphs.advance(glyph, compactClassName)).sum();
        } else if (source instanceof TextComponent text) {
            String content = text.content();
            if (!vanillaTypeface && "   ".equals(content) && source.children().isEmpty()) {
                String separator = "   |   ";
                int advance = textAdvance(separator, readableTypeface);
                return new OverlayLine(Component.text(separator, DIVIDER).font(font), advance);
            }
            StringBuilder glyphs = new StringBuilder();
            for (int offset = 0; offset < content.length();) {
                int original = content.codePointAt(offset);
                offset += Character.charCount(original);
                boolean lowercase = vanillaTypeface && original >= 'a' && original <= 'z';
                int glyph = lowercase || TEXT_GLYPHS.indexOf(original) >= 0 ? original : Character.toUpperCase(original);
                if (glyph == '〚' || glyph == '〛') {
                    glyph = glyph == '〚' ? '[' : ']';
                }
                if (glyph != ' ' && (glyph < FIRST_ICON || glyph > LAST_ICON)
                        && !lowercase && TEXT_GLYPHS.indexOf(glyph) < 0 && NARROW_GLYPHS.indexOf(glyph) < 0) {
                    glyph = '?';
                }
                glyphs.appendCodePoint(glyph);
                width += gameplayAdvance(glyph, readableTypeface);
            }
            line.content(glyphs.toString());
        }
        for (Component child : source.children()) {
            OverlayLine formatted = withHudFont(child, renderedFont, readableTypeface, vanillaTypeface,
                    compactClassName);
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

    static Component styleStat(Component stat, boolean enabled, boolean readableTypeface, boolean vanillaTypeface) {
        Key font = readableTypeface ? DETAIL_FONT : GAMEPLAY_FONT;
        return enabled ? withHudFont(stat, font, readableTypeface, vanillaTypeface, false).text() : stat;
    }

    /** 3種のリソースの送り幅に合わせ、横長HUDの2つの間隔を等しく確保します。 */
    static Component wideResourceGap(Component hp, Component mp, Component energy) {
        int resourcesWidth = combatWidthUpperBound(hp, null, false, true)
                + combatWidthUpperBound(mp, null, false, true)
                + combatWidthUpperBound(energy, null, false, true);
        int preferredGap = Math.max(RESOURCE_GAP, (WIDE_RESOURCE_TARGET_WIDTH - resourcesWidth) / 2);
        int maxGap = Math.max(0, (PROFILE_VIEW_HALF_WIDTH * 2 - resourcesWidth) / 2);
        return spacing(Math.min(preferredGap, maxGap), GAMEPLAY_FONT);
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
