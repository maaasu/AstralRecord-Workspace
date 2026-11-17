package io.github.maaasu.astralRecord.feature.builder.command;

import io.github.maaasu.astralRecord.feature.builder.gui.BuilderGui;
import io.github.maaasu.astralRecord.feature.builder.service.BuilderService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigKeys;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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
     * 引数なし、upload、load を各画面と登録リンク発行へ振り分ける。短縮コマンドでも同じ処理を行う。
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
     * 接続設定の有無に関係なく、Dev サーバーのビルダーにだけ最初の引数を補完する。
     */
    @Test
    void completesTheFirstArgumentForDevBuildersBeforeConnectionIsConfigured() {
        Plugin plugin = mock(Plugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set(ConfigKeys.API_SERVER_ID, "Dev");
        when(plugin.getConfig()).thenReturn(config);
        BuilderGui gui = new BuilderGui(new BuilderService(plugin));
        Player sender = mock(Player.class);
        AstPlayer player = mock(AstPlayer.class);
        when(sender.hasPermission(BuilderService.PERMISSION)).thenReturn(true);
        when(player.getBukkit()).thenReturn(sender);
        BuilderTabCompleter completer = new BuilderTabCompleter(gui);

        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(player);
            assertEquals(List.of("upload", "load"), completer.onTabCompleteAsync(sender, null, "builder", new String[]{""}).join());
            assertEquals(List.of("upload"), completer.onTabCompleteAsync(sender, null, "builder", new String[]{"u"}).join());
            assertEquals(List.of("load"), completer.onTabCompleteAsync(sender, null, "bg", new String[]{"l"}).join());
            assertEquals(List.of(), completer.onTabCompleteAsync(sender, null, "builder", new String[]{"load", ""}).join());
            when(sender.hasPermission(BuilderService.PERMISSION)).thenReturn(false);
            assertEquals(List.of(), completer.onTabCompleteAsync(sender, null, "builder", new String[]{""}).join());
            when(sender.hasPermission(BuilderService.PERMISSION)).thenReturn(true);
            config.set(ConfigKeys.API_SERVER_ID, "Build");
            config.set("worldPlacements.environment", "dev");
            assertEquals(List.of(), completer.onTabCompleteAsync(sender, null, "builder", new String[]{""}).join());
        }
    }
}
