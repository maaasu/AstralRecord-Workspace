package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BladeCounterStateTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 通常攻撃の試行ごとに受付枠を1回先に消費し、1枠の受付は最初の直接hitだけを処理する。
     */
    @Test
    void normalAttackConsumesOneCounterBeforeReception() {
        BladeCounterState state = new BladeCounterState(3, 400L);
        assertTrue(state.consumeCounter(20L));
        assertEquals(2, state.remainingCounters());
        state.openReception(20L, 10L);

        assertTrue(state.consumeReception(20L));
        assertFalse(state.consumeReception(25L));
        assertEquals(2, state.remainingCounters());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 受付時間切れだけでは残回数を消費せず、次の通常攻撃で残数を1回消費して再受付できる。
     */
    @Test
    void expiredReceptionKeepsCounterForNextNormalAttack() {
        BladeCounterState state = new BladeCounterState(3, 400L);
        assertTrue(state.consumeCounter(20L));
        state.openReception(20L, 10L);

        assertFalse(state.consumeReception(30L));
        assertEquals(2, state.remainingCounters());

        assertTrue(state.consumeCounter(40L));
        state.openReception(40L, 10L);
        assertTrue(state.consumeReception(40L));
        assertEquals(1, state.remainingCounters());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 400tickのバフ期限を迎えると受付中でも反撃しない。
     */
    @Test
    void buffExpirationEndsReception() {
        BladeCounterState state = new BladeCounterState(3, 400L);
        assertTrue(state.consumeCounter(395L));
        state.openReception(395L, 10L);

        assertTrue(state.consumeReception(399L));
        assertFalse(state.consumeReception(400L));
        assertEquals(2, state.remainingCounters());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. ソードマン・ブレードカウンターの実装契約
     * 検証契約: 最後の通常攻撃試行で残数が0になった時点で、次の受付を作れない。
     */
    @Test
    void lastNormalAttackExhaustsCounterAvailability() {
        BladeCounterState state = new BladeCounterState(1, 400L);

        assertTrue(state.consumeCounter(20L));
        assertEquals(0, state.remainingCounters());
        assertFalse(state.isActive(20L));
        state.openReception(20L, 10L);
        assertFalse(state.consumeReception(20L));
    }
}
