package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class QuestHudDeliveryStateTest {
    @Test
    void retainsLastDisplayAndRetriesFailedClearUntilSuccessful() {
        long shownAt = 100L;
        Long state = AstralRecordProxyPlugin.questHudAfterSend(null, false, true, shownAt);
        assertEquals(Long.valueOf(shownAt), state);
        state = AstralRecordProxyPlugin.questHudAfterSend(state, false, false, 200L);
        assertEquals(Long.valueOf(shownAt), state);
        assertTrue(AstralRecordProxyPlugin.questHudClearDue(state, shownAt + TimeUnit.SECONDS.toNanos(5)));
        state = AstralRecordProxyPlugin.questHudAfterSend(state, true, false, 300L);
        assertTrue(AstralRecordProxyPlugin.questHudClearDue(state, 300L));
        state = AstralRecordProxyPlugin.questHudAfterSend(state, true, false, 400L);
        assertTrue(AstralRecordProxyPlugin.questHudClearDue(state, 400L));
        assertNull(AstralRecordProxyPlugin.questHudAfterSend(state, true, true, 500L));
    }

    @Test
    void newSuccessfulDisplaySupersedesClearPendingAndFailedFirstShowCreatesNoLatch() {
        assertNull(AstralRecordProxyPlugin.questHudAfterSend(null, false, false, 100L));
        Long state = AstralRecordProxyPlugin.questHudAfterSend(100L, true, false, 200L);
        long refreshedAt = 300L;
        state = AstralRecordProxyPlugin.questHudAfterSend(state, false, true, refreshedAt);
        assertEquals(Long.valueOf(refreshedAt), state);
        assertFalse(AstralRecordProxyPlugin.questHudClearDue(state, refreshedAt + TimeUnit.SECONDS.toNanos(4)));
        assertTrue(AstralRecordProxyPlugin.questHudClearDue(state, refreshedAt + TimeUnit.SECONDS.toNanos(5)));
    }
}
