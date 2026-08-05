package io.github.maaasu.astralRecord.feature.skill.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-コマンド.md
     * 章・見出し: # 13_3-コマンド > ## 1. skill コマンド
     * 検証契約: 引数なしの `/skill` はスキルマネージャーを開く直前に OPEN 音を一度再生する。
     */
    @Test
    void opensSkillManagerWithOpenSoundForDirectCommand() {
        SkillCommand command = new SkillCommand();
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGuiEventHandler handler = mock(SkillBindGuiEventHandler.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(plugin.getSkillBindGuiEventHandler()).thenReturn(handler);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(player.getLocation()).thenReturn(location);
        when(handler.open(player)).thenReturn(true);

        try (MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class)) {
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);

            command.executePlayerCommand(astPlayer, new String[0]);
        }

        verify(player).playSound(location, Sound.BLOCK_CHEST_OPEN, SoundCategory.PLAYERS, 0.6f, 1.28f);
        verify(handler).open(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-コマンド.md
     * 章・見出し: # 13_3-コマンド > ## 1. skill コマンド
     * 検証契約: スキルマネージャーを開けない `/skill` は OPEN 音を再生しない。
     */
    @Test
    void doesNotPlayOpenSoundWhenSkillManagerRejectsOpening() {
        SkillCommand command = new SkillCommand();
        AstralRecord plugin = mock(AstralRecord.class);
        SkillBindGuiEventHandler handler = mock(SkillBindGuiEventHandler.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        Player player = mock(Player.class);
        when(plugin.getSkillBindGuiEventHandler()).thenReturn(handler);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(handler.open(player)).thenReturn(false);

        try (MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class)) {
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);

            command.executePlayerCommand(astPlayer, new String[0]);
        }

        verify(handler).open(player);
        verify(player, never()).playSound(
            org.mockito.ArgumentMatchers.any(Location.class),
            org.mockito.ArgumentMatchers.any(Sound.class),
            org.mockito.ArgumentMatchers.any(SoundCategory.class),
            org.mockito.ArgumentMatchers.anyFloat(),
            org.mockito.ArgumentMatchers.anyFloat()
        );
    }
}
