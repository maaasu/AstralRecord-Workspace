package io.github.maaasu.astralRecord.feature.hud.model;

import io.github.maaasu.astralRecord.feature.hud.HudMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** 両版のクエストHUDで共有する表示枠と行です。 */
public final class QuestHudLayout {
    public static final int WIDTH = 172;
    public static final int ROW_HEIGHT = 13;
    public static final int PADDING = 3;
    public static final int MAX_ROWS = 10;
    private static final int MAX_QUESTS = 3;
    private static final int BEDROCK_TEXT_WIDTH = WIDTH - 12;

    public enum Kind {
        TITLE, OBJECTIVE, COMPLETE, OVERFLOW, GUIDE_TITLE, GUIDE_ACTION, GUIDE_CONTINUATION
    }

    public record Row(Kind kind, String label, String value, int progressPercent) {
    }

    private QuestHudLayout() {
    }

    /**
     * 受注順の依頼を枠内に配置します。後続の依頼がある場合は省略行を確保します。
     * @param quests 表示用の受注中クエスト
     * @return 最大3件・10行の表示行
     */
    public static List<Row> rows(List<QuestService.HudQuest> quests) {
        return rows(quests, MAX_ROWS);
    }

    /** ガイドと同時表示するときも、省略するクエストや目標の件数を残します。 */
    public static List<Row> rows(List<QuestService.HudQuest> quests, int rowBudget) {
        if (rowBudget < 3 || rowBudget > MAX_ROWS) {
            throw new IllegalArgumentException("クエストHUDの行数が範囲外です: " + rowBudget);
        }
        List<Row> rows = new ArrayList<>(rowBudget);
        int displayed = 0;
        while (displayed < Math.min(MAX_QUESTS, quests.size())) {
            QuestService.HudQuest quest = quests.get(displayed);
            List<QuestService.HudObjective> objectives = quest.objectives();
            int available = rowBudget - rows.size() - (displayed + 1 < quests.size() ? 1 : 0);
            int shown = objectives.size();
            if (shown + 1 > available) {
                if (displayed > 0) break;
                shown = available - 2;
            }
            String status = quest.status();
            if (status == null) {
                long completed = objectives.stream().filter(objective -> objective.current() >= objective.required()).count();
                status = objectives.size() > 1 ? completed + "/" + objectives.size() : "";
            }
            rows.add(new Row(Kind.TITLE, quest.title(), status, -1));
            for (QuestService.HudObjective objective : objectives.subList(0, shown)) {
                int percent = (int) (100L * objective.current() / objective.required());
                Kind kind = percent >= 100 ? Kind.COMPLETE : Kind.OBJECTIVE;
                rows.add(new Row(kind, objective.label(), objective.current() + "/" + objective.required(), percent));
            }
            if (shown < objectives.size()) {
                rows.add(overflow(HudMsgId.P_5662, objectives.size() - shown));
            }
            displayed++;
        }
        if (displayed < quests.size()) {
            rows.add(overflow(HudMsgId.P_5661, quests.size() - displayed));
        }
        return List.copyOf(rows);
    }

    private static Row overflow(HudMsgId message, int hidden) {
        return new Row(Kind.OVERFLOW, PlayerMsgResource.format(message.getId(), hidden), "", -1);
    }

    /**
     * 統合版のネイティブラベルに送る行を組み立てます。進捗値を優先して行の幅を制限します。
     * @param rows 共通の表示行
     * @return 専用TIP本文。空の表示は半角空白で解除します
     */
    public static String bedrockText(List<Row> rows) {
        if (rows.isEmpty()) return " ";
        StringJoiner result = new StringJoiner("\n");
        for (Row row : rows) {
            String prefix = switch (row.kind()) {
                case TITLE -> "§6";
                case OBJECTIVE -> "§f◇ ";
                case COMPLETE -> "§a◆ ";
                case OVERFLOW -> "§7";
                case GUIDE_TITLE, GUIDE_ACTION, GUIDE_CONTINUATION ->
                    throw new IllegalArgumentException("ガイド行はJava版専用です");
            };
            String suffix = row.value().isEmpty() ? "" : "  " + row.value();
            int available = BEDROCK_TEXT_WIDTH - textWidth(prefix.substring(2)) - textWidth(suffix);
            String label = fit(row.label(), available);
            result.add(prefix + label + (label.isEmpty() ? suffix.stripLeading() : suffix));
        }
        return result.toString();
    }

    private static String fit(String text, int available) {
        if (textWidth(text) <= available) return text;
        int dots = textWidth("..");
        if (available < dots) return "";
        int limit = available - dots;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            limit -= glyphWidth(codePoint);
            if (limit < 0) break;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end).stripTrailing() + "..";
    }

    private static int textWidth(String text) {
        return text.codePoints().map(QuestHudLayout::glyphWidth).sum();
    }

    private static int glyphWidth(int codePoint) {
        if (codePoint == ' ') return 4;
        return codePoint < 128 ? 7 : 10;
    }
}
