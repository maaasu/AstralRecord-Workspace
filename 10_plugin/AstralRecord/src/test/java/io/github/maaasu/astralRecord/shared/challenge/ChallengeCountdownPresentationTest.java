package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.mockito.ArgumentCaptor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChallengeCountdownPresentationTest {
    /**
     * 名称の既存色と制御文字を除去し、指定色を妨げず20コードポイントに制限する。
     */
    @Test
    void normalizesNameBeforeApplyingChallengeColor() {
        assertEquals("セレネア", ChallengeCountdownPresentation.normalizeName("§x§f§f§0§0§0§0&lセレネア\n"));
        assertEquals("あ".repeat(19) + "…", ChallengeCountdownPresentation.normalizeName("あ".repeat(25)));
        assertEquals("あいう", ChallengeCountdownPresentation.normalizeName("あ\u200bいう"));
    }

    /**
     * Java名前font未収録のコードポイントを安全に置換し、通常の日本語名と省略記号を保つ。
     */
    @Test
    void replacesUnsupportedNameGlyphs() {
        assertEquals("セレネア…?", ChallengeCountdownPresentation.fontName("セレネア…😀"));
    }

    /**
     * 旧パックは専用文字と音を使わず、JE/BE対応時だけ専用表示と短音を送る。
     */
    @Test
    void routesOnlySupportedConnectionsToCustomPresentation() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0));
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            ChallengeCountdownPresentation.show(player, "§cセレネア", true, 3, false, false);
        }
        verify(player).playSound(any(Location.class), eq("minecraft:block.note_block.hat"), eq(SoundCategory.PLAYERS), eq(.8F), eq(1.2F));
        ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        verify(player).showTitle(title.capture());
        assertEquals("セレネア", PlainTextComponentSerializer.plainText().serialize(title.getValue().subtitle()));
        assertEquals(0xF3C970, title.getValue().subtitle().color().value());
        clearInvocations(player);
        ChallengeCountdownPresentation.show(player, "セレネア", true, 3, true, false);
        verify(player).showTitle(title.capture());
        assertEquals(Key.key("astralrecord:challenge_countdown"), title.getValue().title().font());
        verify(player).playSound(any(Location.class), eq("astralrecord:challenge.countdown.three"), eq(SoundCategory.PLAYERS), eq(.8F), eq(1F));
        clearInvocations(player);
        ChallengeCountdownPresentation.show(player, "星の遺跡", false, 0, false, true);
        verify(player).showTitle(title.capture());
        assertEquals("arcd1:d:0", PlainTextComponentSerializer.plainText().serialize(title.getValue().title()));
        assertEquals(0x71E1DA, title.getValue().subtitle().color().value());
        verify(player).playSound(any(Location.class), eq("astralrecord:challenge.countdown.start"), eq(SoundCategory.PLAYERS), eq(.8F), eq(1F));
        ChallengeCountdownPresentation.clear(player);
    }

    /**
     * 中断は表示と全短音を一度回収し、開始音を予約したり二重clearしたりしない。
     */
    @Test
    void cancelsPresentationWithoutSchedulingLateStartSound() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(new Location(null, 0, 0, 0));
        ChallengeCountdownPresentation.show(player, "セレネア", true, 2, true, false);
        clearInvocations(player);
        ChallengeCountdownPresentation.clear(player);
        ChallengeCountdownPresentation.clear(player);
        verify(player, times(1)).clearTitle();
        verify(player, times(5)).stopSound(anyString(), eq(SoundCategory.PLAYERS));
        verify(player, never()).playSound(any(Location.class), anyString(), any(SoundCategory.class), anyFloat(), anyFloat());
    }
}
