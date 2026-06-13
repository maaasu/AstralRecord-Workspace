package io.github.maaasu.astralRecord.feature.gathering.command;

import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class GatheringTabCompleter extends AstTabCompleter {
    private static final List<String> ROOT = List.of("load", "list", "spawn", "spawner");
    private static final List<String> SPAWNER = List.of("reload", "list", "item");

    private final GatheringService gatheringService;
    private final GatheringSpawnerService spawnerService;

    public GatheringTabCompleter(@NotNull GatheringService gatheringService, @NotNull GatheringSpawnerService spawnerService) {
        this.gatheringService = gatheringService;
        this.spawnerService = spawnerService;
    }

    @Override
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1) {
            return ROOT;
        }
        if (args.length == 2 && "spawn".equalsIgnoreCase(args[0])) {
            return new ArrayList<>(gatheringService.getLoadedGatheringIds());
        }
        if (args.length == 2 && "spawner".equalsIgnoreCase(args[0])) {
            return SPAWNER;
        }
        if (args.length == 3 && "spawner".equalsIgnoreCase(args[0]) && "item".equalsIgnoreCase(args[1])) {
            return new ArrayList<>(spawnerService.getLoadedSpawnerIds());
        }
        return List.of();
    }
}
