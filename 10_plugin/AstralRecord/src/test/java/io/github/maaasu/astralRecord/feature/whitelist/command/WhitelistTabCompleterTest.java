package io.github.maaasu.astralRecord.feature.whitelist.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class WhitelistTabCompleterTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 6. whitelist メンテナンス切替
     * 検証契約: whitelist の第1引数では enable と user を補完する。
     */
    @Test
    void completesWhitelistSubcommands() {
        CommandSender sender = mock(CommandSender.class);

        List<String> completions = new WhitelistTabCompleter()
            .onTabComplete(sender, null, "whitelist", new String[] {""});

        assertEquals(List.of("enable", "user"), completions);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 7. whitelist ユーザー設定
     * 検証契約: user の第2引数では add と remove を補完する。
     */
    @Test
    void completesWhitelistUserOperations() {
        CommandSender sender = mock(CommandSender.class);

        List<String> completions = new WhitelistTabCompleter()
            .onTabComplete(sender, null, "whitelist", new String[] {"user", ""});

        assertEquals(List.of("add", "remove"), completions);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 6. whitelist メンテナンス切替
     * 検証契約: enable の第2引数では true と false を補完する。
     */
    @Test
    void completesWhitelistBooleanValues() {
        CommandSender sender = mock(CommandSender.class);

        List<String> completions = new WhitelistTabCompleter()
            .onTabComplete(sender, null, "whitelist", new String[] {"enable", ""});

        assertEquals(List.of("true", "false"), completions);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 7. whitelist ユーザー設定
     * 検証契約: user add/remove の第3引数ではオンラインプレイヤー名を補完する。
     */
    @Test
    void completesOnlinePlayerNamesForWhitelistUser() {
        CommandSender sender = mock(CommandSender.class);
        Player player = mock(Player.class);
        org.mockito.Mockito.when(player.getName()).thenReturn("Alice");

        try (MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));

            List<String> completions = new WhitelistTabCompleter()
                .onTabComplete(sender, null, "whitelist", new String[] {"user", "add", ""});

            assertEquals(List.of("Alice"), completions);
        }
    }
}
