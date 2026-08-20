package io.github.maaasu.astralRecord.feature.hud.command;

import io.github.maaasu.astralRecord.feature.hud.service.AdminMessageBossBarService;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminMessageCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-コマンド.md
     * 章・見出し: # 10_3-コマンド > ## 1. `/adminmessage` / `/adminmsg`
     * 検証契約: 時間と本文を受理した実行は、本文を空白結合してBossBar表示サービスへ渡す。
     */
    @Test
    void sendsJoinedMessageAndDurationToBossBarService() {
        AdminMessageBossBarService service = mock(AdminMessageBossBarService.class);
        AdminMessageCommand command = new AdminMessageCommand(service);
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("Console");

        command.executeCommand(sender, new String[] {"15", "Server", "maintenance", "soon"});

        verify(service).show("Server maintenance soon", 15L);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-コマンド.md
     * 章・見出し: # 10_3-コマンド > ## 1. `/adminmessage` / `/adminmsg`
     * 検証契約: 数値でない時間または上限超過時間ではBossBar表示サービスを変更しない。
     */
    @Test
    void rejectsInvalidDurationWithoutChangingBossBar() {
        AdminMessageBossBarService service = mock(AdminMessageBossBarService.class);
        AdminMessageCommand command = new AdminMessageCommand(service);
        CommandSender sender = mock(CommandSender.class);

        command.executeCommand(sender, new String[] {"not-a-number", "notice"});
        command.executeCommand(sender, new String[] {"86401", "notice"});

        verify(service, never()).show(anyString(), anyLong());
    }
}
