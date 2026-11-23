package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
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
    void doesNotConsumeCentralTitleWhileChestOrAnvilIsOpen() {
        AstralRecord plugin = mock(AstralRecord.class);
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getType()).thenReturn(InventoryType.CHEST, InventoryType.ANVIL, InventoryType.CRAFTING);
        when(plugin.isGuideTitleOccupied(player)).thenCallRealMethod();
        assertTrue(plugin.isGuideTitleOccupied(player));
        assertTrue(plugin.isGuideTitleOccupied(player));
        assertFalse(plugin.isGuideTitleOccupied(player));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
