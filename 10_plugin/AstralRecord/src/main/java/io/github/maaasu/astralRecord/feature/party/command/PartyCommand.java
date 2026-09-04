package io.github.maaasu.astralRecord.feature.party.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyActionResult;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * /party コマンドでパーティー操作を提供します。
 */
public final class PartyCommand extends AstCommand {

    /**
     * PartyCommand を初期化します。
     */
    public PartyCommand() {
        super("party", "パーティーを管理します。", "/party [gui|create|invite|accept|decline|leave|disband|kick|promote|list|chat [message]]", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        boolean adminPartyList = args.length > 0
            && "list".equalsIgnoreCase(args[0])
            && player.hasAdminPermission();
        if (!adminPartyList && !requireGameplayMode(player)) {
            return;
        }
        PartyService partyService = AstralRecord.getInstance().getPartyService();
        if (partyService == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5919);
            return;
        }

        if (args.length == 0 || "gui".equalsIgnoreCase(args[0])) {
            PartyGui partyGui = AstralRecord.getInstance().getPartyGui();
            if (partyGui == null) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5919);
                return;
            }
            partyGui.open(player.getBukkit());
            return;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> sendResult(player, partyService.createParty(player));
            case "invite" -> invite(player, partyService, args);
            case "accept" -> accept(player, partyService, args);
            case "decline" -> decline(player, partyService, args);
            case "leave" -> sendResult(player, partyService.leave(player));
            case "disband" -> sendResult(player, partyService.disband(player));
            case "kick" -> kick(player, partyService, args);
            case "promote" -> promote(player, partyService, args);
            case "list" -> showList(player, partyService);
            case "chat" -> chat(player, partyService, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void invite(@NotNull AstPlayer player, @NotNull PartyService partyService, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5905, args[1]);
            return;
        }
        sendResult(player, partyService.invite(player, target));
    }

    private void accept(@NotNull AstPlayer player, @NotNull PartyService partyService, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }
        sendResult(player, partyService.acceptInvite(player, args[1]));
    }

    private void decline(@NotNull AstPlayer player, @NotNull PartyService partyService, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }
        sendResult(player, partyService.declineInvite(player, args[1]));
    }

    private void kick(@NotNull AstPlayer player, @NotNull PartyService partyService, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5905, args[1]);
            return;
        }
        sendResult(player, partyService.kick(player, target));
    }

    private void promote(@NotNull AstPlayer player, @NotNull PartyService partyService, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5905, args[1]);
            return;
        }
        sendResult(player, partyService.promote(player, target));
    }

    private void showList(@NotNull AstPlayer player, @NotNull PartyService partyService) {
        if (player.hasAdminPermission()) {
            showAdminList(player, partyService);
            return;
        }
        Party party = partyService.findParty(player.getBukkit().getUniqueId());
        if (party == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5902);
            return;
        }

        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5909, party.size(), PartyService.MAX_MEMBERS);
        for (UUID memberId : party.members()) {
            Player member = Bukkit.getPlayer(memberId);
            String name = member == null ? memberId.toString() : member.getName();
            String leaderMark = party.getLeaderId().equals(memberId)
                ? PlayerMsgResource.getMessage(PlayerMsgId.P_6708.getId())
                : "";
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5910, name, leaderMark);
        }
    }

    private void showAdminList(@NotNull AstPlayer player, @NotNull PartyService partyService) {
        List<Party> parties = partyService.getParties().stream()
            .sorted(Comparator.comparing(Party::getCreatedAt).thenComparing(Party::getPartyId))
            .toList();
        PlayerMessageService messageService = PlayerMessageService.getInstance();
        messageService.send(player, PlayerMsgId.P_5928, parties.size());
        for (Party party : parties) {
            String leaderName = playerName(party.getLeaderId());
            Component entry = PlayerMsgResource.formatPlainComponent(
                PlayerMsgId.P_5929.getId(),
                party.getPartyId(),
                party.size(),
                PartyService.MAX_MEMBERS,
                leaderName
            );
            messageService.sendComponent(
                player.getBukkit(),
                entry.hoverEvent(HoverEvent.showText(formatPartyMembers(party)))
            );
        }
    }

    private Component formatPartyMembers(@NotNull Party party) {
        Component members = PlayerMsgResource.formatPlainComponent(
            PlayerMsgId.P_5909.getId(),
            party.size(),
            PartyService.MAX_MEMBERS
        );
        for (UUID memberId : party.members()) {
            String name = playerName(memberId);
            String leaderMark = party.getLeaderId().equals(memberId)
                ? PlayerMsgResource.getMessage(PlayerMsgId.P_6708.getId())
                : "";
            members = members
                .append(Component.newline())
                .append(PlayerMsgResource.formatPlainComponent(PlayerMsgId.P_5910.getId(), name, leaderMark));
        }
        return members;
    }

    private String playerName(@NotNull UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player == null ? playerId.toString() : player.getName();
    }

    private void chat(@NotNull AstPlayer player, @NotNull PartyService partyService, @NotNull String[] args) {
        if (args.length == 1) {
            sendResult(player, partyService.togglePartyChat(player));
            return;
        }
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }
        Party party = partyService.findParty(player.getBukkit().getUniqueId());
        if (party == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5902);
            return;
        }

        String message = joinArgs(args, 1).trim();
        if (message.isBlank()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5946);
            return;
        }

        partyService.broadcastPartyChat(player.getBukkit(), message);
    }

    private void sendResult(@NotNull AstPlayer player, @NotNull PartyActionResult result) {
        PlayerMessageService.getInstance().send(player, result.messageId(), result.args());
    }
}
