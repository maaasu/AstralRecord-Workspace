package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout;
import io.github.maaasu.astralRecord.feature.hud.model.QuestHudLayout.Row;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

import java.util.List;

import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.SOLID_PIXEL;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.appendGlyph;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.appendLine;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.appendPixel;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.fit;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.width;

/** 専用パックで画面左上へ移す、送り幅ゼロのクエスト字形を組み立てます。 */
public final class QuestHudGlyphs {
    private static final int LEFT = 8;
    private static final int MARKER_X = LEFT + 5;
    private static final int LABEL_X = LEFT + 18;
    private static final int TEXT_RIGHT = LEFT + QuestHudLayout.WIDTH - 5;
    private static final int VALUE_GAP = 5;
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
                case TITLE -> 0;
                case OBJECTIVE -> 1;
                case COMPLETE -> 2;
                case OVERFLOW -> 3;
            };
            int textMarker = TEXT_MARKER + (rowCount - 1) * 4 + palette;
            int shapeRow = rowCount * 16 + index;
            List<Integer> value = fit(row.value(), VALUE_MAX_WIDTH);
            int valueX = TEXT_RIGHT - width(value);
            int labelRight = value.isEmpty() ? TEXT_RIGHT : valueX - VALUE_GAP;
            List<Integer> label = fit(row.label(), labelRight - LABEL_X);
            switch (row.kind()) {
                case TITLE -> {
                    if (index > 0) {
                        appendPixel(result, DIVIDER_MARKER, shapeRow, 0);
                    }
                    appendGlyph(result, SOLID_PIXEL + 1, 7, ICON_MARKER, shapeRow, 0);
                }
                case OBJECTIVE, COMPLETE -> {
                    int icon = row.kind() == QuestHudLayout.Kind.COMPLETE ? 2 : 1;
                    appendGlyph(result, SOLID_PIXEL + icon + 1, 7, ICON_MARKER, shapeRow, icon);
                    int barSpace = valueX - 3 - BAR_WIDTH - BAR_X;
                    int barShift = Math.clamp(barSpace / 2, 0, 7);
                    if (row.progressPercent() >= 0 && !value.isEmpty() && barSpace >= 0
                        && width(label) <= BAR_X + barShift * 2 - 3 - LABEL_X) {
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
                case OVERFLOW -> appendLine(result, List.of((int) '…'), MARKER_X, index, textMarker);
            }
            appendLine(result, label, LABEL_X, index, textMarker);
            appendLine(result, value, valueX, index, textMarker);
        }
        return result.build();
    }
}
