package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 両版の報酬プレートに表示する、行幅とページ高さをそろえた本文です。
 */
public final class CombatRewardPlaque {
    private static final int MIN_LINES = 5;
    private static final int MAX_LINES = 10;
    private static final int SPACER_INDEX = 3;
    private static final int DETAIL_START = SPACER_INDEX + 1;
    private static final int DETAIL_LINES = MAX_LINES - DETAIL_START;
    private static final long REVEAL_INTERVAL_TICKS = 2L;
    private static final int FRAME_WIDTH = 192;
    private static final int ITEM_INSET = 16;
    // 既存パックのアイコン枠にも収まるよう、Java版の字送りを維持します。
    private static final int ITEM_ICON_GAP = 11;
    private static final int MAX_TEXT_WIDTH = FRAME_WIDTH - 2 * ITEM_INSET;
    private static final Key FONT = Key.key("astralrecord", "combat_reward_plaque");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Pattern ICONS = Pattern.compile("[\uE700-\uE708]");
    private static final Pattern TOKENS = Pattern.compile("(?i)§[0-9a-fk-or]|\\X");
    private static final String BLANK = "\u00a0";

    private final List<List<String>> pages;
    private final int lineCount;
    private final Supplier<String> summary;
    private final boolean rare;

    private CombatRewardPlaque(List<List<String>> pages, int lineCount, Supplier<String> summary, boolean rare) {
        this.pages = pages;
        this.lineCount = lineCount;
        this.summary = summary;
        this.rare = rare;
    }

    /**
     * 表示内容を幅に収め、詳細は最大6行のページに分けます。
     * @param title 結果の見出し
     * @param subtitle 取得元の表示名
     * @param summary 固定報酬または帰還残秒。動的に更新する場合は1行に収まる短い本文
     * @param details 獲得品と戦績。1ページに収まる項目は分割しません
     * @param rare レアドロップが含まれるか
     * @return 両版用の報酬プレート
     */
    public static @NotNull CombatRewardPlaque layout(
            @NotNull String title, @NotNull String subtitle, @NotNull Supplier<String> summary,
            @NotNull List<Row> details, boolean rare
    ) {
        var titleLines = wrap(title, "", "", false);
        var subtitleLines = wrap(subtitle, "", "", false);
        var summaryLines = wrap(summary.get(), "", "", false);
        var header = List.of(titleLines.getFirst(), subtitleLines.getFirst(), summaryLines.getFirst(), BLANK);
        var groups = new ArrayList<List<String>>();
        groups.add(titleLines.subList(1, titleLines.size()));
        groups.add(subtitleLines.subList(1, subtitleLines.size()));
        groups.add(summaryLines.subList(1, summaryLines.size()));
        for (Row row : details) {
            groups.add(wrap(row.text(), row.prefix(), row.suffix(), true));
        }

        var pages = new ArrayList<List<String>>();
        var page = new ArrayList<>(header);
        for (List<String> group : groups) {
            if (group.size() <= DETAIL_LINES && page.size() + group.size() > MAX_LINES) {
                pages.add(List.copyOf(page));
                page = new ArrayList<>(header);
            }
            for (String line : group) {
                if (page.size() == MAX_LINES) {
                    pages.add(List.copyOf(page));
                    page = new ArrayList<>(header);
                }
                page.add(line);
            }
        }
        pages.add(List.copyOf(page));
        int lineCount = pages.stream().mapToInt(List::size).max().orElse(MIN_LINES);
        return new CombatRewardPlaque(List.copyOf(pages), Math.max(MIN_LINES, lineCount),
                summaryLines.size() == 1 ? summary : summaryLines::getFirst, rare);
    }

