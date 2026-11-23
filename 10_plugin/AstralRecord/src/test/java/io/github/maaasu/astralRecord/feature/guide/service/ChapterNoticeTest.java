package io.github.maaasu.astralRecord.feature.guide.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChapterNoticeTest {
    @Test
    void sendsClickableCommandOnlyBeforeFirstViewAndUsesShortCurrentStepInTheCenter() {
        Player player = mock(Player.class);
        new ChapterNotice(2, 6, "グラスボアを1体倒そう", "メールの贈り物を受け取る",
            true, true, false, false, true).send(player, false);
        var message = ArgumentCaptor.forClass(Component.class);
        var title = ArgumentCaptor.forClass(Title.class);
        verify(player).sendMessage(message.capture());
        verify(player).showTitle(title.capture());
        assertEquals("ステップ 2/6", plain(title.getValue().title()));
        assertEquals("メールの贈り物を受け取る", plain(title.getValue().subtitle()));
        assertTrue(plain(message.getValue()).contains("/guide"));
        assertTrue(message.getValue().children().stream().anyMatch(c -> ClickEvent.runCommand("/guide").equals(c.clickEvent())));
        Player returning = mock(Player.class);
        new ChapterNotice(0, 6, "グラスボアを1体倒そう", "", true, true, false, false, false).send(returning, false);
        verify(returning).sendMessage(message.capture());
        verify(returning).showTitle(title.capture());
        assertFalse(plain(message.getValue()).contains("/guide"));
        assertEquals("第1章 完了", plain(title.getValue().title()));
    }

    @Test
    void deferringTitleSendsChatImmediatelyAndUsesOnlyCurrentStepWhenDisplayIsFree() {
        Player player = mock(Player.class);
        new ChapterNotice(2, 6, "冒険", "メールを受け取る", true, false, false, false, false).send(player, false);
        new ChapterNotice(3, 6, "冒険", "バンドルを開ける", true, false, false, false, false).send(player, false);
        verify(player, never()).showTitle(any(Title.class));
        new ChapterNotice(3, 6, "冒険", "バンドルを開ける", false, true, false, false, false).send(player, false);
        var title = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(title.capture());
        assertEquals("ステップ 3/6", plain(title.getValue().title()));
        verify(player, times(2)).sendMessage(any(Component.class));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
