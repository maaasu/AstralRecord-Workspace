package io.github.maaasu.astralRecord.feature.skilltree.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /skilltree コマンドです。
 */
public class SkillTreeCommand extends AstCommand {
    private final SkillTreeService service;

    public SkillTreeCommand(@NotNull SkillTreeService service) {
        super("skilltree", "Open and manage the skill tree.", "/skilltree [reload|position-item|connector-item|points]", true);
        this.service = service;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            handleTeleport(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(player);
            case "position-item" -> handlePositionItem(player, args);
            case "connector-item" -> handleConnectorItem(player, args);
            case "points" -> handlePoints(player, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleTeleport(@NotNull AstPlayer player) {
        if (!service.canTeleportFrom(player.getBukkit().getWorld())) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5829.getId()));
            return;
        }
        if (!service.teleportToSkillTree(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5820.getId()));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5819.getId()));
    }

    private void handleReload(@NotNull AstPlayer player) {
        if (!service.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }
        int count = service.loadAll();
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5815.getId(), count));
    }

    private void handlePositionItem(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!service.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }
        int amount = args.length >= 3 ? parseInt(args[2], 1) : 1;
        ItemStack itemStack = service.createPositionItem(args[1], amount);
        if (itemStack == null) {
            sendUsage(player.getBukkit());
            return;
        }
        var leftover = player.getBukkit().getInventory().addItem(itemStack);
        if (!leftover.isEmpty()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5241.getId()));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5816.getId(), args[1], amount));
    }

    private void handleConnectorItem(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!service.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }
        int amount = args.length >= 2 ? parseInt(args[1], 1) : 1;
        var leftover = player.getBukkit().getInventory().addItem(service.createConnectorItem(amount));
        if (!leftover.isEmpty()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5241.getId()));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5817.getId(), amount));
    }

    private void handlePoints(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!service.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5707.getId()));
            return;
        }
        if (args.length < 3) {
            sendUsage(player.getBukkit());
            return;
        }
        AstPlayer target = player;
        if (args.length >= 4) {
            var bukkitTarget = Bukkit.getPlayerExact(args[3]);
            target = bukkitTarget == null ? null : getAstPlayer(bukkitTarget);
            if (target == null) {
                sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), args[3]));
                return;
            }
        }
        int points = parseInt(args[2], 0);
        if ("set".equalsIgnoreCase(args[1])) {
            service.setSkillPoints(target, points);
        } else if ("add".equalsIgnoreCase(args[1])) {
            service.addSkillPoints(target, points);
        } else {
            sendUsage(player.getBukkit());
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5818.getId(), target.getBukkit().getName(), service.state(target).skillPoints()));
    }

    private int parseInt(@NotNull String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
