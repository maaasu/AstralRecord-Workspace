package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout;
import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout.Row;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import java.util.List;
import java.util.ArrayList;

import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.SOLID_PIXEL;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.appendGlyph;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.QUEST_TEXT;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.appendPixel;

/** 専用パックで画面左上へ移す、送り幅ゼロのクエスト字形を組み立てます。 */
public final class QuestHudGlyphs {
    private static final int LEFT = 8;
    private static final int MARKER_X = LEFT + 5;
    private static final int LABEL_X = LEFT + 18;
    private static final int BODY_INDENT = 5;
    private static final int CONTINUATION_INDENT = 8;
    private static final int TEXT_RIGHT = LEFT + QuestHudLayout.WIDTH - 5;
    private static final int VALUE_GAP = 5;
    private static final int GUIDE_VALUE_GAP = 3;
    private static final int VALUE_MAX_WIDTH = TEXT_RIGHT - LABEL_X - 22;
    private static final int BAR_WIDTH = 18;
    private static final int BAR_X = LEFT + 122;
    private static final int TEXT_MARKER = 180;
    private static final int FRAME_MARKER = 220;
    private static final int DIVIDER_MARKER = 221;
    private static final int TRACK_MARKER = 222;
    private static final int FILL_MARKER = 223;
    private static final int ICON_MARKER = 224;
    private static final int COMPLETE_FILL_MARKER = 225;
    private static final int GUIDE_BIT = 16;
    private static final int SHADOW_BIT = 32;

    private QuestHudGlyphs() {
    }

    /**
     * 左上へ描画する枠・クエスト行を既存 ActionBar に追加できる幅ゼロの Component に変換します。
     * 互換パック適用後にだけ呼び出してください。表示幅を超えるラベルは省略し、数値欄を右へ揃えます。
     *
     * @param rows 表示順に並ぶクエスト HUD の行。空の場合は枠を表示しない
     * @return 元の ActionBar の中央寄せを変えない追加要素
     */
    public static Component render(List<Row> rows) {
        return render(rows, false);
    }

