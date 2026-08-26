package io.github.maaasu.astralRecord.feature.player.afk.model;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AfkActivityStateTest {

    private static final World WORLD = mock(World.class);

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 10. AFK判定状態
     * 検証契約: 前後左右入力中でも基準位置から1m未満の移動だけでは、AFK判定用の操作時刻を更新しない。
     */
    @Test
    void doesNotResetInactivityForDirectionalMovementBelowOneBlock() {
        AfkActivityState state = new AfkActivityState(location(0.0D), 0L);
        state.setDirectionalInput(true);

        assertFalse(state.recordMovement(location(0.99D), false, 120_000L));
        assertTrue(state.isInactiveFor(300_000L, 300_000L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 10. AFK判定状態
     * 検証契約: 前後左右入力中に基準位置から1m以上移動すると、AFK判定用の操作時刻を更新する。
     */
    @Test
    void resetsInactivityForDirectionalMovementOfOneBlockOrMore() {
        AfkActivityState state = new AfkActivityState(location(0.0D), 0L);
        state.setDirectionalInput(true);

        assertTrue(state.recordMovement(location(1.0D), false, 120_000L));
        assertFalse(state.isInactiveFor(300_000L, 300_000L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 10. AFK判定状態
     * 検証契約: 前後左右入力を伴わない水流・スキル・転送相当の移動は、距離にかかわらずAFK判定用の操作時刻を更新しない。
     */
    @Test
    void doesNotResetInactivityForMovementWithoutDirectionalInput() {
        AfkActivityState state = new AfkActivityState(location(0.0D), 0L);

        assertFalse(state.recordMovement(location(10.0D), false, 120_000L));
        assertFalse(state.recordMovement(location(20.0D), true, 180_000L));
        assertTrue(state.isInactiveFor(300_000L, 300_000L));
    }

    private static Location location(double x) {
        return new Location(WORLD, x, 64.0D, 0.0D);
    }
}
