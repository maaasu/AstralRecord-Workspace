package io.github.maaasu.astralRecord.feature.gathering.command;

import io.github.maaasu.astralRecord.feature.gathering.model.GatheringInstance;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
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
                sendSuccess(player.getBukkit(), "Gathering loaded: " + gatheringCount + " objects / " + spawnerCount + " spawners");
            }
            case "list" -> sendInfo(player.getBukkit(), "Gathering: "
                    + String.join(", ", gatheringService.getLoadedGatheringIds())
                    + " / active=" + gatheringService.getInstances().size());
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
            sendError(player.getBukkit(), "Gathering definition not found: " + args[1]);
            return;
        }
        sendSuccess(player.getBukkit(), "Gathering spawned: " + instance.definition().id() + " / " + instance.instanceId());
    }

    private void handleSpawner(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reload" -> sendSuccess(player.getBukkit(), "Gathering spawners loaded: " + spawnerService.loadAll());
            case "list" -> sendInfo(player.getBukkit(), "Gathering spawners: "
                    + String.join(", ", spawnerService.getLoadedSpawnerIds())
                    + " / placed=" + spawnerService.getLocations().size());
            case "item" -> handleSpawnerItem(player, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleSpawnerItem(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!spawnerService.isAdminMode(player)) {
            sendError(player.getBukkit(), "Admin mode is required.");
            return;
        }
        if (args.length < 3) {
            sendUsage(player.getBukkit());
            return;
        }

        int amount = args.length >= 4 ? parseAmount(args[3]) : 1;
        ItemStack itemStack = spawnerService.createSpawnerItem(args[2], amount);
        if (itemStack == null) {
            sendError(player.getBukkit(), "Gathering spawner definition not found: " + args[2]);
            return;
        }
        var leftover = player.getBukkit().getInventory().addItem(itemStack);
        if (!leftover.isEmpty()) {
            sendError(player.getBukkit(), "Inventory is full.");
            return;
        }
        sendSuccess(player.getBukkit(), "Gathering spawner item granted: " + args[2] + " x" + amount);
    }

    private int parseAmount(@NotNull String value) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }
}