    /** Java v3の字形契約だけがガイド色と影のビットを解釈します。 */
    public static Component render(List<Row> rows, boolean transparentTheme) {
        if (rows.isEmpty()) {
            return Component.empty();
        }
        TextComponent.Builder result = HudOverlayGlyphs.builder();
        int rowCount = rows.size();
        for (int edge = 0; edge < 8; edge++) {
            appendPixel(result, FRAME_MARKER, rowCount, edge);
        }
        for (int index = 0; index < rowCount; index++) {
            Row row = rows.get(index);
            int palette = switch (row.kind()) {
                case TITLE, GUIDE_TITLE -> 0;
                case OBJECTIVE, GUIDE_ACTION, GUIDE_CONTINUATION -> 1;
                case COMPLETE -> 2;
                case OVERFLOW -> 3;
            };
            int textMarker = TEXT_MARKER + (rowCount - 1) * 4 + palette;
            int shapeRow = rowCount * 16 + index;
            boolean guide = row.kind() == QuestHudLayout.Kind.GUIDE_TITLE
                || row.kind() == QuestHudLayout.Kind.GUIDE_ACTION || row.kind() == QuestHudLayout.Kind.GUIDE_CONTINUATION;
            int section = guide ? GUIDE_BIT : 0;
            int labelX = LABEL_X + (transparentTheme ? switch (row.kind()) {
                case GUIDE_ACTION, OBJECTIVE, COMPLETE -> BODY_INDENT;
                case GUIDE_CONTINUATION -> CONTINUATION_INDENT;
                default -> 0;
            } : 0);
            List<Integer> value = QUEST_TEXT.fit(row.value(), VALUE_MAX_WIDTH);
            int valueX = TEXT_RIGHT - QUEST_TEXT.width(value);
            int labelRight = value.isEmpty() ? TEXT_RIGHT : valueX - (guide ? GUIDE_VALUE_GAP : VALUE_GAP);
            List<Integer> label = QUEST_TEXT.fit(row.label(), labelRight - labelX);
            switch (row.kind()) {
                case TITLE, GUIDE_TITLE -> {
                    if (index > 0) {
                        boolean sectionBoundary = transparentTheme && row.kind() == QuestHudLayout.Kind.TITLE
                            && rows.get(index - 1).kind() == QuestHudLayout.Kind.GUIDE_CONTINUATION;
                        appendPixel(result, DIVIDER_MARKER, shapeRow, sectionBoundary ? 1 : 0);
                    }
                    appendGlyph(result, SOLID_PIXEL + 1, 7, ICON_MARKER, shapeRow, section);
                }
                case OBJECTIVE, COMPLETE, GUIDE_ACTION -> {
                    int icon = row.kind() == QuestHudLayout.Kind.COMPLETE ? 2 : 1;
                    int glyph = row.kind() == QuestHudLayout.Kind.GUIDE_ACTION ? SOLID_PIXEL + 4 : SOLID_PIXEL + icon + 1;
                    appendGlyph(result, glyph, 7, ICON_MARKER, shapeRow, section | icon);
                    int barSpace = valueX - 3 - BAR_WIDTH - BAR_X;
                    int barShift = Math.clamp(barSpace / 2, 0, 7);
                    if (row.progressPercent() >= 0 && !value.isEmpty() && barSpace >= 0
                        && QUEST_TEXT.width(label) <= BAR_X + barShift * 2 - 3 - labelX) {
                        int fill = Math.clamp((row.progressPercent() * BAR_WIDTH + 50) / 100, 0, BAR_WIDTH);
                        int barVariant = barShift * 32 + fill;
                        if (fill < BAR_WIDTH) {
                            appendPixel(result, TRACK_MARKER, shapeRow, barVariant);
                        }
                        if (fill > 0) {
                            int marker = row.kind() == QuestHudLayout.Kind.COMPLETE ? COMPLETE_FILL_MARKER : FILL_MARKER;
                            appendPixel(result, marker, shapeRow, barVariant);
                        }
                    }
                }
                case OVERFLOW -> QUEST_TEXT.appendLine(result, List.of((int) '…'), MARKER_X, index, textMarker, 0);
                case GUIDE_CONTINUATION -> { }
            }
            int textRow = index | section;
            int shadowOffset = transparentTheme ? SHADOW_BIT : 0;
            QUEST_TEXT.appendLine(result, label, labelX, textRow, textMarker, shadowOffset);
            QUEST_TEXT.appendLine(result, value, valueX, textRow, textMarker, shadowOffset);
        }
        return result.build();
    }

    /** 章名・進捗・次の操作を実フォント幅で配置します。操作文の最終行は長すぎれば描画時に省略します。 */
    public static List<Row> guideRows(String chapterTitle, int completed, int total, int nextNumber, String nextAction) {
        if (completed < 0 || completed >= total || nextNumber < 1 || nextNumber > total || nextAction.isBlank()) return List.of();
        var rows = new ArrayList<Row>(5);
        String progress = completed + "/" + total;
        String title = "第1章 " + chapterTitle;
        int headerWidth = TEXT_RIGHT - LABEL_X - GUIDE_VALUE_GAP - QUEST_TEXT.width(QUEST_TEXT.fit(progress, VALUE_MAX_WIDTH));
        int end = QUEST_TEXT.prefixEnd(title, headerWidth);
        boolean singleTitle = end == title.length();
        rows.add(new Row(QuestHudLayout.Kind.GUIDE_TITLE, singleTitle ? title : "第1章", progress, -1));
        if (!singleTitle) rows.add(new Row(QuestHudLayout.Kind.GUIDE_CONTINUATION, chapterTitle, "", -1));
        rows.add(new Row(QuestHudLayout.Kind.GUIDE_ACTION, "次のステップ " + nextNumber, "", -1));
        String action = nextAction.strip();
        int breakAt = action.indexOf('\n');
        end = Math.min(breakAt < 0 ? action.length() : breakAt,
            QUEST_TEXT.prefixEnd(action, TEXT_RIGHT - LABEL_X - CONTINUATION_INDENT));
        rows.add(new Row(QuestHudLayout.Kind.GUIDE_CONTINUATION, action.substring(0, end), "", -1));
        if (end < action.length()) {
            rows.add(new Row(QuestHudLayout.Kind.GUIDE_CONTINUATION, action.substring(end).stripLeading().replace('\n', ' '), "", -1));
        }
        return List.copyOf(rows);
    }
}
