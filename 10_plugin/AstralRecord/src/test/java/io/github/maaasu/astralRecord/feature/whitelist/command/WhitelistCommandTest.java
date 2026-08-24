package io.github.maaasu.astralRecord.feature.whitelist.command;

import io.github.maaasu.astralRecord.feature.whitelist.service.WhitelistService;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhitelistCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 5. whitelist メンテナンス切替
     * 検証契約: コンソールの引数なし実行は現在値を反転し、falseをサービスへ保存する。
     */
    @Test
    void noArgumentTogglesWhitelistForConsole() {
        WhitelistService service = mock(WhitelistService.class);
        CommandSender sender = mock(CommandSender.class);
        when(service.isEnabled()).thenReturn(true);

        new WhitelistCommand(service).onCommand(sender, null, "whitelist", new String[0]);

        verify(service).setEnabled(false);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-コマンド.md
     * 章・見出し: # 03_3-コマンド > ## 5. whitelist メンテナンス切替
     * 検証契約: true/false以外の引数は状態を変更せず、エラーとusageを実行者へ返す。
     */
    @Test
    void rejectsNonBooleanArgumentWithoutChangingState() {
        WhitelistService service = mock(WhitelistService.class);
        CommandSender sender = mock(CommandSender.class);

        new WhitelistCommand(service).onCommand(sender, null, "whitelist", new String[] {"yes"});

        verify(service, never()).setEnabled(true);
        verify(service, never()).setEnabled(false);
        ArgumentCaptor<String> messagesCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender, times(2)).sendMessage(messagesCaptor.capture());
        List<String> messages = messagesCaptor.getAllValues();
        assertTrue(messages.stream().anyMatch(message ->
            message.contains("whitelist の引数は true または false で指定してください。")
        ));
        assertTrue(messages.stream().anyMatch(message ->
            message.contains("使い方: §e/whitelist [true|false]")
        ));
    }
}