    /**
     * 数量・確率などの末尾は可能な限りまとめ、字形と書式を保持して折り返します。
     * @param text 表示名または詳細本文
     * @param prefix アイコンなど先頭だけに置く装飾
     * @param suffix 数量や確率など末尾に置く情報
     * @param itemRow アイコンと文字の間の余白を加えるか
     * @return 折り返し後の物理行
     */
    private static List<String> wrap(String text, String prefix, String suffix, boolean itemRow) {
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        var activeStyle = new StringBuilder();
        String name = ColorCodeUtil.translateAlternateColorCodes(prefix + text);
        String tail = ColorCodeUtil.translateAlternateColorCodes(suffix);
        var tokens = TOKENS.matcher(name + tail);
        int width = 0;
        boolean bold = false;
        while (tokens.find()) {
            String token = tokens.group();
            if (tokens.start() == name.length() && width > 0 && fitsItemRow(activeStyle.toString() + tail)
                    && !fitsItemRow(line.toString() + tail)) {
                lines.add(line.toString());
                line = new StringBuilder(activeStyle);
                width = 0;
            }
            if (token.length() == 2 && token.charAt(0) == '§') {
                char code = Character.toLowerCase(token.charAt(1));
                if (code == 'r' || Character.digit(code, 16) >= 0) {
                    activeStyle.setLength(0);
                    bold = false;
                }
                activeStyle.append(token);
                bold |= code == 'l';
                line.append(token);
                continue;
            }
            if (token.charAt(0) == '\r' || token.charAt(0) == '\n') {
                lines.add(width == 0 ? BLANK : line.toString());
                line = new StringBuilder(activeStyle);
                width = 0;
                continue;
            }
            // 明示的な改行と折り返しの先頭に、直前の色と装飾を引き継ぎます。
            int tokenWidth = token.codePoints().map(cp -> glyphWidth(cp, itemRow)).sum();
            if (bold) tokenWidth += token.codePointCount(0, token.length());
            if (width > 0 && width + tokenWidth > MAX_TEXT_WIDTH) {
                lines.add(line.toString());
                line = new StringBuilder(activeStyle);
                width = 0;
            }
            if (width == 0 && !lines.isEmpty() && token.equals(" ")) continue;
            line.append(token);
            width += tokenWidth;
        }
        lines.add(width == 0 ? BLANK : line.toString());
        return lines;
    }

    /**
     * プレート上でひとまとまりに扱う情報です。
     * @param prefix 先頭のアイコンなど
     * @param text 名称または詳細本文
     * @param suffix 数量や確率などまとめて置く末尾の情報
     */
    public record Row(@NotNull String prefix, @NotNull String text, @NotNull String suffix) {
        /**
         * アイコンや数量を伴わない詳細本文を用意します。
         * @param text 色コードと改行を含められる詳細本文
         * @return 詳細項目
         */
        public static @NotNull Row text(@NotNull String text) {
            return new Row("", text, "");
        }
    }

    /**
     * アイコン枠を含めてアイテム行が収まるかを調べます。
     * @param line 色コードを含む表示行
     * @return アイテム行の幅がプレートの内側に収まる場合はtrue
     */
    public static boolean fitsItemRow(@NotNull String line) {
        return line.indexOf('\n') < 0 && line.indexOf('\r') < 0 && textWidth(line, true) <= MAX_TEXT_WIDTH;
    }

    /** @return 表示するページ数 */
    int pageCount() {
        return pages.size();
    }

    /** @return 全ページ共通の物理行数 */
    int lineCount() {
        return lineCount;
    }

    /** @return 現在の固定報酬または帰還残秒 */
    String summary() {
        String text = summary.get();
        return text.isBlank() ? BLANK : ColorCodeUtil.translateAlternateColorCodes(text);
    }

    /** @return レアドロップが含まれるか */
    boolean rare() {
        return rare;
    }

    /**
     * 最初の行から本文を順に開示し、装飾用の空行には開示時間を割り当てません。
     * @param age 表示開始からの経過tick
     * @return 先頭から開示した行数。途中の空行を含む
     */
    int visibleLines(long age) {
        int revealStep = (int) Math.min(age / REVEAL_INTERVAL_TICKS, lineCount - 2L);
        return 1 + revealStep + (revealStep >= SPACER_INDEX ? 1 : 0);
    }

