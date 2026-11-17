package io.github.maaasu.astralRecord.feature.player.afk.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AfkActivityStateTest {

    private static final World WORLD = mock(World.class);

    /**
     * 方向入力中の移動は、基準位置から1m以上の場合だけ操作時刻を更新する。
     */
    @Test
    void updatesInactivityAtOneBlockOfDirectionalMovement() {
        assertAll(
            () -> assertDirectionalMovement(0.99D, false),
            () -> assertDirectionalMovement(1.0D, true)
        );
    }

    /**
     * 前後左右入力を伴わない水流・スキル・転送相当の移動は、距離にかかわらずAFK判定用の操作時刻を更新しない。
     */
    @Test
    void doesNotResetInactivityForMovementWithoutDirectionalInput() {
        AfkActivityState state = new AfkActivityState(location(0.0D), 0L);

        assertFalse(state.recordMovement(location(10.0D), false, 120_000L));
        assertFalse(state.recordMovement(location(20.0D), true, 180_000L));
        assertTrue(state.isInactiveFor(300_000L, 300_000L));
    }

    private static void assertDirectionalMovement(double distance, boolean resetsInactivity) {
        AfkActivityState state = new AfkActivityState(location(0.0D), 0L);
        state.setDirectionalInput(true);

        assertEquals(resetsInactivity, state.recordMovement(location(distance), false, 120_000L),
            () -> "移動距離: " + distance);
        assertEquals(!resetsInactivity, state.isInactiveFor(300_000L, 300_000L),
            () -> "移動距離: " + distance);
    }

    private static Location location(double x) {
        return new Location(WORLD, x, 64.0D, 0.0D);
    }
}
