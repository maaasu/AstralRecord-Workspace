package io.github.maaasu.astralrecordproxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/** Proxy共通Tabのヘッダーとフッターを生成する。 */
final class ProxyTabDisplay {
    private ProxyTabDisplay() {
    }

    /**
     * 現在backendのMSPT、閲覧者の通信遅延、Proxy全体人数を表示する。
     *
     * @param ping 閲覧者の通信遅延（ミリ秒）
     * @param mspt 現在backendのMSPT。未受信の場合はnull
     * @param totalPlayers Proxy全体の参加人数
     * @return Tabへ送信するヘッダーとフッター
     */
    static HeaderFooter render(long ping, Double mspt, int totalPlayers) {
        Component msptValue = mspt == null
            ? Component.text("計測中", NamedTextColor.GRAY)
            : Component.text(String.format(Locale.ROOT, "%.1f", mspt), msptColor(mspt));
        Component header = Component.text()
            .append(Component.text("ASTRAL RECORD", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text("MSPT ", NamedTextColor.GRAY))
            .append(msptValue)
            .build();
        Component footer = Component.text()
            .append(Component.text("通信遅延 ", NamedTextColor.GRAY))
            .append(Component.text(Math.max(0L, ping) + "ms", pingColor(ping)))
            .append(Component.newline())
            .append(Component.text("総参加人数 ", NamedTextColor.GRAY))
            .append(Component.text(Math.max(0, totalPlayers) + "人", NamedTextColor.AQUA))
            .build();
        return new HeaderFooter(header, footer);
    }

    private static NamedTextColor msptColor(double mspt) {
        if (mspt <= 25.0D) return NamedTextColor.GREEN;
        if (mspt <= 40.0D) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    private static NamedTextColor pingColor(long ping) {
        if (ping < 50L) return NamedTextColor.GREEN;
        if (ping < 100L) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    record HeaderFooter(Component header, Component footer) {
    }
}