    /**
     * @return 最後の行を開示する表示開始からの経過tick
     */
    long revealDurationTicks() {
        return (lineCount - 2L) * REVEAL_INTERVAL_TICKS;
    }

    /**
     * 行を開示しつつ、空白でプレートの行数とJava版の全幅を固定します。
     * @param bedrock 統合版の名札として整形するか
     * @param pageIndex 表示中のページ番号。0始まり
     * @param visibleLines 開示済みの先頭行数
     * @param summary 現在の固定報酬または帰還残秒
     * @return 行数が固定された表示本文
     */
    Component body(boolean bedrock, int pageIndex, int visibleLines, String summary) {
        List<String> lines = pages.get(pageIndex);
        var body = Component.text();
        for (int index = 0; index < lineCount; index++) {
            if (index > 0) body.append(Component.newline());
            if (!bedrock && index == SPACER_INDEX) {
                body.append(Component.text("\uE731").font(FONT));
                continue;
            }
            if (index >= visibleLines || index >= lines.size()) {
                body.append(Component.text(BLANK));
                continue;
            }
            String text = index == 2 ? summary : lines.get(index);
            Component line = LEGACY.deserialize(text);
            if (!bedrock) {
                boolean itemRow = index > SPACER_INDEX;
                line = line.replaceText(config -> config.match(ICONS)
                        .replacement((match, glyph) -> {
                            glyph.font(FONT);
                            if (itemRow) glyph.append(javaPadding(ITEM_ICON_GAP));
                            return glyph.build();
                        }));
                int inset = itemRow ? ITEM_INSET : (FRAME_WIDTH - textWidth(text, false)) / 2;
                body.append(javaPadding(inset));
            }
            body.append(line);
        }
        return body.build();
    }

    Component frame(boolean sparkle) {
        int firstGlyph = rare ? (sparkle ? 0xe750 : 0xe720) : 0xe710;
        return Component.text(Character.toString(firstGlyph + lineCount - MIN_LINES)).font(FONT);
    }

    int bedrockLayout() {
        return lineCount | (rare ? 0x10 : 0);
    }

    private static Component javaPadding(int pixels) {
        var glyphs = new StringBuilder(8);
        for (int bit = 0; bit < 8; bit++) {
            if ((pixels & (1 << bit)) != 0) glyphs.append((char) (0xe740 + bit));
        }
        return Component.text(glyphs.toString()).font(FONT);
    }

    private static int textWidth(String line, boolean itemRow) {
        var tokens = TOKENS.matcher(ColorCodeUtil.translateAlternateColorCodes(line));
        int width = 0;
        boolean bold = false;
        while (tokens.find()) {
            String token = tokens.group();
            if (token.length() == 2 && token.charAt(0) == '§') {
                char code = Character.toLowerCase(token.charAt(1));
                if (code == 'r' || Character.digit(code, 16) >= 0) bold = false;
                bold |= code == 'l';
            } else {
                width += token.codePoints().map(cp -> glyphWidth(cp, itemRow)).sum();
                if (bold) width += token.codePointCount(0, token.length());
            }
        }
        return width;
    }

    private static int glyphWidth(int codePoint, boolean itemRow) {
        // Java版の通常フォントのピクセル幅を基準にし、枠の左右には余裕を残します。
        if (codePoint >= 0xe700 && codePoint <= 0xe708) return 11 + (itemRow ? ITEM_ICON_GAP : 0);
        return switch (codePoint) {
            case ' ', 0xa0 -> 4;
            case '.', ',', '|', '!', ':', '\'', 'i', 'l' -> 3;
            case '@', '~' -> 7;
            default -> codePoint < 0x7f ? 6 : 9;
        };
    }
}
