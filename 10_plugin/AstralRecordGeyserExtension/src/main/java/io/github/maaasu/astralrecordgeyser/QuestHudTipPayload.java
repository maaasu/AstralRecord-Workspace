package io.github.maaasu.astralrecordgeyser;

final class QuestHudTipPayload {
    private QuestHudTipPayload() {
    }

    /** JSON UIが表示行数に合う枠を選べるよう、表示用TIPに2桁の行数を加える。 */
    static String encode(String text) {
        if (text == null || text.isEmpty() || text.length() > 512 || text.indexOf('\r') >= 0) return null;
        if (" ".equals(text)) return text;
        long rows = 1 + text.chars().filter(character -> character == '\n').count();
        if (rows > 10) return null;
        return (rows < 10 ? "0" : "") + rows + "|" + text;
    }
}
