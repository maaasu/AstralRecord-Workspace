package io.github.maaasu.astralrecordproxy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyChannelBoostsTest {
    @Test
    void displaysRegisteredChannelsAndExpiresBoosts() {
        var state = JsonParser.parseString("""
            {"channels":[
              {"channelId":"ch2","exp":null,"drop":null},
              {"channelId":"ch1","exp":{"multiplier":1.5,"expiresAt":"2026-01-01T00:30:00Z"},
               "drop":{"multiplier":1.2,"expiresAt":"2025-12-31T23:59:59Z"}}
            ]}
            """).getAsJsonObject();

        assertEquals(List.of("ch1 EXP 1.5倍 残り30分 / DROP 通常",
            "ch2 EXP 通常 / DROP 通常"),
            ProxyChannelBoosts.lines(state, Instant.parse("2026-01-01T00:00:00Z")));
    }
}
