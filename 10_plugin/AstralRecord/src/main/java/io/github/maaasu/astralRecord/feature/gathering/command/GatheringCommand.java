package io.github.maaasu.astralRecord.feature.gathering.command;

import io.github.maaasu.astralRecord.feature.gathering.model.GatheringInstance;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public class GatheringCommand extends AstCommand {
    private final GatheringService gatheringService;
    private final GatheringSpawnerService spawnerService;

    public GatheringCommand(@NotNull GatheringService gatheringService, @NotNull GatheringSpawnerService spawnerService) {
        super("gathering", "Manage gathering objects.", "/gathering <load|list|spawn|spawner>",
                true, UserPermission.ADMIN.getValue());
        this.gatheringService = gatheringService;
        this.spawnerService = spawnerService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "load" -> {
                int gatheringCount = gatheringService.loadAll();
                int spawnerCount = spawnerService.loadAll();
                sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5720.getId(), gatheringCount, spawnerCount));
            }
            case "list" -> sendInfo(player.getBukkit(), PlayerMsgResource.format(
                    PlayerMsgId.P_5721.getId(),
                    String.join(", ", gatheringService.getLoadedGatheringIds()),
                    gatheringService.getInstances().size()
            ));
            case "spawn" -> handleSpawn(player, args);
            case "spawner" -> handleSpawner(player, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleSpawn(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }
        GatheringInstance instance = gatheringService.spawn(args[1], player.getBukkit().getLocation());
        if (instance == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5722.getId(), args[1]));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(
                PlayerMsgId.P_5723.getId(),
                instance.definition().id(),
                instance.instanceId()
        ));
    }

    private void handleSpawner(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                gatheringService.clearInstances();
                sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5724.getId(), spawnerService.loadAll()));
            }
            case "list" -> sendInfo(player.getBukkit(), PlayerMsgResource.format(
                    PlayerMsgId.P_5725.getId(),
                    String.join(", ", spawnerService.getLoadedSpawnerIds()),
                    spawnerService.getLocations().size()
            ));
            case "item" -> handleSpawnerItem(player, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleSpawnerItem(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!spawnerService.isAdminMode(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5719.getId()));
            return;
        }
        if (args.length < 3) {
            sendUsage(player.getBukkit());
            return;
        }

        int amount = args.length >= 4 ? parseAmount(args[3]) : 1;
        ItemStack itemStack = spawnerService.createSpawnerItem(args[2], amount);
        if (itemStack == null) {
            sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5708.getId(), args[2]));
            return;
        }
        if (!canFitItem(player.getBukkit().getInventory(), itemStack)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5241.getId()));
            return;
        }
        var leftover = player.getBukkit().getInventory().addItem(itemStack);
        if (!leftover.isEmpty()) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5241.getId()));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5714.getId(), args[2], amount));
    }

    private int parseAmount(@NotNull String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private boolean canFitItem(@NotNull Inventory inventory, @NotNull ItemStack itemStack) {
        int remaining = itemStack.getAmount();
        int maxStackSize = Math.min(inventory.getMaxStackSize(), itemStack.getMaxStackSize());
        for (ItemStack current : inventory.getStorageContents()) {
            if (current == null || current.getType().isAir()) {
                remaining -= maxStackSize;
            } else if (current.isSimilar(itemStack)) {
                remaining -= Math.max(0, maxStackSize - current.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }
}
