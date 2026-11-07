package io.github.maaasu.astralrecordgeyser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LatchedTipDeliveryTest {
    @Test
    void retriesFailedClearAfterVerificationChangesUntilDeliverySucceeds() {
        LatchedTipDelivery delivery = new LatchedTipDelivery();
        Object connection = new Object();
        assertFalse(delivery.send(connection, false, true, () -> false));
        assertFalse(delivery.send(connection, true, false, () -> true));
        assertTrue(delivery.send(connection, false, true, () -> true));
        // 設定変更後の新表示は拒否するが、以前表示した同じ接続の消去は再試行する。
        assertFalse(delivery.send(connection, false, false, () -> true));
        assertFalse(delivery.send(connection, true, false, () -> false));
        assertTrue(delivery.send(connection, true, false, () -> true));
        assertFalse(delivery.send(connection, true, false, () -> true));
    }

    @Test
    void neverTransfersClearPermissionToAnotherConnectionOrPastDisconnect() {
        LatchedTipDelivery delivery = new LatchedTipDelivery();
        Object first = new String("same-account");
        Object reconnected = new String("same-account");
        AtomicInteger sends = new AtomicInteger();
        assertTrue(delivery.send(first, false, true, () -> true));
        assertFalse(delivery.send(reconnected, true, false, () -> { sends.incrementAndGet(); return true; }));
        assertEquals(0, sends.get());
        delivery.forget(first);
        assertFalse(delivery.send(first, true, false, () -> true));
        assertTrue(delivery.send(reconnected, false, true, () -> true));
        delivery.clear();
        assertFalse(delivery.send(reconnected, true, false, () -> true));
    }
}
