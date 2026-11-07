package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 両版の討伐報酬プレートに表示する、確定済みの本文と装飾の種類です。
 *
 * @param lines 見出し、取得元、固定報酬、区切り、アイテム行。5〜10行
 * @param rare レアドロップが含まれるか
 */
public record CombatRewardPlaque(@NotNull List<String> lines, boolean rare) {
    private static final int MIN_LINES = 5;
    private static final int MAX_LINES = 10;
    private static final int FRAME_WIDTH = 192;
    private static final int ITEM_INSET = 16;
    private static final int ITEM_ICON_GAP = 11;
    private static final int MAX_TEXT_WIDTH = FRAME_WIDTH - 2 * ITEM_INSET;
    private static final int DIVIDER_ADVANCE = 151;
    private static final int DIVIDER_INSET = (FRAME_WIDTH - DIVIDER_ADVANCE) / 2;
    private static final Key FONT = Key.key("astralrecord", "combat_reward_plaque");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern ICONS = Pattern.compile("[\uE700-\uE708]");

    /**
     * 行の順序を固定してプレートを構成します。
     * @throws IllegalArgumentException パックの対応範囲外の行数の場合
     */
    public CombatRewardPlaque {
        lines = List.copyOf(lines);
        if (lines.size() < MIN_LINES || lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("Combat reward plaque must have 5 to 10 lines.");
        }
    }

    /**
     * 一枚の枠に収まらない長い表示名を検出します。
     * @return 安全な幅で全行を表示できる場合はtrue
     */
    public boolean fits() {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) return false;
            if (index < 4 ? textWidth(line) > MAX_TEXT_WIDTH : !fitsItemRow(line)) return false;
        }
        return true;
    }

    /**
     * アイコン枠を含めてアイテム行が収まるかを調べます。
     * @param line 色コードを含む表示行
     * @return アイテム行の幅がプレートの内側に収まる場合はtrue
     */
    public static boolean fitsItemRow(@NotNull String line) {
        int iconGap = ICONS.matcher(line).find() ? ITEM_ICON_GAP : 0;
        return textWidth(line) + iconGap <= MAX_TEXT_WIDTH;
    }

    Component body(boolean bedrock, int visibleLines) {
        var body = Component.text();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) body.append(Component.newline());
            if (index >= visibleLines) {
                // 区切りを開示する前も表示幅を固定し、既に見えている行を動かしません。
                body.append(!bedrock && index == 3 ? Component.text("\uE731").font(FONT)
                        : Component.text("\u00a0"));
                continue;
            }
            if (!bedrock && index == 3) {
                body.append(javaPadding(DIVIDER_INSET), Component.text("\uE709").font(FONT),
                        javaPadding(FRAME_WIDTH - DIVIDER_ADVANCE - DIVIDER_INSET));
                continue;
            }
            Component line = LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(lines.get(index)));
            if (!bedrock) {
                boolean itemRow = index >= 4;
                line = line.replaceText(config -> config.match(ICONS)
                        .replacement((match, glyph) -> {
                            glyph.font(FONT);
                            if (itemRow) glyph.append(javaPadding(ITEM_ICON_GAP));
                            return glyph.build();
                        }));
                int inset = itemRow ? ITEM_INSET : (FRAME_WIDTH - textWidth(lines.get(index))) / 2;
                body.append(javaPadding(inset));
            }
            body.append(line);
        }
        return body.build();
    }

    Component frame(boolean sparkle) {
        int firstGlyph = rare ? (sparkle ? 0xe750 : 0xe720) : 0xe710;
        return Component.text(Character.toString(firstGlyph + lines.size() - MIN_LINES)).font(FONT);
    }

    int bedrockLayout() {
        return lines.size() | (rare ? 0x10 : 0);
    }

    private static Component javaPadding(int pixels) {
        var glyphs = new StringBuilder(8);
        for (int bit = 0; bit < 8; bit++) {
            if ((pixels & (1 << bit)) != 0) glyphs.append((char) (0xe740 + bit));
        }
        return Component.text(glyphs.toString()).font(FONT);
    }

    private static int textWidth(String line) {
        String legacy = ColorCodeUtil.translateAlternateColorCodes(line);
        String plain = ColorCodeUtil.stripColor(legacy);
        int bold = legacy.contains(ColorCodeUtil.BOLD) || legacy.contains("§L") ? 1 : 0;
        // Java版の通常フォントのピクセル幅を基準にし、枠の左右には余裕を残します。
        return plain.codePoints().map(codePoint -> {
            if (codePoint >= 0xe700 && codePoint <= 0xe708) return 11;
            return bold + switch (codePoint) {
                case ' ', 0xa0 -> 4;
                case '.', ',', '|', '!', ':', '\'', 'i', 'l' -> 3;
                default -> codePoint < 0x7f ? 6 : 9;
            };
        }).sum();
    }
}
