package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.shared.display.DisplaySeparators;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/** スキルを表示する各 GUI で使うロアの書式です。 */
public final class SkillLore {
    private static final NamespacedKey TOOLTIP_KEY = new NamespacedKey("astralrecord", "skill_tooltip");
    private static final String MATERIAL_MARKER = "astralrecord:skill_material";
    private static final String GEM_MARKER = "astralrecord:skill_gem:";
    private static final TextColor BODY = TextColor.color(0xe8e3d6);
    private static final TextColor LABEL = TextColor.color(0xc5d0e1);
    private static final Map<String, Integer> GEM_VARIANTS = Map.of(
        "10a00019", 0, "10a00025", 1, "10a00028", 2, "10a00030", 3,
        "10a00037", 4, "10a00040", 5, "10a00044", 6, "10a00053", 7
    );
    private static final String BULLET = " ▸ ";
    private static final String CONTINUATION = "   ";
    private static final int LINE_WIDTH = 42;

    private SkillLore() {
    }

    /**
     * 表示用スタックへ受信者別の装飾に使う識別子を記録します。
     * @param item 装飾対象の表示用スタック
     * @param skillId 専用字形を選ぶスキルID。共通字形を使う場合は null
     * @return 識別子を記録した同じスタック
     */
    public static @NotNull ItemStack style(@NotNull ItemStack item, @Nullable String skillId) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(
            TOOLTIP_KEY, PersistentDataType.STRING, skillId == null ? "" : skillId
        ));
        return item;
    }

    /**
     * 表示用スタックから受信者別装飾の識別子を取得します。
     * @param data 他のアイテム判定と共有する読み取り専用PDC
     * @return 未登録では null、共通字形では空文字列、専用字形ではスキルID
     */
    public static @Nullable String styleId(@NotNull PersistentDataContainerView data) {
        return data.get(TOOLTIP_KEY, PersistentDataType.STRING);
    }

    /**
     * 見出しと短い装飾線を1行で追加します。
     * @param lore 追加先のロア
     * @param title 見出し
     * @param color 見出しの色
     */
    public static void section(@NotNull List<Component> lore, @NotNull String title, @NotNull NamedTextColor color) {
        String heading = "❖ " + title;
        Component line = Component.text(heading, color);
        int available = LINE_WIDTH - heading.codePoints().map(SkillLore::characterWidth).sum() - 2;
        int ornamentLength = Math.min(8, available / 2);
        if (ornamentLength >= 3) {
            String separator = DisplaySeparators.SECTION;
            String ornament = separator.substring(0, ornamentLength - 1) + separator.charAt(separator.length() - 1);
            line = line.append(Component.text("  " + ornament, NamedTextColor.DARK_GRAY));
        }
        lore.add(line);
    }

    /**
     * 箇条書きのラベルと値を組み合わせます。
     * @param label 項目名。値だけを表示する場合は空文字列
     * @param value 書式を保持する値
     * @return ロアの1行
     */
    public static @NotNull Component row(@NotNull String label, @NotNull Component value) {
        return row(Component.text(BULLET, LABEL), label, value);
    }

    /**
     * 共通素材字形を使う素材行を作成します。
     * @param label 項目名。値だけを表示する場合は空文字列
     * @param value 素材名や数量
     * @return ロアの1行
     */
    public static @NotNull Component materialRow(@NotNull String label, @NotNull Component value) {
        return materialRow(null, label, value);
    }

    /**
     * 原石ではランク別字形を選べる素材行を作成します。
     * @param itemId 素材のマスターID。共通素材字形を使う場合は null
     * @param label 項目名。値だけを表示する場合は空文字列
     * @param value 素材名や数量
     * @return パックなしでも箇条書きとして読めるロアの1行
     */
    public static @NotNull Component materialRow(
        @Nullable String itemId, @NotNull String label, @NotNull Component value
    ) {
        Integer variant = itemId == null ? null : GEM_VARIANTS.get(itemId);
        return row(Component.text(BULLET, LABEL).insertion(
            variant == null ? MATERIAL_MARKER : GEM_MARKER + variant
        ), label, value);
    }

    private static @NotNull Component row(Component prefix, String label, Component value) {
        return Component.empty()
            .append(prefix)
            .append(Component.text(label.isBlank() ? "" : label + " ", LABEL))
            .append(body(value));
    }

    /**
     * 比較や数量に使う値を強調します。
     * @param value 単位を含む表示用の値
     * @return 黄色と太字を付けた値
     */
    public static @NotNull Component value(@NotNull String value) {
        return Component.text(value, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD);
    }

    /**
     * 操作案内や補足を共通の色で表示します。
     * @param text 案内文
     * @return ロアの補足行
     */
    public static @NotNull Component note(@NotNull String text) {
        return Component.text(text, LABEL);
    }

    /**
     * 通常の説明色を共通の本文色に揃え、数値や状態の強調色は維持します。
     * @param text 書式を揃える本文
     * @return 本文色を適用したコンポーネント
     */
    public static @NotNull Component body(@NotNull Component text) {
        return palette(text).colorIfAbsent(BODY);
    }

    private static Component palette(Component text) {
        TextColor color = text.color();
        Component adjusted = NamedTextColor.WHITE.equals(color) || NamedTextColor.GRAY.equals(color)
            ? text.color(BODY) : text;
        return adjusted.children().isEmpty() ? adjusted
            : adjusted.children(adjusted.children().stream().map(SkillLore::palette).toList());
    }

    /**
     * 送信用コピーの素材行へ受信者のパックが持つ字形を差し込みます。
     * @param line 入力のロア行
     * @param materialGlyph 共通素材の字形
     * @param gemGlyph 原石ランクを字形に変換する関数
     * @return 対象となる素材行だけを装飾したロア
     */
    public static @NotNull Component decorateMaterials(
        @NotNull Component line, @NotNull Component materialGlyph, @NotNull IntFunction<Component> gemGlyph
    ) {
        String marker = line.insertion();
        Component selected = null;
        if (MATERIAL_MARKER.equals(marker)) {
            selected = materialGlyph;
        } else if (marker != null && marker.startsWith(GEM_MARKER)
            && marker.length() == GEM_MARKER.length() + 1) {
            int variant = marker.charAt(GEM_MARKER.length()) - '0';
            if (variant >= 0 && variant < GEM_VARIANTS.size()) {
                selected = gemGlyph.apply(variant);
            }
        }
        if (selected != null) {
            return Component.text(" ").append(selected).append(Component.space())
                .append(Component.empty().children(line.children()));
        }
        return line.children().isEmpty() ? line : line.children(line.children().stream()
            .map(child -> decorateMaterials(child, materialGlyph, gemGlyph)).toList());
    }

    /**
     * 空行を整え、書式を保持して長い行を折り返します。
     * @param lore 入力のロア
     * @return 整形済みの変更不能なロア一覧
     */
    public static @NotNull List<Component> finish(@NotNull List<Component> lore) {
        List<Component> result = new ArrayList<>();
        boolean blank = false;
        for (Component line : lore) {
            if (line.equals(Component.empty())) {
                blank = !result.isEmpty();
                continue;
            }
            if (blank) {
                result.add(Component.empty());
                blank = false;
            }
            result.addAll(wrap(line));
        }
        return List.copyOf(result);
    }

    /**
     * マスターの文頭ラベルを除いた本文を、書式を維持して取り出します。
     * @param line 元の本文
     * @param length 取り除く文頭のUTF-16文字数
     * @return 文頭ラベルを除いた本文
     */
    public static @NotNull Component dropPrefix(@NotNull Component line, int length) {
        List<TextComponent> runs = new ArrayList<>();
        if (!flatten(line, Style.empty(), runs)) {
            return line;
        }
        int end = runs.stream().mapToInt(run -> run.content().length()).sum();
        return slice(runs, Math.min(length, end), end);
    }

    private static List<Component> wrap(Component line) {
        List<TextComponent> runs = new ArrayList<>();
        if (!flatten(line, Style.empty(), runs)) {
            return List.of(line);
        }
        StringBuilder text = new StringBuilder();
        for (TextComponent run : runs) {
            text.append(run.content());
        }
        if (text.isEmpty()) {
            return List.of(line);
        }

        String plain = text.toString();
        BreakIterator breaks = BreakIterator.getLineInstance(Locale.JAPANESE);
        breaks.setText(plain);
        List<Component> result = new ArrayList<>();
        for (int start = 0; start < plain.length();) {
            int available = result.isEmpty() ? LINE_WIDTH : LINE_WIDTH - CONTINUATION.length();
            int end = start;
            while (end < plain.length()) {
                int codePoint = plain.codePointAt(end);
                if (codePoint == '\n' || codePoint == '\r') {
                    break;
                }
                available -= characterWidth(codePoint);
                if (available < 0) {
                    break;
                }
                end += Character.charCount(codePoint);
            }
            if (start == 0 && end == plain.length()) {
                return List.of(line);
            }

            int cut = end;
            if (available < 0 && !breaks.isBoundary(end)) {
                int boundary = breaks.preceding(end);
                if (boundary > start && plain.codePointCount(boundary, end) < 9) {
                    cut = boundary;
                }
            }
            while (cut > start && Character.isWhitespace(plain.codePointBefore(cut))) {
                cut -= Character.charCount(plain.codePointBefore(cut));
            }
            Component part = slice(runs, start, cut);
            result.add(result.isEmpty() ? part : Component.text(CONTINUATION).append(part));
            start = Math.max(cut, start + (cut == start ? Character.charCount(plain.codePointAt(start)) : 0));
            while (start < plain.length() && Character.isWhitespace(plain.codePointAt(start))) {
                start += Character.charCount(plain.codePointAt(start));
            }
        }
        return result;
    }

    /** 和文とASCIIの概算字幅。クライアントごとの描画差を吸収するため幅に余白を残します。 */
    private static int characterWidth(int codePoint) {
        return codePoint <= 0x7e || codePoint >= 0xff61 && codePoint <= 0xff9f ? 1 : 2;
    }

    private static boolean flatten(Component component, Style inherited, List<TextComponent> runs) {
        if (!(component instanceof TextComponent text)) {
            return false;
        }
        Style style = text.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (!text.content().isEmpty()) {
            runs.add(Component.text(text.content(), style));
        }
        for (Component child : text.children()) {
            if (!flatten(child, style, runs)) {
                return false;
            }
        }
        return true;
    }

    private static Component slice(List<TextComponent> runs, int start, int end) {
        var result = Component.text();
        int offset = 0;
        for (TextComponent run : runs) {
            int next = offset + run.content().length();
            if (start < next && end > offset) {
                int left = Math.max(start - offset, 0);
                int right = Math.min(end - offset, run.content().length());
                if (left < right) {
                    result.append(run.content(run.content().substring(left, right)));
                }
            }
            offset = next;
        }
        return result.build();
    }
}
