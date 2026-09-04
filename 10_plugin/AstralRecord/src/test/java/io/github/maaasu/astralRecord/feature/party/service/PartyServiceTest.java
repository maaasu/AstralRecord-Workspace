package io.github.maaasu.astralRecord.feature.party.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartyServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 作成・招待
     * 検証契約: トレード中でないtargetへの招待は、従来どおり承諾command付きのクリック式通知にする。
     */
    @Test
    void keepsClickableInviteWhileTargetIsNotTrading() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        TradeService tradeService = mock(TradeService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer inviter = mock(AstPlayer.class);
        AstPlayer targetAstPlayer = mock(AstPlayer.class);
        Player inviterPlayer = mock(Player.class);
        Player target = mock(Player.class);
        UUID inviterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getTradeService()).thenReturn(tradeService);
        when(inviter.getBukkit()).thenReturn(inviterPlayer);
        when(inviterPlayer.getUniqueId()).thenReturn(inviterId);
        when(inviterPlayer.getName()).thenReturn("inviter");
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("target");

        PartyService service = new PartyService(plugin, userService);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(inviter)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(targetAstPlayer)).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.invite(inviter, target);
        }

        verify(messageService).sendClickable(
            target,
            PlayerMsgId.P_5908,
            "/party accept inviter",
            "inviter"
        );
        verify(messageService, never()).send(target, PlayerMsgId.P_5908, "inviter");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 作成・招待
     * 検証契約: トレード参加中のtargetへの招待は保持し、通知だけを非クリック式にする。
     */
    @Test
    void sendsNonClickableInviteWhileTargetIsTrading() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        TradeService tradeService = mock(TradeService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer inviter = mock(AstPlayer.class);
        AstPlayer targetAstPlayer = mock(AstPlayer.class);
        Player inviterPlayer = mock(Player.class);
        Player target = mock(Player.class);
        UUID inviterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getTradeService()).thenReturn(tradeService);
        when(tradeService.getOpenSession(targetId)).thenReturn(mock(TradeSession.class));
        when(inviter.getBukkit()).thenReturn(inviterPlayer);
        when(inviterPlayer.getUniqueId()).thenReturn(inviterId);
        when(inviterPlayer.getName()).thenReturn("inviter");
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("target");

        PartyService service = new PartyService(plugin, userService);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(inviter)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(targetAstPlayer)).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.invite(inviter, target);
        }

        verify(messageService).send(target, PlayerMsgId.P_5908, "inviter");
        verify(messageService, never()).sendClickable(
            eq(target),
            eq(PlayerMsgId.P_5908),
            anyString(),
            any(Object[].class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 作成・招待
     * 検証契約: 挑戦開始後の招待者または対象者への招待は party を作成・変更せず、P_7024 を返す。
     */
    @Test
    void rejectsInviteAfterChallengeStarts() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        AstPlayer inviter = mock(AstPlayer.class);
        AstPlayer targetAstPlayer = mock(AstPlayer.class);
        Player inviterPlayer = mock(Player.class);
        Player target = mock(Player.class);
        UUID inviterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(inviter.getBukkit()).thenReturn(inviterPlayer);
        when(inviterPlayer.getUniqueId()).thenReturn(inviterId);
        when(inviterPlayer.getName()).thenReturn("inviter");
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("target");

        PartyService service = new PartyService(plugin, userService);
        service.setChallengePartyMutationGuard(id -> id.equals(inviterId));
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(inviter)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(targetAstPlayer)).thenReturn(true);

            var result = service.invite(inviter, target);

            assertFalse(result.success());
            assertEquals(PlayerMsgId.P_7024, result.messageId());
            assertNull(service.findParty(inviterId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## 招待応答
     * 検証契約: 既存招待の承認時に挑戦開始後の対象者が含まれる場合、membership を変更せずP_7024を返す。
     */
    @Test
    void rejectsInviteAcceptanceAfterChallengeStarts() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        TradeService tradeService = mock(TradeService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer inviter = mock(AstPlayer.class);
        AstPlayer targetAstPlayer = mock(AstPlayer.class);
        Player inviterPlayer = mock(Player.class);
        Player target = mock(Player.class);
        UUID inviterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        AtomicBoolean blocked = new AtomicBoolean(false);

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getTradeService()).thenReturn(tradeService);
        when(inviter.getBukkit()).thenReturn(inviterPlayer);
        when(inviterPlayer.getUniqueId()).thenReturn(inviterId);
        when(inviterPlayer.getName()).thenReturn("inviter");
        when(targetAstPlayer.getBukkit()).thenReturn(target);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("target");

        PartyService service = new PartyService(plugin, userService);
        service.setChallengePartyMutationGuard(id -> blocked.get());
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(inviter)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(targetAstPlayer)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(targetAstPlayer);
            cache.when(() -> AstPlayerCache.get(inviterPlayer)).thenReturn(inviter);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(service.invite(inviter, target).success());
            blocked.set(true);
            bukkit.when(() -> Bukkit.getPlayerExact("inviter")).thenReturn(inviterPlayer);

            var result = service.acceptInvite(targetAstPlayer, "inviter");

            assertFalse(result.success());
            assertEquals(PlayerMsgId.P_7024, result.messageId());
            assertNull(service.findParty(targetId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## パーティーチャット mode / 管理者一覧
     * 検証契約: party 所属者が togglePartyChat を実行すると有効・無効が交互に切り替わる。
     */
    @Test
    void togglesPartyChatModeForPartyMember() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        AstPlayer player = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        UUID playerId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        when(bukkitPlayer.getUniqueId()).thenReturn(playerId);

        PartyService service = new PartyService(plugin, userService);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(player)).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(bukkitPlayer);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(bukkitPlayer)).thenReturn(true);

            assertEquals(PlayerMsgId.P_5900, service.createParty(player).messageId());
            assertEquals(PlayerMsgId.P_5926, service.togglePartyChat(player).messageId());
            assertTrue(service.isPartyChatEnabled(playerId));
            guard.when(() -> AccountModeGuard.isGameplayPlayer(bukkitPlayer)).thenReturn(false);
            assertFalse(service.isPartyChatEnabled(playerId));
            guard.when(() -> AccountModeGuard.isGameplayPlayer(bukkitPlayer)).thenReturn(true);
            assertEquals(PlayerMsgId.P_5927, service.togglePartyChat(player).messageId());
            assertFalse(service.isPartyChatEnabled(playerId));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_2-ユースケース.md
     * 章・見出し: # 19_2-ユースケース > ## UC-19-05 party chat
     * 検証契約: パーティーチャットは party member と party 外の Admin へ配信し、通常プレイヤーには配信しない。
     */
    @Test
    void broadcastsPartyChatToMembersAndAdmins() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer senderAstPlayer = mock(AstPlayer.class);
        AstPlayer adminAstPlayer = mock(AstPlayer.class);
        AstPlayer regularAstPlayer = mock(AstPlayer.class);
        Player sender = mock(Player.class);
        Player member = mock(Player.class);
        Player admin = mock(Player.class);
        Player regular = mock(Player.class);
        UUID senderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID regularId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(sender.getUniqueId()).thenReturn(senderId);
        when(member.getUniqueId()).thenReturn(memberId);
        when(admin.getUniqueId()).thenReturn(adminId);
        when(regular.getUniqueId()).thenReturn(regularId);
        when(sender.isOnline()).thenReturn(true);
        when(member.isOnline()).thenReturn(true);
        when(admin.isOnline()).thenReturn(true);
        when(regular.isOnline()).thenReturn(true);
        when(adminAstPlayer.hasAdminPermission()).thenReturn(true);
        when(regularAstPlayer.hasAdminPermission()).thenReturn(false);
        when(senderAstPlayer.getBukkit()).thenReturn(sender);

        PartyService service = new PartyService(plugin, userService);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(senderAstPlayer)).thenReturn(true);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);
            assertTrue(service.createParty(senderAstPlayer).success());
            service.findParty(senderId).addMember(memberId);
            bukkit.when(() -> Bukkit.getPlayer(senderId)).thenReturn(sender);
            bukkit.when(() -> Bukkit.getPlayer(memberId)).thenReturn(member);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(sender, member, admin, regular));
            cache.when(() -> AstPlayerCache.get(admin)).thenReturn(adminAstPlayer);
            cache.when(() -> AstPlayerCache.get(regular)).thenReturn(regularAstPlayer);

            service.broadcastPartyChat(sender, "hello");
        }

        verify(messageService).broadcastPartyChat(
            argThat(recipients -> recipients.size() == 3
                && recipients.containsAll(java.util.Set.of(sender, member, admin))),
            eq(sender),
            eq("hello")
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_4-統合フロー.md
     * 章・見出し: # 19_4-統合フロー > ## 5. パーティーチャット切替・管理者一覧 > ### パーティーチャット
     * 検証契約: party chat mode は離脱・logout・追放・解散・clearAll で stale にならず、再作成後に再度有効化できる。
     */
    @Test
    void clearsPartyChatModeAcrossPartyLifecycleOperations() {
        AstralRecord plugin = mock(AstralRecord.class);
        UserService userService = mock(UserService.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AstPlayer leader = mock(AstPlayer.class);
        AstPlayer memberAstPlayer = mock(AstPlayer.class);
        Player leaderPlayer = mock(Player.class);
        Player member = mock(Player.class);
        UUID leaderId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(leader.getBukkit()).thenReturn(leaderPlayer);
        when(memberAstPlayer.getBukkit()).thenReturn(member);
        when(leaderPlayer.getUniqueId()).thenReturn(leaderId);
        when(leaderPlayer.getName()).thenReturn("leader");
        when(member.getUniqueId()).thenReturn(memberId);
        when(member.getName()).thenReturn("member");
        when(leaderPlayer.isOnline()).thenReturn(true);
        when(member.isOnline()).thenReturn(true);

        PartyService service = new PartyService(plugin, userService);
        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(leader)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(memberAstPlayer)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(leaderPlayer)).thenReturn(true);
            guard.when(() -> AccountModeGuard.isGameplayPlayer(member)).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(leaderPlayer)).thenReturn(leader);
            cache.when(() -> AstPlayerCache.get(member)).thenReturn(memberAstPlayer);
            bukkit.when(() -> Bukkit.getPlayer(leaderId)).thenReturn(leaderPlayer);
            bukkit.when(() -> Bukkit.getPlayer(memberId)).thenReturn(member);
            bukkit.when(() -> Bukkit.getPlayerExact("leader")).thenReturn(leaderPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(service.createParty(leader).success());
            assertEquals(PlayerMsgId.P_5926, service.togglePartyChat(leader).messageId());
            assertTrue(service.leave(leader).success());
            assertTrue(service.createParty(leader).success());
            assertEquals(PlayerMsgId.P_5926, service.togglePartyChat(leader).messageId());

            service.leaveOnLogout(leaderId, "leader");
            assertTrue(service.createParty(leader).success());
            assertEquals(PlayerMsgId.P_5926, service.togglePartyChat(leader).messageId());

            assertTrue(service.invite(leader, member).success());
            assertTrue(service.acceptInvite(memberAstPlayer, "leader").success());
            assertEquals(PlayerMsgId.P_5926, service.togglePartyChat(memberAstPlayer).messageId());
            assertTrue(service.kick(leader, member).success());
            assertTrue(service.createParty(memberAstPlayer).success());
            assertEquals(PlayerMsgId.P_5926, service.togglePartyChat(memberAstPlayer).messageId());

            assertTrue(service.disband(memberAstPlayer).success());
            assertTrue(service.createParty(memberAstPlayer).success());
            assertEquals(PlayerMsgId.P_5926, service.togglePartyChat(memberAstPlayer).messageId());

            service.clearAll();
            assertTrue(service.createParty(leader).success());
            assertEquals(PlayerMsgId.P_5926, service.togglePartyChat(leader).messageId());
        }
    }
}
