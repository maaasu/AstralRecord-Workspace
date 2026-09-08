package io.github.maaasu.astralRecord.feature.boss.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BossCommandTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-コマンド.md
     * 章・見出し: # 26_3-コマンド > ## 2. 稼働中挑戦一覧
     * 検証契約: 管理者の一覧はBossとDungeonをまとめ、各行を対象 UUID の転送コマンド付きクリックメッセージで表示する。
     */
    @Test
    void adminListCombinesBossAndDungeonAndMakesRowsClickable() {
        Player admin = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AstralRecord plugin = mock(AstralRecord.class);
        BossChallengeService bossService = mock(BossChallengeService.class);
        DungeonService dungeonService = mock(DungeonService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        UUID bossId = UUID.randomUUID();
        UUID dungeonId = UUID.randomUUID();
        when(astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())).thenReturn(true);
        when(plugin.getBossChallengeService()).thenReturn(bossService);
        when(plugin.getDungeonService()).thenReturn(dungeonService);
        when(bossService.describeActiveForAdmin()).thenReturn(List.of(
                new BossChallengeService.AdminChallengeInfo(bossId, "boss-line")
        ));
        when(dungeonService.describeActiveForAdmin()).thenReturn(List.of(
                new DungeonService.AdminSessionInfo(dungeonId, "dungeon-line")
        ));

        try (MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            cache.when(() -> AstPlayerCache.get(admin)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new BossCommand().executeCommand(admin, new String[] {"instances"});
        }

        verify(messageService).sendClickable(
                same(admin),
                eq(PlayerMsgId.P_6537),
                eq("/boss teleport boss " + bossId),
                eq("ボス | boss-line")
        );
        verify(messageService).sendClickable(
                same(admin),
                eq(PlayerMsgId.P_6537),
                eq("/boss teleport dungeon " + dungeonId),
                eq("ダンジョン | dungeon-line")
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-コマンド.md
     * 章・見出し: # 26_3-コマンド > ## 3. 管理者挑戦リーダー位置への転送
     * 検証契約: クリック由来の対象が無効になった場合、サービスの再検証失敗を専用メッセージで通知する。
     */
    @Test
    void adminTeleportReportsStaleChallenge() {
        Player admin = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AstralRecord plugin = mock(AstralRecord.class);
        BossChallengeService bossService = mock(BossChallengeService.class);
        DungeonService dungeonService = mock(DungeonService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        UUID challengeId = UUID.randomUUID();
        when(astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())).thenReturn(true);
        when(plugin.getBossChallengeService()).thenReturn(bossService);
        when(plugin.getDungeonService()).thenReturn(dungeonService);
        when(bossService.teleportAdminToLeader(admin, challengeId)).thenReturn(false);

        try (MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            cache.when(() -> AstPlayerCache.get(admin)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new BossCommand().executeCommand(
                    admin,
                    new String[] {"teleport", "boss", challengeId.toString()}
            );
        }

        verify(messageService).send(same(admin), eq(PlayerMsgId.P_6539));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-コマンド.md
     * 章・見出し: # 26_3-コマンド > ## 3. 管理者挑戦リーダー位置への転送
     * 検証契約: ADMIN権限がないプレイヤーと引数不足の呼び出しは、挑戦サービスへ到達しない。
     */
    @Test
    void rejectsNonAdminAndInvalidTeleportArguments() {
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AstralRecord plugin = mock(AstralRecord.class);
        BossChallengeService bossService = mock(BossChallengeService.class);
        DungeonService dungeonService = mock(DungeonService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        when(plugin.getBossChallengeService()).thenReturn(bossService);
        when(plugin.getDungeonService()).thenReturn(dungeonService);
        when(astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue())).thenReturn(false);

        try (MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new BossCommand().executeCommand(player, new String[] {"teleport", "boss"});
            new BossCommand().executeCommand(player, new String[] {"instances"});
        }

        verifyNoInteractions(bossService, dungeonService);
        verify(messageService, never()).sendClickable(
                any(Player.class), any(PlayerMsgId.class), anyString(), any(Object[].class)
        );
    }
}
