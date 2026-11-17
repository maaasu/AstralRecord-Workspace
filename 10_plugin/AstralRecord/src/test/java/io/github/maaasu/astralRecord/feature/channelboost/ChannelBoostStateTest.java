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
     * API絶対期限を過ぎた効果だけ1倍へ戻し、EXPとDROPは独立させる。
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

    @Test
    void onlyExplicitNetworkFlagEnablesNetworkBoostWithoutChangingLocalEffects() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var state = ChannelBoostRepository.parse(JsonParser.parseString("""
            {"eventCursor":4,"channels":[
              {"channelId":"ch1","displayName":"Channel 1","networkBoostEnabled":false,
               "exp":{"multiplier":1.5,"expiresAt":"2099-01-01T00:00:00Z"},"drop":null},
              {"channelId":"ch2","displayName":"Channel 2",
               "exp":{"multiplier":1.5,"expiresAt":"2099-01-01T00:00:00Z"},"drop":null},
              {"channelId":"ch3","displayName":"Channel 3","networkBoostEnabled":true,
               "exp":{"multiplier":1.5,"expiresAt":"2099-01-01T00:00:00Z"},"drop":null}
            ]}
            """).getAsJsonObject());

        assertEquals(3, state.channels().size());
        assertFalse(state.current("ch1").networkBoostEnabled());
        assertFalse(state.current("ch2").networkBoostEnabled());
        assertTrue(state.current("ch3").networkBoostEnabled());
        for (var channel : state.channels().values()) {
            assertEquals(1.5, channel.expFactorAt(now), channel.channelId());
        }
    }
}
