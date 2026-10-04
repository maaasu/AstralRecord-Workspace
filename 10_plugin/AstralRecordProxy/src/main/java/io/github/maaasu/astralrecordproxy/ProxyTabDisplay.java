package io.github.maaasu.astralrecordproxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;
import java.util.List;

/** Proxy共通Tabのヘッダーとフッターを生成する。 */
final class ProxyTabDisplay {
    private static final String TITLE = "ASTRAL RECORD";
    private static final NamedTextColor[] STAR_COLORS = {
        NamedTextColor.DARK_PURPLE, NamedTextColor.LIGHT_PURPLE, NamedTextColor.WHITE,
        NamedTextColor.AQUA, NamedTextColor.LIGHT_PURPLE, NamedTextColor.GOLD
    };
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
        return render(serverAddress, ping, mspt, totalPlayers, List.of());
    }

    /** 有効なネットワークブーストを性能表示の上へ追加します。 */
    static HeaderFooter render(String serverAddress, long ping, Double mspt, int totalPlayers,
                               List<Component> boostRows) {
        return render(serverAddress, ping, mspt, totalPlayers, boostRows, 0);
    }

    /** 周期更新で進めたフレームに合わせ、タイトルと装飾線の光を移動する。 */
    static HeaderFooter render(String serverAddress, long ping, Double mspt, int totalPlayers,
                               List<Component> boostRows, int frame) {
        Component msptValue = mspt == null
            ? Component.text("計測中", NamedTextColor.GRAY)
            : Component.text(String.format(Locale.ROOT, "%.1f", mspt), msptColor(mspt));
        Component header = Component.text("✦ ", STAR_COLORS[Math.floorMod(frame, STAR_COLORS.length)])
            .append(shimmer(TITLE, frame))
            .append(Component.text(" ✦", STAR_COLORS[Math.floorMod(frame + 3, STAR_COLORS.length)]));
        if (serverAddress != null && !serverAddress.isBlank()) {
            header = header.append(Component.newline())
                .append(Component.text("⌁ ", NamedTextColor.DARK_PURPLE))
                .append(Component.text(serverAddress.trim(), NamedTextColor.AQUA))
                .append(Component.text(" ⌁", NamedTextColor.DARK_PURPLE));
        }
        header = header.append(Component.newline())
            .append(ornament(frame));
        Component footer = Component.text()
            .append(ornament(frame))
            .build();
        if (!boostRows.isEmpty()) {
            footer = footer.append(Component.newline())
                .append(Component.text("✦ ブースト発動中 ✦", STAR_COLORS[Math.floorMod(frame + 5, STAR_COLORS.length)]));
            for (Component row : boostRows) footer = footer.append(Component.newline()).append(row);
            footer = footer.append(Component.newline())
                .append(ornament(frame));
        }
        footer = footer.append(Component.newline())
            .append(Component.text("MSPT ", NamedTextColor.GRAY))
            .append(msptValue)
            .append(Component.text("  |  PING ", NamedTextColor.DARK_GRAY))
            .append(Component.text(Math.max(0L, ping) + "ms", pingColor(ping)))
            .append(Component.newline())
            .append(Component.text("オンライン ", NamedTextColor.GRAY))
            .append(Component.text(Math.max(0, totalPlayers) + "人", NamedTextColor.AQUA));
        return new HeaderFooter(header, footer);
    }

    private static Component shimmer(String value, int frame) {
        Component result = Component.empty();
        for (int index = 0; index < value.length(); index++) {
            NamedTextColor color = index == Math.floorMod(frame, value.length())
                ? NamedTextColor.WHITE : index % 2 == 0 ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA;
            result = result.append(Component.text(String.valueOf(value.charAt(index)), color, TextDecoration.BOLD));
        }
        return result;
    }

    private static Component ornament(int frame) {
        Component result = Component.text("✧", STAR_COLORS[Math.floorMod(frame, STAR_COLORS.length)]);
        for (int index = 0; index < 18; index++) {
            result = result.append(Component.text("━", index == Math.floorMod(frame, 18)
                ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.DARK_GRAY));
        }
        return result.append(Component.text("✧", STAR_COLORS[Math.floorMod(frame + 3, STAR_COLORS.length)]));
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
