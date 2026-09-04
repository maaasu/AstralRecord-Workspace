package io.github.maaasu.astralRecord.feature.party.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyActionResult;
import io.github.maaasu.astralRecord.feature.party.model.PartyInvite;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * 一時パーティーの作成、招待、参加、離脱を管理します。
 */
public final class PartyService {
    public static final int MAX_MEMBERS = 6;

    private final AstralRecord plugin;
    private final UserService userService;
    private final Map<UUID, Party> parties = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partyIdByMember = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, PartyInvite>> invitesByTarget = new ConcurrentHashMap<>();
    private final Set<UUID> partyChatEnabled = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<PartyMembershipChangeListener> membershipChangeListeners =
            new CopyOnWriteArrayList<>();
    private volatile Predicate<UUID> challengePartyMutationGuard = ignored -> false;

    /**
     * PartyService を作成します。
     *
     * @param plugin プラグインインスタンス
     * @param userService 履歴登録に使うユーザーサービス
     */
    public PartyService(@NotNull AstralRecord plugin, @NotNull UserService userService) {
        this.plugin = plugin;
        this.userService = userService;
    }

    /**
     * 挑戦開始後のパーティー招待・承認を拒否する判定を設定します。
     * <p>
     * PartyService が Boss／Dungeon の実装へ直接依存しないよう、起動時に呼出元が判定を注入します。
     * 待機ハブでメンバーを揃えている段階はこの判定で拒否しません。
     *
     * @param guard 対象プレイヤーが招待・承認操作を制限されている場合に true を返す判定
     */
    public void setChallengePartyMutationGuard(@NotNull Predicate<UUID> guard) {
        this.challengePartyMutationGuard = guard;
    }

    /**
     * パーティーを新規作成します。
     *
     * @param leader 作成者
     * @return 操作結果
     */
    public synchronized @NotNull PartyActionResult createParty(@NotNull AstPlayer leader) {
        if (!AccountModeGuard.isGameplayPlayer(leader)) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        UUID leaderId = leader.getBukkit().getUniqueId();
        if (partyIdByMember.containsKey(leaderId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5901);
        }

        Party party = new Party(UUID.randomUUID(), leaderId);
        parties.put(party.getPartyId(), party);
        partyIdByMember.put(leaderId, party.getPartyId());
        recordHistory(leaderId, "PARTY_CREATED", "Party created: " + party.getPartyId());
        return PartyActionResult.success(PlayerMsgId.P_5900);
    }

    /**
     * 指定プレイヤーをパーティーへ招待します。招待者が未所属なら自動でパーティーを作成します。
     * トレード参加中の対象へは、inventory close と競合しない非クリック式の通知を送ります。
     *
     * @param inviter 招待者
     * @param target 招待対象
     * @return 操作結果
     */
    public synchronized @NotNull PartyActionResult invite(@NotNull AstPlayer inviter, @NotNull Player target) {
        if (!AccountModeGuard.isGameplayPlayer(inviter) || !AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(target))) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        UUID inviterId = inviter.getBukkit().getUniqueId();
        UUID targetId = target.getUniqueId();
        if (inviterId.equals(targetId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5904);
        }
        if (isChallengePartyMutationBlocked(inviterId) || isChallengePartyMutationBlocked(targetId)) {
            return PartyActionResult.failure(PlayerMsgId.P_7024);
        }
        if (partyIdByMember.containsKey(targetId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5906);
        }

        Party party = findParty(inviterId);
        if (party == null) {
            PartyActionResult created = createParty(inviter);
            if (!created.success()) {
                return created;
            }
            party = findParty(inviterId);
        }
        if (party == null) {
            return PartyActionResult.failure(PlayerMsgId.P_5919);
        }
        if (!party.isLeader(inviterId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5920);
        }
        if (party.size() >= MAX_MEMBERS) {
            return PartyActionResult.failure(PlayerMsgId.P_5903, MAX_MEMBERS);
        }

