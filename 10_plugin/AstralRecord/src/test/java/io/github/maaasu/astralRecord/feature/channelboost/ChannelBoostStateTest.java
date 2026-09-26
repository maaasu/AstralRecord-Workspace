package io.github.maaasu.astralRecord.feature.channelboost;

import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelBoostStateTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/36-paid-benefits/36_0-概要.md
     * 章・見出し: # 36_0-概要 > ## 責務
     * 検証契約: API絶対期限を過ぎた効果だけ1倍へ戻し、EXPとDROPは独立させる。
     */
    @Test
    void expiryReturnsOnlyExpiredFactorToOne() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var channel = new ChannelBoostState.Channel("ch1", "第一", false,
            new ChannelBoostState.Boost(1.5, now.minusSeconds(1), "a"),
            new ChannelBoostState.Boost(1.3, now.plusSeconds(10), "b"));
        var state = new ChannelBoostState(Map.of("ch1", channel), 8);

        assertEquals(1.0, state.current("CH1").expFactorAt(now));
        assertEquals(1.3, state.current("ch1").dropFactorAt(now));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/36-paid-benefits/36_0-概要.md
     * 章・見出し: # 36_0-概要 > ## 責務
     * 検証契約: ネットワーク発動対象外や旧応答でも全チャンネルのゲーム内効果を保持する。
     */
    @Test
    void retainsLocalBoostWhenNetworkFlagIsFalseOrMissing() {
        var state = ChannelBoostRepository.parse(JsonParser.parseString("""
            {"eventCursor":4,"channels":[
              {"channelId":"ch1","displayName":"第一","networkBoostEnabled":false,
               "exp":{"multiplier":1.5,"expiresAt":"2099-01-01T00:00:00Z"},"drop":null},
              {"channelId":"ch2","displayName":"第二","exp":null,"drop":null}
            ]}
            """).getAsJsonObject());

        assertFalse(state.current("ch1").networkBoostEnabled());
        assertFalse(state.current("ch2").networkBoostEnabled());
        assertEquals(1.5, state.current("ch1").expFactorAt(Instant.parse("2026-01-01T00:00:00Z")));
        assertEquals(2, state.channels().size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/36-paid-benefits/36_0-概要.md
     * 章・見出し: # 36_0-概要 > ## 責務
     * 検証契約: ネットワーク発動対象の明示的なtrueだけTABの対象判定へ渡す。
     */
    @Test
    void parsesExplicitNetworkBoostFlag() {
        var state = ChannelBoostRepository.parse(JsonParser.parseString("""
            {"eventCursor":0,"channels":[{"channelId":"ch1","displayName":"第一",
             "networkBoostEnabled":true,"exp":null,"drop":null}]}
            """).getAsJsonObject());

        assertTrue(state.current("ch1").networkBoostEnabled());
        assertEquals("第一", state.current("ch1").displayName());
    }
}
