package io.github.maaasu.astralrecordproxy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyChannelBoostsTest {
    @Test
    void tabIncludesOnlyExplicitlyEnabledChannelsWithoutDroppingServerInfoState() {
        var state = JsonParser.parseString("""
            {"channels":[
              {"channelId":"ch2","displayName":"第二","networkBoostEnabled":false,"exp":null,"drop":null},
              {"channelId":"ch3","displayName":"第三","exp":null,"drop":null},
              {"channelId":"ch1","displayName":"第一","networkBoostEnabled":true,"exp":null,"drop":null}
            ]}
            """).getAsJsonObject();

        assertEquals(1, ProxyChannelBoosts.tabRows(state, Instant.parse("2026-01-01T00:00:00Z")).size());
        assertEquals(3, ProxyChannelBoosts.lines(state, Instant.parse("2026-01-01T00:00:00Z")).size());
        assertTrue(ProxyChannelBoosts.tabRows(null, Instant.parse("2026-01-01T00:00:00Z")).isEmpty());
    }
}
