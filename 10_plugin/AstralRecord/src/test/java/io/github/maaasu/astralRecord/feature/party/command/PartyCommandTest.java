package io.github.maaasu.astralRecord.feature.party.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyActionResult;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartyCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## `/party` command
     * 検証契約: `/party chat <message>` は PartyService の共通配信処理へ本文を渡す。
     */
    @Test
    void partyChatDelegatesToPartyService() {
        UUID senderId = UUID.randomUUID();
        Player sender = mockPlayer(senderId);
        AstPlayer senderAstPlayer = mock(AstPlayer.class);
        Party party = new Party(UUID.randomUUID(), senderId);
        PartyService partyService = mock(PartyService.class);
        AstralRecord plugin = mock(AstralRecord.class);
        when(senderAstPlayer.getBukkit()).thenReturn(sender);
        when(partyService.findParty(senderId)).thenReturn(party);

        try (MockedStatic<AccountModeGuard> modeGuard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class)) {
            modeGuard.when(() -> AccountModeGuard.isGameplayPlayer(senderAstPlayer)).thenReturn(true);
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            when(plugin.getPartyService()).thenReturn(partyService);

            new PartyCommand().executePlayerCommand(senderAstPlayer, new String[] {"chat", "hello"});
        }

        verify(partyService).broadcastPartyChat(same(sender), eq("hello"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## パーティーチャット mode / 管理者一覧
     * 検証契約: `/party chat` は引数なしで PartyService のパーティーチャット mode を切り替える。
     */
    @Test
    void argumentlessPartyChatTogglesMode() {
        UUID senderId = UUID.randomUUID();
        Player sender = mockPlayer(senderId);
        AstPlayer senderAstPlayer = mock(AstPlayer.class);
        PartyService partyService = mock(PartyService.class);
        AstralRecord plugin = mock(AstralRecord.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        when(senderAstPlayer.getBukkit()).thenReturn(sender);
        when(partyService.togglePartyChat(senderAstPlayer)).thenReturn(PartyActionResult.success(PlayerMsgId.P_5926));

        try (MockedStatic<AccountModeGuard> modeGuard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            modeGuard.when(() -> AccountModeGuard.isGameplayPlayer(senderAstPlayer)).thenReturn(true);
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            when(plugin.getPartyService()).thenReturn(partyService);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new PartyCommand().executePlayerCommand(senderAstPlayer, new String[] {"chat"});
        }

        verify(partyService).togglePartyChat(senderAstPlayer);
        verify(messageService).send(
            same(senderAstPlayer),
            eq(PlayerMsgId.P_5926),
            org.mockito.ArgumentMatchers.any(Object[].class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_2-ユースケース.md
     * 章・見出し: # 19_2-ユースケース > ## UC-19-06 管理者が party 一覧を確認する
     * 検証契約: Admin は account mode が通常プレイでなくても全 party 一覧の hover 行を表示できる。
     */
    @Test
    void adminCanViewAllPartiesWithHoverMembers() {
        UUID adminId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        Player admin = mockPlayer(adminId);
        Player member = mockPlayer(memberId);
        when(admin.getName()).thenReturn("admin");
        when(member.getName()).thenReturn("member");
        AstPlayer adminAstPlayer = mock(AstPlayer.class);
        PartyService partyService = mock(PartyService.class);
        AstralRecord plugin = mock(AstralRecord.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        Party party = new Party(partyId, adminId);
        party.addMember(memberId);
        when(adminAstPlayer.hasAdminPermission()).thenReturn(true);
        when(adminAstPlayer.getBukkit()).thenReturn(admin);
        when(partyService.getParties()).thenReturn(List.of(party));

        try (MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            when(plugin.getPartyService()).thenReturn(partyService);
            bukkit.when(() -> Bukkit.getPlayer(adminId)).thenReturn(admin);
            bukkit.when(() -> Bukkit.getPlayer(memberId)).thenReturn(member);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new PartyCommand().executePlayerCommand(adminAstPlayer, new String[] {"list"});
        }

        verify(messageService).send(same(adminAstPlayer), eq(PlayerMsgId.P_5928), eq(1));
        verify(messageService).sendComponent(same(admin), org.mockito.ArgumentMatchers.argThat(component ->
            component.hoverEvent() != null
                && component.hoverEvent().value().toString().contains("member")
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## `/party` command
     * 検証契約: `/party approve <player>` は掲示板参加申請の承認処理へ対象名を渡す。
     */
    @Test
    void approveCommandDelegatesToJoinRequestApproval() {
        Player leaderPlayer = mockPlayer(UUID.randomUUID());
        AstPlayer leader = mock(AstPlayer.class);
        PartyService partyService = mock(PartyService.class);
        AstralRecord plugin = mock(AstralRecord.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        when(leader.getBukkit()).thenReturn(leaderPlayer);
        when(partyService.approveJoinRequest(leader, "requester"))
            .thenReturn(PartyActionResult.success(PlayerMsgId.P_5981, "requester"));

        try (MockedStatic<AccountModeGuard> modeGuard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            modeGuard.when(() -> AccountModeGuard.isGameplayPlayer(leader)).thenReturn(true);
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            when(plugin.getPartyService()).thenReturn(partyService);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            new PartyCommand().executePlayerCommand(leader, new String[] {"approve", "requester"});
        }

        verify(partyService).approveJoinRequest(leader, "requester");
        verify(messageService).send(
            same(leader),
            eq(PlayerMsgId.P_5981),
            org.mockito.ArgumentMatchers.any(Object[].class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/19-party/19_3-メソッド仕様.md
     * 章・見出し: # 19_3-メソッド仕様 > ## `/party` command
     * 検証契約: recruitの後続引数を一つの募集文へ結合して登録し、掲載中の変更でも公開状態を維持する。
     */
    @Test
    void recruitmentCommandRegistersAndUpdatesPublishedMessage() {
        Player leaderPlayer = mockPlayer(UUID.randomUUID());
        AstPlayer leader = mock(AstPlayer.class);
        when(leader.getBukkit()).thenReturn(leaderPlayer);
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        PartyService partyService = new PartyService(plugin, mock(UserService.class));
        when(plugin.getPartyService()).thenReturn(partyService);

        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class);
             MockedStatic<AstralRecord> astralRecord = mockStatic(AstralRecord.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(leader)).thenReturn(true);
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            messages.when(PlayerMessageService::getInstance).thenReturn(mock(PlayerMessageService.class));
            assertTrue(partyService.createParty(leader).success());
            PartyCommand command = new PartyCommand();

            command.executePlayerCommand(leader, new String[] {"recruit", "  初心者", "歓迎  "});
            Party party = partyService.findParty(leaderPlayer.getUniqueId());
            assertEquals("初心者 歓迎", party.getRecruitmentMessage());
            assertTrue(partyService.publishRecruitment(leader).success());

            command.executePlayerCommand(leader, new String[] {"recruit", "ダンジョン", "攻略募集"});
            assertEquals("ダンジョン 攻略募集", party.getRecruitmentMessage());
            assertTrue(party.isRecruitmentPublished());
            assertEquals(List.of(party), partyService.getPublishedParties());
        }
    }

    private Player mockPlayer(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        return player;
    }
}
