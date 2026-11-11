package io.github.maaasu.astralRecord.feature.builder.command;

import io.github.maaasu.astralRecord.feature.builder.gui.BuilderGui;
import io.github.maaasu.astralRecord.feature.builder.service.BuilderService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class BuilderCommandTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_3-ビルダーGUI.md
     * 章・見出し: # 38_3-ビルダーGUI > ## dev の建築ファイル登録
     * 検証契約: 引数なし、upload、load を各画面と登録リンク発行へ振り分ける。短縮コマンドでも同じ処理を行う。
     */
    @Test
    void routesHomeUploadAndLoadForABuilder() {
        BuilderGui gui = mock(BuilderGui.class);
        Player sender = mock(Player.class);
        AstPlayer player = mock(AstPlayer.class);
        when(sender.hasPermission(BuilderService.PERMISSION)).thenReturn(true);
        when(player.getBukkit()).thenReturn(sender);
        BuilderCommand command = new BuilderCommand(gui);

        // AstCommand は usage に引数があるコマンドだけ Brigadier の引数ノードを登録する。
        assertTrue(command.getUsage().trim().contains(" "));
        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(player);
            command.onCommand(sender, null, "builder", new String[0]);
            command.onCommand(sender, null, "builder", new String[]{"upload"});
            command.onCommand(sender, null, "bg", new String[]{"LOAD"});
        }

        verify(gui).open(sender);
        verify(gui).requestSchematicUpload(sender);
        verify(gui).openRegisteredSchematics(sender);
        verifyNoMoreInteractions(gui);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/38-builder/38_3-ビルダーGUI.md
     * 章・見出し: # 38_3-ビルダーGUI > ## dev の建築ファイル登録
     * 検証契約: インポートが有効でビルダー権限がある場合だけ最初の引数を補完する。
     */
    @Test
    void completesTheFirstArgumentOnlyWhenImportIsAvailableToTheBuilder() {
        BuilderGui gui = mock(BuilderGui.class);
        Player sender = mock(Player.class);
        AstPlayer player = mock(AstPlayer.class);
        when(sender.hasPermission(BuilderService.PERMISSION)).thenReturn(true);
        when(player.getBukkit()).thenReturn(sender);
        when(gui.schematicImportEnabled()).thenReturn(true);
        BuilderTabCompleter completer = new BuilderTabCompleter(gui);

        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(player);
            assertEquals(List.of("upload"), completer.onTabComplete(sender, null, "builder", new String[]{"u"}));
            assertEquals(List.of("load"), completer.onTabComplete(sender, null, "bg", new String[]{"l"}));
            assertEquals(List.of(), completer.onTabComplete(sender, null, "builder", new String[]{"load", ""}));
            when(sender.hasPermission(BuilderService.PERMISSION)).thenReturn(false);
            assertEquals(List.of(), completer.onTabComplete(sender, null, "builder", new String[]{""}));
            when(sender.hasPermission(BuilderService.PERMISSION)).thenReturn(true);
            when(gui.schematicImportEnabled()).thenReturn(false);
            assertEquals(List.of(), completer.onTabComplete(sender, null, "builder", new String[]{""}));
        }
    }
}
