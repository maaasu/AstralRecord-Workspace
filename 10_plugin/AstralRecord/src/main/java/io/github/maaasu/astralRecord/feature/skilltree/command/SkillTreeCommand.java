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
        super("skilltree", "Open and manage the skill tree.", "/skilltree [back|reload|position-item|connector-item|option]", true);
        this.service = service;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            handleTeleport(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "back" -> handleBack(player);
            case "reload" -> handleReload(player);
            case "position-item" -> handlePositionItem(player, args);
            case "connector-item" -> handleConnectorItem(player, args);
            case "option" -> handleOption(player, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleBack(@NotNull AstPlayer player) {
        service.returnToBase(player.getBukkit()).thenAccept(success ->
                Bukkit.getScheduler().runTask(io.github.maaasu.astralRecord.AstralRecord.getInstance(), () -> {
                    if (!success) {
                        sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5820.getId()));
                    }
                })
        );
    }

    private void handleTeleport(@NotNull AstPlayer player) {
        if (!service.canTeleportFrom(player.getBukkit().getWorld())) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5829.getId()));
            return;
        }
        service.teleportToSkillTree(player).thenAccept(success ->
                Bukkit.getScheduler().runTask(io.github.maaasu.astralRecord.AstralRecord.getInstance(), () -> {
                    if (!success) {
                        sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5820.getId()));
                        return;
                    }
                    sendSuccess(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5819.getId()));
                })
        );
    }

    private void handleReload(@NotNull AstPlayer player) {
        if (!service.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5719.getId()));
            return;
        }
        int count = service.loadAll();
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5815.getId(), count));
    }

    private void handlePositionItem(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!service.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5719.getId()));
            return;
        }
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }
        if (!service.hasDefinedPosition(args[1])) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5834.getId(), args[1]));
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
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5719.getId()));
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

    private void handleOption(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "view-distance" -> handleViewDistanceOption(player, args);
            case "edge-display" -> handleEdgeDisplayOption(player, args);
            case "reset" -> handleResetOption(player);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleViewDistanceOption(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 3) {
            sendUsage(player.getBukkit());
            return;
        }
        int viewDistance = parseInt(args[2], Integer.MIN_VALUE);
        if (viewDistance < service.minViewDistance() || viewDistance > service.maxViewDistance()) {
            sendError(
                    player.getBukkit(),
                    PlayerMsgResource.format(PlayerMsgId.P_5844.getId(), service.minViewDistance(), service.maxViewDistance())
            );
            return;
        }
        service.updateViewDistance(player.getBukkit(), viewDistance);
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5842.getId(), viewDistance));
    }

    private void handleEdgeDisplayOption(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 3) {
            sendUsage(player.getBukkit());
            return;
        }
        SkillTreeService.SkillTreeEdgeDisplayMode edgeDisplayMode =
                SkillTreeService.SkillTreeEdgeDisplayMode.fromCommandValue(args[2]);
        if (edgeDisplayMode == null) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5845.getId()));
            return;
        }
        service.updateEdgeDisplayMode(player.getBukkit(), edgeDisplayMode);
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5843.getId(), edgeDisplayMode.commandValue()));
    }

    private void handleResetOption(@NotNull AstPlayer player) {
        service.resetViewOptions(player.getBukkit());
        sendSuccess(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5846.getId()));
    }

    private int parseInt(@NotNull String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
