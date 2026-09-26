package io.github.maaasu.astralRecord.feature.channelboost;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelBoostStateTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/36-paid-benefits/36_0-概要.md
     * 章・見出し: # 36_0-概要 > ## 責務
     * 検証契約: API絶対期限を過ぎた効果だけ1倍へ戻し、EXPとDROPは独立させる。
     */
    @Test
    void expiryReturnsOnlyExpiredFactorToOne() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var channel = new ChannelBoostState.Channel("ch1",
            new ChannelBoostState.Boost(1.5, now.minusSeconds(1), "a"),
            new ChannelBoostState.Boost(1.3, now.plusSeconds(10), "b"));
        var state = new ChannelBoostState(Map.of("ch1", channel), 8);

        assertEquals(1.0, state.current("CH1").expFactorAt(now));
        assertEquals(1.3, state.current("ch1").dropFactorAt(now));
    }
}
