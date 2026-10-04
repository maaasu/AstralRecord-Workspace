package io.github.maaasu.astralrecordproxy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;
import java.util.Locale;

/** Proxy共通Tabのヘッダーとフッターを生成する。 */
final class ProxyTabDisplay {
    private static final String TITLE = "A S T R A L   R E C O R D";
    static final TextColor SILVER = TextColor.color(0xB9C6D3);
    static final TextColor WHITE = TextColor.color(0xF6FAFF);
    static final TextColor MUTED = TextColor.color(0x8293A3);
    private static final TextColor CAUTION = TextColor.color(0xD4A371);
    private static final TextColor CRITICAL = TextColor.color(0xD47777);

    private ProxyTabDisplay() {
    }

    /** 公開接続先、現在backendのMSPT、閲覧者の通信遅延、Proxy全体人数を表示する。 */
    static HeaderFooter render(String serverAddress, long ping, Double mspt, int totalPlayers) {
        return render(serverAddress, ping, mspt, totalPlayers, List.of());
    }

    /** 有効なネットワークブーストを性能表示の上へ追加する。 */
    static HeaderFooter render(String serverAddress, long ping, Double mspt, int totalPlayers,
                               List<Component> boostRows) {
        return render(serverAddress, ping, mspt, totalPlayers, boostRows, 0);
    }

    /** 銀白の見出しと、余白で整理した状態表示を描画する。 */
    static HeaderFooter render(String serverAddress, long ping, Double mspt, int totalPlayers,
                               List<Component> boostRows, int frame) {
        Component header = shimmer(TITLE, SILVER, WHITE, frame, true);
        if (serverAddress != null && !serverAddress.isBlank()) {
            header = header.append(Component.newline())
                .append(Component.text(serverAddress.trim(), MUTED));
        }

        Component footer = Component.text("オンライン ", MUTED)
            .append(Component.text(Math.max(0, totalPlayers) + "人", WHITE));
        if (!boostRows.isEmpty()) {
            footer = footer.append(Component.newline())
                .append(Component.text("ブースト発動中", SILVER));
            for (Component row : boostRows) footer = footer.append(Component.newline()).append(row);
        }
        Component msptValue = mspt == null
            ? Component.text("計測中", MUTED)
            : Component.text(String.format(Locale.ROOT, "%.1f", mspt), msptColor(mspt));
        footer = footer.append(Component.newline())
            .append(Component.text("MSPT ", MUTED))
            .append(msptValue)
            .append(Component.text("    /    PING ", MUTED))
            .append(Component.text(Math.max(0L, ping) + "ms", pingColor(ping)));
        return new HeaderFooter(header, footer);
    }

    static Component shimmer(String value, TextColor base, TextColor highlight, int frame, boolean bold) {
        Component result = Component.empty();
        int center = Math.floorMod(frame, value.length() + 6) - 2;
        for (int index = 0; index < value.length(); index++) {
            double strength = Math.max(0.0D, 1.0D - Math.abs(index - center) / 3.0D);
            TextColor color = blend(base, highlight, strength * strength);
            Component letter = Component.text(String.valueOf(value.charAt(index)), color);
            result = result.append(bold ? letter.decorate(TextDecoration.BOLD) : letter);
        }
        return result;
    }

    private static TextColor blend(TextColor base, TextColor highlight, double amount) {
        int red = (int) Math.round(base.red() + (highlight.red() - base.red()) * amount);
        int green = (int) Math.round(base.green() + (highlight.green() - base.green()) * amount);
        int blue = (int) Math.round(base.blue() + (highlight.blue() - base.blue()) * amount);
        return TextColor.color(red, green, blue);
    }

    private static TextColor msptColor(double mspt) {
        if (mspt <= 25.0D) return WHITE;
        if (mspt <= 40.0D) return CAUTION;
        return CRITICAL;
    }

    private static TextColor pingColor(long ping) {
        if (ping < 50L) return WHITE;
        if (ping < 100L) return CAUTION;
        return CRITICAL;
    }

    record HeaderFooter(Component header, Component footer) {
    }
}
