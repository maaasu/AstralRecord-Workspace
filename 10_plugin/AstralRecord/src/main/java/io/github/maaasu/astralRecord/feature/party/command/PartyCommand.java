package io.github.maaasu.astralRecord.feature.party.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyActionResult;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * /party コマンドでパーティー操作を提供します。
 */
public final class PartyCommand extends AstCommand {

    /**
     * PartyCommand を初期化します。
     */
    public PartyCommand() {
        super("party", "Manage temporary parties.", "/party [gui|create|invite|accept|decline|leave|disband|kick|promote|list]", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        PartyService partyService = AstralRecord.getInstance().getPartyService();
        if (partyService == null) {
            player.sendMessage(PlayerMsgId.P_5919);
            return;
        }

        if (args.length == 0 || "gui".equalsIgnoreCase(args[0])) {
            PartyGui partyGui = AstralRecord.getInstance().getPartyGui();
            if (partyGui == null) {
                player.sendMessage(PlayerMsgId.P_5919);
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
            default -> sendUsage(player.getBukkit());
        }
    }

    private void invite(@NotNull AstPlayer player, @NotNull PartyService partyService, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(PlayerMsgId.P_5905, args[1]);
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
            player.sendMessage(PlayerMsgId.P_5905, args[1]);
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
            player.sendMessage(PlayerMsgId.P_5905, args[1]);
            return;
        }
        sendResult(player, partyService.promote(player, target));
    }

    private void showList(@NotNull AstPlayer player, @NotNull PartyService partyService) {
        Party party = partyService.findParty(player.getBukkit().getUniqueId());
        if (party == null) {
            player.sendMessage(PlayerMsgId.P_5902);
            return;
        }

        player.sendMessage(PlayerMsgId.P_5909, party.size(), PartyService.MAX_MEMBERS);
        for (UUID memberId : party.members()) {
            Player member = Bukkit.getPlayer(memberId);
            String name = member == null ? memberId.toString() : member.getName();
            String leaderMark = party.getLeaderId().equals(memberId) ? " (Leader)" : "";
            player.sendMessage(PlayerMsgId.P_5910, name, leaderMark);
        }
    }

    private void sendResult(@NotNull AstPlayer player, @NotNull PartyActionResult result) {
        player.getBukkit().sendMessage(PlayerMsgResource.format(result.messageId().getId(), result.args()));
    }
}