        invitesByTarget.computeIfAbsent(targetId, ignored -> new LinkedHashMap<>())
            .put(inviterId, new PartyInvite(party.getPartyId(), inviterId, targetId, java.time.Instant.now()));
        String inviterName = inviter.getBukkit().getName();
        PlayerMessageService messageService = PlayerMessageService.getInstance();
        TradeService tradeService = plugin.getTradeService();
        if (tradeService != null && tradeService.getOpenSession(targetId) != null) {
            messageService.send(target, PlayerMsgId.P_5908, inviterName);
        } else {
            messageService.sendClickable(
                target,
                PlayerMsgId.P_5908,
                "/party accept " + inviterName,
                inviterName
            );
        }
        recordHistory(inviterId, "PARTY_INVITED", "Party invite sent to " + target.getName());
        recordHistory(targetId, "PARTY_INVITE_RECEIVED", "Party invite received from " + inviter.getBukkit().getName());
        return PartyActionResult.success(PlayerMsgId.P_5907, target.getName());
    }

    /**
     * 招待を承諾してパーティーへ参加します。
     *
     * @param player 承諾者
     * @param leaderName 招待者名
     * @return 操作結果
     */
    public synchronized @NotNull PartyActionResult acceptInvite(@NotNull AstPlayer player, @NotNull String leaderName) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        Player leader = Bukkit.getPlayerExact(leaderName);
        if (leader != null && !AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(leader))) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        if (leader == null) {
            return PartyActionResult.failure(PlayerMsgId.P_5905, leaderName);
        }

        UUID playerId = player.getBukkit().getUniqueId();
        UUID leaderId = leader.getUniqueId();
        if (isChallengePartyMutationBlocked(playerId) || isChallengePartyMutationBlocked(leaderId)) {
            return PartyActionResult.failure(PlayerMsgId.P_7024);
        }
        PartyInvite invite = invitesByTarget.getOrDefault(playerId, Map.of()).get(leaderId);
        if (invite == null) {
            return PartyActionResult.failure(PlayerMsgId.P_5911, leaderName);
        }
        if (partyIdByMember.containsKey(playerId)) {
            removeInvite(playerId, leaderId);
            return PartyActionResult.failure(PlayerMsgId.P_5901);
        }

        Party party = parties.get(invite.partyId());
        if (party == null || !party.contains(leaderId)) {
            removeInvite(playerId, leaderId);
            return PartyActionResult.failure(PlayerMsgId.P_5911, leaderName);
        }
        if (party.size() >= MAX_MEMBERS) {
            return PartyActionResult.failure(PlayerMsgId.P_5903, MAX_MEMBERS);
        }

        party.addMember(playerId);
        partyIdByMember.put(playerId, party.getPartyId());
        removeInvite(playerId, leaderId);
        clearInvitesForTarget(playerId);
        notifyPartyExcept(party, playerId, PlayerMsgId.P_5913, player.getBukkit().getName());
        recordHistory(playerId, "PARTY_JOINED", "Party joined: " + party.getPartyId());
        notifyMembershipChanged(party.getPartyId());
        return PartyActionResult.success(PlayerMsgId.P_5912, leader.getName());
    }

    /**
     * 招待を辞退します。
     *
     * @param player 辞退者
     * @param leaderName 招待者名
     * @return 操作結果
     */
    public synchronized @NotNull PartyActionResult declineInvite(@NotNull AstPlayer player, @NotNull String leaderName) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        Player leader = Bukkit.getPlayerExact(leaderName);
        if (leader == null) {
            return PartyActionResult.failure(PlayerMsgId.P_5905, leaderName);
        }
        UUID playerId = player.getBukkit().getUniqueId();
        UUID leaderId = leader.getUniqueId();
        PartyInvite invite = invitesByTarget.getOrDefault(playerId, Map.of()).get(leaderId);
        if (invite == null) {
            return PartyActionResult.failure(PlayerMsgId.P_5911, leaderName);
        }

        removeInvite(playerId, leaderId);
        PlayerMessageService.getInstance().send(leader, PlayerMsgId.P_5915, player.getBukkit().getName());
        recordHistory(playerId, "PARTY_INVITE_DECLINED", "Party invite declined from " + leaderName);
        return PartyActionResult.success(PlayerMsgId.P_5914, leaderName);
    }

    /**
     * パーティーから離脱します。
     *
     * @param player 離脱者
     * @return 操作結果
     */
    public synchronized @NotNull PartyActionResult leave(@NotNull AstPlayer player) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        boolean left = leaveInternal(player.getBukkit().getUniqueId(), player.getBukkit().getName(), "PARTY_LEFT", true);
        return left
            ? PartyActionResult.success(PlayerMsgId.P_5916)
            : PartyActionResult.failure(PlayerMsgId.P_5902);
    }

    /**
     * ログアウトしたプレイヤーをパーティーから自動離脱させます。
     *
     * @param playerId プレイヤーUUID
     * @param playerName プレイヤー名
     */
    public synchronized void leaveOnLogout(@NotNull UUID playerId, @NotNull String playerName) {
        leaveInternal(playerId, playerName, "PARTY_LEFT_LOGOUT", true);
        clearInvitesForTarget(playerId);
    }

    /**
     * リーダー操作でパーティーを解散します。
     *
     * @param leader リーダー
     * @return 操作結果
     */
    public synchronized @NotNull PartyActionResult disband(@NotNull AstPlayer leader) {
        if (!AccountModeGuard.isGameplayPlayer(leader)) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        Party party = findParty(leader.getBukkit().getUniqueId());
        if (party == null) {
            return PartyActionResult.failure(PlayerMsgId.P_5902);
        }
        if (!party.isLeader(leader.getBukkit().getUniqueId())) {
            return PartyActionResult.failure(PlayerMsgId.P_5920);
        }

        List<UUID> members = party.members();
        UUID leaderId = leader.getBukkit().getUniqueId();
        parties.remove(party.getPartyId());
        for (UUID memberId : members) {
            partyIdByMember.remove(memberId);
            partyChatEnabled.remove(memberId);
            clearInvitesForTarget(memberId);
            if (!memberId.equals(leaderId)) {
                sendIfOnline(memberId, PlayerMsgId.P_5918);
            }
            recordHistory(memberId, "PARTY_DISBANDED", "Party disbanded: " + party.getPartyId());
        }
        notifyMembershipChanged(party.getPartyId());
        return PartyActionResult.success(PlayerMsgId.P_5918);
    }

    /**
     * メンバーをパーティーから追放します。
     *
     * @param leader リーダー
     * @param target 追放対象
     * @return 操作結果
     */
    public synchronized @NotNull PartyActionResult kick(@NotNull AstPlayer leader, @NotNull Player target) {
        if (!AccountModeGuard.isGameplayPlayer(leader) || !AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(target))) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        UUID leaderId = leader.getBukkit().getUniqueId();
        UUID targetId = target.getUniqueId();
        if (leaderId.equals(targetId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5925);
        }

        Party party = findParty(leaderId);
        if (party == null) {
            return PartyActionResult.failure(PlayerMsgId.P_5902);
        }
        if (!party.isLeader(leaderId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5920);
        }
        if (!party.contains(targetId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5924);
        }

        party.removeMember(targetId);
        partyIdByMember.remove(targetId);
        partyChatEnabled.remove(targetId);
        clearInvitesForTarget(targetId);
        PlayerMessageService.getInstance().send(target, PlayerMsgId.P_5922);
        notifyPartyExcept(party, leaderId, PlayerMsgId.P_5917, target.getName());
        recordHistory(targetId, "PARTY_KICKED", "Kicked from party: " + party.getPartyId());
        recordHistory(leaderId, "PARTY_MEMBER_KICKED", "Kicked party member: " + target.getName());
        notifyMembershipChanged(party.getPartyId());
        return PartyActionResult.success(PlayerMsgId.P_5921, target.getName());
    }

    /**
     * リーダーを移譲します。
     *
     * @param leader 現リーダー
     * @param target 新リーダー
     * @return 操作結果
     */
    public synchronized @NotNull PartyActionResult promote(@NotNull AstPlayer leader, @NotNull Player target) {
        if (!AccountModeGuard.isGameplayPlayer(leader) || !AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(target))) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        UUID leaderId = leader.getBukkit().getUniqueId();
        UUID targetId = target.getUniqueId();
        Party party = findParty(leaderId);
        if (party == null) {
            return PartyActionResult.failure(PlayerMsgId.P_5902);
        }
        if (!party.isLeader(leaderId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5920);
        }
        if (!party.contains(targetId)) {
            return PartyActionResult.failure(PlayerMsgId.P_5924);
        }

        party.setLeaderId(targetId);
        notifyPartyExcept(party, leaderId, PlayerMsgId.P_5923, target.getName());
        recordHistory(leaderId, "PARTY_LEADER_TRANSFERRED", "Party leader transferred to " + target.getName());
        recordHistory(targetId, "PARTY_LEADER_ASSIGNED", "Party leader assigned: " + party.getPartyId());
        return PartyActionResult.success(PlayerMsgId.P_5923, target.getName());
    }

    /**
     * 実行者のパーティーチャットモードを切り替えます。
     *
     * @param player モードを切り替えるプレイヤー
     * @return 操作結果。パーティー未所属または通常プレイ以外の場合は失敗
     */
    public synchronized @NotNull PartyActionResult togglePartyChat(@NotNull AstPlayer player) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return PartyActionResult.failure(PlayerMsgId.P_5065);
        }
        UUID playerId = player.getBukkit().getUniqueId();
        if (findParty(playerId) == null) {
            partyChatEnabled.remove(playerId);
            return PartyActionResult.failure(PlayerMsgId.P_5902);
        }
        if (partyChatEnabled.add(playerId)) {
            return PartyActionResult.success(PlayerMsgId.P_5926);
        }
        partyChatEnabled.remove(playerId);
        return PartyActionResult.success(PlayerMsgId.P_5927);
    }

    /**
     * 指定プレイヤーのパーティーチャットモードが有効かを判定します。
     *
     * @param playerId 判定対象プレイヤーUUID
     * @return パーティー所属中、パーティーチャットモードが有効、かつ通常プレイ中なら true
     */
    public synchronized boolean isPartyChatEnabled(@NotNull UUID playerId) {
        return partyChatEnabled.contains(playerId)
            && partyIdByMember.containsKey(playerId)
            && AccountModeGuard.isGameplayPlayer(Bukkit.getPlayer(playerId));
    }

    /**
     * 指定プレイヤーのパーティーチャットを、パーティーメンバーと管理者へ配信します。
     *
     * @param sender 発言者
     * @param message チャット本文
     */
    public void broadcastPartyChat(@NotNull Player sender, @NotNull String message) {
        if (message.isBlank()) {
            return;
        }
        Party party = findParty(sender.getUniqueId());
        if (party == null) {
            return;
        }

        Map<UUID, Player> recipients = new LinkedHashMap<>();
        for (UUID memberId : party.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                recipients.put(member.getUniqueId(), member);
            }
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            AstPlayer onlineAstPlayer = AstPlayerCache.get(onlinePlayer);
            if (onlineAstPlayer != null && onlineAstPlayer.hasAdminPermission()) {
                recipients.putIfAbsent(onlinePlayer.getUniqueId(), onlinePlayer);
            }
        }

        PlayerMessageService.getInstance().broadcastPartyChat(recipients.values(), sender, message);
    }

    /**
     * 現在保持している全パーティーのスナップショットを返します。
     *
     * @return パーティー一覧。戻り値のリスト自体は変更不可
     */
    public synchronized @NotNull List<Party> getParties() {
        return List.copyOf(parties.values());
    }

    public @Nullable Party findParty(@NotNull UUID playerId) {
        UUID partyId = partyIdByMember.get(playerId);
        return partyId == null ? null : parties.get(partyId);
    }

    /**
     * パーティー ID から現在のパーティーを取得します。
     *
     * @param partyId パーティー ID
     * @return 存在するパーティー。解散済みなら {@code null}
     */
    public synchronized @Nullable Party findPartyById(@NotNull UUID partyId) {
        return parties.get(partyId);
    }

    /**
     * パーティー構成変更 listener を登録します。
     *
     * @param listener 構成変更時に呼び出す listener
     */
    public void addMembershipChangeListener(@NotNull PartyMembershipChangeListener listener) {
        membershipChangeListeners.addIfAbsent(listener);
    }

    /**
     * パーティー構成変更 listener の登録を解除します。
     *
     * @param listener 登録済み listener
     */
    public void removeMembershipChangeListener(@NotNull PartyMembershipChangeListener listener) {
        membershipChangeListeners.remove(listener);
    }

    public @NotNull List<PartyInvite> getInvites(@NotNull UUID playerId) {
        return new ArrayList<>(invitesByTarget.getOrDefault(playerId, Map.of()).values());
    }

    public void clearAll() {
        parties.clear();
        partyIdByMember.clear();
        invitesByTarget.clear();
        partyChatEnabled.clear();
    }

    private boolean leaveInternal(@NotNull UUID playerId, @NotNull String playerName, @NotNull String eventType, boolean notify) {
        partyChatEnabled.remove(playerId);
        Party party = findParty(playerId);
        if (party == null) {
            return false;
        }

        party.removeMember(playerId);
        partyIdByMember.remove(playerId);
        recordHistory(playerId, eventType, "Party left: " + party.getPartyId());
        if (party.isEmpty()) {
            parties.remove(party.getPartyId());
            notifyMembershipChanged(party.getPartyId());
            return true;
        }

        if (party.getLeaderId().equals(playerId)) {
            UUID nextLeader = party.members().get(0);
            party.setLeaderId(nextLeader);
            Player nextLeaderPlayer = Bukkit.getPlayer(nextLeader);
            notifyParty(party, PlayerMsgId.P_5923, nextLeaderPlayer == null ? nextLeader.toString() : nextLeaderPlayer.getName());
            recordHistory(nextLeader, "PARTY_LEADER_ASSIGNED", "Party leader assigned after leave: " + party.getPartyId());
        }
        if (notify) {
            notifyParty(party, PlayerMsgId.P_5917, playerName);
        }
        notifyMembershipChanged(party.getPartyId());
        return true;
    }

    private void notifyMembershipChanged(@NotNull UUID partyId) {
        for (PartyMembershipChangeListener listener : membershipChangeListeners) {
            try {
                listener.onPartyMembershipChanged(partyId);
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_6110, exception, partyId.toString());
            }
        }
    }

    private boolean isChallengePartyMutationBlocked(@NotNull UUID playerId) {
        return challengePartyMutationGuard.test(playerId);
    }

    private void notifyParty(@NotNull Party party, @NotNull PlayerMsgId messageId, Object... args) {
        for (UUID memberId : party.members()) {
            sendIfOnline(memberId, messageId, args);
        }
    }

    private void notifyPartyExcept(@NotNull Party party, @NotNull UUID excludedMemberId, @NotNull PlayerMsgId messageId, Object... args) {
        for (UUID memberId : party.members()) {
            if (!memberId.equals(excludedMemberId)) {
                sendIfOnline(memberId, messageId, args);
            }
        }
    }

    private void sendIfOnline(@NotNull UUID memberId, @NotNull PlayerMsgId messageId, Object... args) {
        Player member = Bukkit.getPlayer(memberId);
        if (member != null && member.isOnline()) {
            PlayerMessageService.getInstance().send(member, messageId, args);
        }
    }

    private void removeInvite(@NotNull UUID targetId, @NotNull UUID leaderId) {
        Map<UUID, PartyInvite> invites = invitesByTarget.get(targetId);
        if (invites == null) {
            return;
        }
        invites.remove(leaderId);
        if (invites.isEmpty()) {
            invitesByTarget.remove(targetId);
        }
    }

    private void clearInvitesForTarget(@NotNull UUID targetId) {
        invitesByTarget.remove(targetId);
        for (Map<UUID, PartyInvite> invites : invitesByTarget.values()) {
            invites.remove(targetId);
        }
    }

    private void recordHistory(@NotNull UUID userId, @NotNull String eventType, @NotNull String message) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                userService.recordUserHistory(userId, eventType, "PLUGIN", message);
            } catch (Exception e) {
                Logger.log(LogId.W_6100, userId, eventType, e.getMessage());
            }
        });
    }
}
