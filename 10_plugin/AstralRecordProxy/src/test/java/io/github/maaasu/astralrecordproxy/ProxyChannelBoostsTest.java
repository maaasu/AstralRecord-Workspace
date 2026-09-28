package io.github.maaasu.astralrecordproxy;

import com.google.gson.JsonParser;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyChannelBoostsTest {
    @Test
    void tabIncludesOnlyActiveBoostsOnEnabledChannelsWithoutDroppingServerInfoState() {
        var state = JsonParser.parseString("""
            {"channels":[
              {"channelId":"ch2","displayName":"第二","networkBoostEnabled":false,"exp":{"multiplier":2,"expiresAt":"2026-01-01T00:10:00Z"},"drop":null},
              {"channelId":"ch3","displayName":"第三","exp":null,"drop":null},
              {"channelId":"ch1","displayName":"第一","networkBoostEnabled":true,"exp":null,"drop":null},
              {"channelId":"ch4","displayName":"第四","networkBoostEnabled":true,"exp":{"multiplier":2,"expiresAt":"2025-12-31T23:59:00Z"},"drop":{"multiplier":1.5,"expiresAt":"2026-01-01T00:10:00Z"}},
              {"channelId":"ch5","displayName":"第五","networkBoostEnabled":true,"exp":{"multiplier":2,"expiresAt":"2026-01-01T00:10:00Z"},"drop":{"multiplier":3,"expiresAt":"2026-01-01T00:05:00Z"}}
            ]}
            """).getAsJsonObject();

        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var rows = ProxyChannelBoosts.tabRows(state, now);
        var plain = PlainTextComponentSerializer.plainText();
        assertEquals(2, rows.size());
        assertEquals("第四  ›  DROP ×1.5  残り10分", plain.serialize(rows.get(0)));
        assertEquals("第五  ›  EXP ×2  残り10分   ·   DROP ×3  残り5分", plain.serialize(rows.get(1)));
        assertEquals(5, ProxyChannelBoosts.lines(state, now).size());
        assertTrue(ProxyChannelBoosts.tabRows(null, now).isEmpty());
    }
}
