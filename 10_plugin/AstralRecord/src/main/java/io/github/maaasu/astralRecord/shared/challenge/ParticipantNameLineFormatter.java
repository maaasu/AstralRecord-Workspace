package io.github.maaasu.astralRecord.shared.challenge;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** ボス／ダンジョンのサイドバーで参加者名を一定幅ごとにまとめます。 */
public final class ParticipantNameLineFormatter {
    /** 参加者名を1行へ詰める最大文字数です。 */
    public static final int MAX_NAME_CHARACTERS_PER_LINE = 20;
    /** 15行サイドバーで、ボス／ダンジョン名の下へ表示できる参加者行数です。 */
    public static final int MAX_SIDEBAR_PARTICIPANT_LINES = 5;

    private ParticipantNameLineFormatter() {
    }

    /**
     * 参加者名を名前の途中で分割せず、一定文字数ごとの行へ分割します。
     *
     * @param participantNames 参加者名
     * @return 1行ごとの参加者名。参加者が空の場合も空行を1件返す
     */
    public static @NotNull List<List<String>> wrap(@NotNull List<String> participantNames) {
        return wrap(participantNames, Integer.MAX_VALUE);
    }

    /**
     * 参加者名を名前単位で折り返し、指定行数を超える場合は省略人数を最終行へ示します。
     *
     * @param participantNames 参加者名
     * @param maxLines 表示可能な最大行数
     * @return 1行ごとの参加者名
     */
    public static @NotNull List<List<String>> wrap(
            @NotNull List<String> participantNames,
            int maxLines
    ) {
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        List<List<String>> lines = new ArrayList<>();
        List<String> currentLine = new ArrayList<>();
        int currentLength = 0;
        for (String participantName : participantNames) {
            int nameLength = participantName.codePointCount(0, participantName.length());
            int separatorLength = currentLine.isEmpty() ? 0 : 1;
            if (!currentLine.isEmpty()
                    && currentLength + separatorLength + nameLength > MAX_NAME_CHARACTERS_PER_LINE) {
                lines.add(List.copyOf(currentLine));
                currentLine = new ArrayList<>();
                currentLength = 0;
                separatorLength = 0;
            }
            currentLine.add(participantName);
            currentLength += separatorLength + nameLength;
        }
        if (!currentLine.isEmpty()) {
            lines.add(List.copyOf(currentLine));
        }
        if (lines.isEmpty()) {
            lines.add(List.of());
        }
        if (lines.size() <= maxLines) {
            return List.copyOf(lines);
        }

        List<List<String>> limitedLines = new ArrayList<>(lines.subList(0, maxLines - 1));
        int visibleParticipantCount = limitedLines.stream().mapToInt(List::size).sum();
        limitedLines.add(List.of("…ほか" + (participantNames.size() - visibleParticipantCount) + "人"));
        return List.copyOf(limitedLines);
    }

    /**
     * 参加者名を表示するために必要な行数を返します。
     *
     * @param participantNames 参加者名
     * @return 参加者名の表示行数
     */
    public static int lineCount(@NotNull List<String> participantNames) {
        return wrap(participantNames).size();
    }

    /**
     * 15行サイドバー向けに省略を適用した参加者名の表示行数を返します。
     *
     * @param participantNames 参加者名
     * @return 参加者名の表示行数
     */
    public static int sidebarLineCount(@NotNull List<String> participantNames) {
        return wrap(participantNames, MAX_SIDEBAR_PARTICIPANT_LINES).size();
    }
}
