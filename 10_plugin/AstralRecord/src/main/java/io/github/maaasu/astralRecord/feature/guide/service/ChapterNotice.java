package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;

/** アカウントごとに新しく送る必要のある第1章の案内。番号0は今回の完了です。 */
record ChapterNotice(int number, int total, String chapter, String action, boolean chat, boolean title,
                     boolean startChat, boolean startTitle, boolean guideHint) {
    private static final Title.Times TIMES = Title.Times.times(
        Duration.ofMillis(150), Duration.ofMillis(2000), Duration.ofMillis(350));

    void send(Player player, boolean introTitleAlreadyShown) {
        String chapterName = ColorCodeUtil.toPlainText(chapter, "最初の冒険");
        String next = ColorCodeUtil.toPlainText(action, "");
        String heading = switch (number) {
            case 0 -> "第1章 完了";
            case 1 -> "第1章 開始";
            default -> "ステップ " + number + "/" + total;
        };
        if (chat) {
            boolean opening = number == 1 || startChat;
            Component message = Component.text(opening ? "第1章 開始" : heading, NamedTextColor.GOLD)
                .append(Component.text("　" + (number > 0 && !opening ? next : chapterName), NamedTextColor.WHITE));
            if (opening) message = message.append(Component.text("　ステップ " + number + "/" + total + "：" + next));
            if (guideHint) message = message.append(Component.newline())
                .append(Component.text("/guide", NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.runCommand("/guide")))
                .append(Component.text(" で進行を確認できます。", NamedTextColor.GRAY));
            player.sendMessage(message);
        }
        if (title && !(number == 1 && introTitleAlreadyShown)) {
            String subtitle = number == 0 ? "グラスボアを1体倒した！" : number == 1 ? chapterName : next;
            if (startTitle && number > 1 && !introTitleAlreadyShown) subtitle = number + "/" + total + "　" + next;
            player.showTitle(Title.title(Component.text(startTitle && !introTitleAlreadyShown ? "第1章 開始" : heading, NamedTextColor.GOLD),
                Component.text(subtitle, NamedTextColor.WHITE), TIMES));
        }
    }
}
