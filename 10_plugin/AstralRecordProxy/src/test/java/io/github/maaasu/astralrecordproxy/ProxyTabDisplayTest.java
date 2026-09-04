package io.github.maaasu.astralrecordproxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxyTabDisplayTest {
    @Test
    void rendersBrandCurrentBackendMsptPingAndTotalPlayers() {
        ProxyTabDisplay.HeaderFooter display = ProxyTabDisplay.render(48L, 12.34D, 83);

        assertEquals(Component.text()
            .append(Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text("MSPT ", NamedTextColor.GRAY))
            .append(Component.text("12.3", NamedTextColor.GREEN))
            .build(), display.header());
        assertEquals(Component.text()
            .append(Component.text("通信遅延 ", NamedTextColor.GRAY))
            .append(Component.text("48ms", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("総参加人数 ", NamedTextColor.GRAY))
            .append(Component.text("83人", NamedTextColor.AQUA))
            .build(), display.footer());
    }

    @Test
    void showsMeasuringUntilBackendMetricsArrive() {
        ProxyTabDisplay.HeaderFooter display = ProxyTabDisplay.render(-1L, null, -1);

        assertEquals(Component.text()
            .append(Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text("MSPT ", NamedTextColor.GRAY))
            .append(Component.text("計測中", NamedTextColor.GRAY))
            .build(), display.header());
        assertEquals(Component.text()
            .append(Component.text("通信遅延 ", NamedTextColor.GRAY))
            .append(Component.text("0ms", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("総参加人数 ", NamedTextColor.GRAY))
            .append(Component.text("0人", NamedTextColor.AQUA))
            .build(), display.footer());
    }

    @Test
    void expiresBackendMsptAfterFifteenSeconds() {
        long receivedAt = 1_000L;
        AstralRecordProxyPlugin.ServerMetric metric =
            new AstralRecordProxyPlugin.ServerMetric(18.5D, receivedAt);

        assertEquals(18.5D, AstralRecordProxyPlugin.resolveServerMspt(
            metric, receivedAt + AstralRecordProxyPlugin.SERVER_METRICS_TTL_NANOS));
        assertNull(AstralRecordProxyPlugin.resolveServerMspt(
            metric, receivedAt + AstralRecordProxyPlugin.SERVER_METRICS_TTL_NANOS + 1L));
        assertNull(AstralRecordProxyPlugin.resolveServerMspt(null, receivedAt));
    }
}
