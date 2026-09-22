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
     * 公開接続先、現在backendのMSPT、閲覧者の通信遅延、Proxy全体人数を表示する。
     *
     * @param serverAddress 公開サーバーアドレス。空文字の場合は省略
     * @param ping 閲覧者の通信遅延（ミリ秒）
     * @param mspt 現在backendのMSPT。未受信の場合はnull
     * @param totalPlayers Proxy全体の参加人数
     * @return Tabへ送信するヘッダーとフッター
     */
    static HeaderFooter render(String serverAddress, long ping, Double mspt, int totalPlayers) {
        Component msptValue = mspt == null
            ? Component.text("計測中", NamedTextColor.GRAY)
            : Component.text(String.format(Locale.ROOT, "%.1f", mspt), msptColor(mspt));
        Component header = Component.text("✦ ASTRAL RECORD ✦", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD);
        if (serverAddress != null && !serverAddress.isBlank()) {
            header = header.append(Component.newline())
                .append(Component.text(serverAddress.trim(), NamedTextColor.AQUA));
        }
        header = header.append(Component.newline())
            .append(Component.text("━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY));
        Component footer = Component.text()
            .append(Component.text("━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY))
            .append(Component.newline())
            .append(Component.text("MSPT ", NamedTextColor.GRAY))
            .append(msptValue)
            .append(Component.text("  |  PING ", NamedTextColor.DARK_GRAY))
            .append(Component.text(Math.max(0L, ping) + "ms", pingColor(ping)))
            .append(Component.newline())
            .append(Component.text("オンライン ", NamedTextColor.GRAY))
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
