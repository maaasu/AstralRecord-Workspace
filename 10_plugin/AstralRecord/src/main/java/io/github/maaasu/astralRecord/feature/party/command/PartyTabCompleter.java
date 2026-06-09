package io.github.maaasu.astralRecord.feature.party.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyInvite;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /party コマンドの補完を提供します。
 */
public final class PartyTabCompleter extends AstTabCompleter {

    public PartyTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("gui", "create", "invite", "accept", "decline", "leave", "disband", "kick", "promote", "list", "chat");
        }
        if (args.length == 2 && "invite".equalsIgnoreCase(args[0])) {
            return getOnlinePlayerNames().stream()
                .filter(name -> !name.equalsIgnoreCase(player.getBukkit().getName()))
                .toList();
        }
        if (args.length == 2 && ("accept".equalsIgnoreCase(args[0]) || "decline".equalsIgnoreCase(args[0]))) {
            PartyService partyService = AstralRecord.getInstance().getPartyService();
            if (partyService == null) {
                return List.of();
            }
            return partyService.getInvites(player.getBukkit().getUniqueId()).stream()
                .map(PartyInvite::leaderId)
                .map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull)
                .map(Player::getName)
                .toList();
        }
        if (args.length == 2 && ("kick".equalsIgnoreCase(args[0]) || "promote".equalsIgnoreCase(args[0]))) {
            PartyService partyService = AstralRecord.getInstance().getPartyService();
            if (partyService == null) {
                return List.of();
            }
            Party party = partyService.findParty(player.getBukkit().getUniqueId());
            if (party == null) {
                return List.of();
            }
            return party.members().stream()
                .filter(memberId -> !memberId.equals(player.getBukkit().getUniqueId()))
                .map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull)
                .map(Player::getName)
                .toList();
        }
        return List.of();
    }
}
