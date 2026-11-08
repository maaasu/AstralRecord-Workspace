package io.github.maaasu.astralrecordgeyser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class QuestHudTipPayloadTest {
    @Test
    void prefixesFormattedRowsWithoutChangingClear() {
        String title = "§6薬草採集";
        String objective = "§f月見草を採集 あと2個";
        assertEquals(" ", QuestHudTipPayload.encode(" "));
        assertEquals("01|" + title, QuestHudTipPayload.encode(title));
        assertEquals("02|" + title + "\n" + objective, QuestHudTipPayload.encode(title + "\n" + objective));
        String tenRows = (objective + "\n").repeat(9) + objective;
        assertEquals("10|" + tenRows, QuestHudTipPayload.encode(tenRows));
        assertNull(QuestHudTipPayload.encode(tenRows + "\n" + objective));
    }

    @Test
    void checksTextLengthBeforeAddingTheFrameSelector() {
        String text = "月".repeat(512);
        assertEquals("01|" + text, QuestHudTipPayload.encode(text));
        assertNull(QuestHudTipPayload.encode(text + "月"));
        assertNull(QuestHudTipPayload.encode(""));
        assertNull(QuestHudTipPayload.encode("a\r\nb"));
    }
}
