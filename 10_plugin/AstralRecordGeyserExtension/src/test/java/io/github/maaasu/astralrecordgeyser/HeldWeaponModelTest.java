package io.github.maaasu.astralrecordgeyser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HeldWeaponModelTest {
    private static final long TAG = 0x4152574D4F44454CL;

    @Test
    void decodesModelAndResetWhileRejectingOtherSpawnUuids() {
        assertEquals(new HeldWeaponModel(0), HeldWeaponModel.decode(new UUID(TAG, Long.MIN_VALUE)));
        assertEquals(new HeldWeaponModel(21001), HeldWeaponModel.decode(new UUID(TAG, Long.MIN_VALUE | 21001)));
        assertEquals(new HeldWeaponModel(0x00ff_ffff), HeldWeaponModel.decode(new UUID(TAG, 0x8000_0000_00ff_ffffL)));
        assertNull(HeldWeaponModel.decode(new UUID(TAG, 21001)));
        assertNull(HeldWeaponModel.decode(new UUID(TAG, 0x8000_0000_0100_0000L)));
        assertNull(HeldWeaponModel.decode(new UUID(TAG ^ 1, Long.MIN_VALUE | 21001)));
        assertNull(HeldWeaponModel.decode(null));
    }

    @Test
    void heartbeatPreventsAnExpiryQueuedBeforeTheRenewal() {
        var lease = new HeldWeaponModelDisplay.Lease();
        var applied = new ArrayList<Integer>();
        long deadline = Duration.ofSeconds(8).toNanos();

        lease.renew(21001, 0);
        lease.apply(applied::add);
        assertTrue(lease.expired(deadline));

        lease.renew(21001, deadline);
        lease.expire(deadline + 1, applied::add);
        assertEquals(List.of(21001), applied);

        lease.expire(deadline * 2, applied::add);
        assertEquals(List.of(21001, 0), applied);
    }

    @Test
    void resetOrNewModelWinsOverQueuedUpdates() {
        var lease = new HeldWeaponModelDisplay.Lease();
        var applied = new ArrayList<Integer>();

        lease.renew(21001, 0);
        lease.renew(21002, 1);
        lease.apply(applied::add);
        lease.apply(applied::add);
        lease.renew(0, 2);
        lease.apply(applied::add);

        assertEquals(List.of(21002, 21002, 0), applied);
    }

    @Test
    void reappliesTheSameModelAfterAnExternalReset() {
        var lease = new HeldWeaponModelDisplay.Lease();
        var applied = new ArrayList<Integer>();
        lease.renew(21001, 0);
        lease.apply(applied::add);
        applied.add(0);
        lease.renew(21001, 1);
        lease.apply(applied::add);
        assertEquals(List.of(21001, 0, 21001), applied);
    }
}
