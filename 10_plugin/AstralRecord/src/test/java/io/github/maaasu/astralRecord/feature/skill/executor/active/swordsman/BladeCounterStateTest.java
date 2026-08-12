package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BladeCounterStateTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 1回の10tick受付中は残回数まで連続攻撃へ反撃し、試行ごとに回数を消費する。
     */
    @Test
    void oneReceptionConsumesEveryCounterAttemptUntilExhausted() {
        BladeCounterState state = new BladeCounterState(3, 400L);
        state.openReception(20L, 10L);

        assertTrue(state.consumeCounter(20L));
        assertTrue(state.consumeCounter(25L));
        assertTrue(state.consumeCounter(29L));
        assertFalse(state.consumeCounter(29L));
        assertEquals(0, state.remainingCounters());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 受付時間切れだけでは残回数を消費せず、次の通常攻撃で再受付できる。
     */
    @Test
    void expiredReceptionKeepsCounterForNextNormalAttack() {
        BladeCounterState state = new BladeCounterState(3, 400L);
        state.openReception(20L, 10L);

        assertFalse(state.consumeCounter(30L));
        assertEquals(3, state.remainingCounters());

        state.openReception(40L, 10L);
        assertTrue(state.consumeCounter(40L));
        assertEquals(2, state.remainingCounters());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 400tickのバフ期限を迎えると受付中でも反撃しない。
     */
    @Test
    void buffExpirationEndsReception() {
        BladeCounterState state = new BladeCounterState(3, 400L);
        state.openReception(395L, 10L);

        assertTrue(state.consumeCounter(399L));
        assertFalse(state.consumeCounter(400L));
    }
}
