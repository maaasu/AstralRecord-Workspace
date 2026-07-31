package io.github.maaasu.astralRecord.feature.status.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShieldRechargeStateTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/07_1-モデル定義.md
     * 章・見出し: # 07_1-モデル定義 > ## 6. Shield リチャージ状態
     * 検証契約: now注入で残り時間・左から右へ増えるprogress・延長後の後退を決定的に計算する。
     */
    @Test
    void calculatesRemainingProgressAndExtensionWithoutWallClock() {
        ShieldRechargeState state = new ShieldRechargeState(1_000L, 11_000L, 50.0D);

        assertEquals(5_000L, state.remainingMs(6_000L));
        assertEquals(0.5D, state.progress(6_000L), 0.0001D);

        ShieldRechargeState extended = state.extendedBy(10_000L);
        assertEquals(15_000L, extended.remainingMs(6_000L));
        assertEquals(0.25D, extended.progress(6_000L), 0.0001D);
        assertEquals(50.0D, extended.rechargeAmount(), 0.0001D);
    }
}
