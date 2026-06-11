package io.github.maaasu.astralRecord.feature.skilltree.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /skilltree のタブ補完です。
 */
public class SkillTreeTabCompleter extends AstTabCompleter {
    private final SkillTreeService service;

    public SkillTreeTabCompleter(@NotNull SkillTreeService service) {
        super(true);
        this.service = service;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        boolean adminMode = service.isAdminMode(player);
        if (args.length == 1) {
            return adminMode
                    ? List.of("back", "reload", "position-item", "connector-item", "points", "option")
                    : List.of("back", "option");
        }
        if (!adminMode && isAdminSubCommand(args[0])) {
            return List.of();
        }
        if (args.length == 2 && "position-item".equalsIgnoreCase(args[0])) {
            return service.getDefinedPositionIds().stream().sorted().toList();
        }
        if (args.length == 2 && "points".equalsIgnoreCase(args[0])) {
            return List.of("set", "add");
        }
        if (args.length == 2 && "option".equalsIgnoreCase(args[0])) {
            return List.of("view-distance", "edge-display", "reset");
        }
        if (args.length == 3 && "option".equalsIgnoreCase(args[0]) && "edge-display".equalsIgnoreCase(args[1])) {
            return java.util.Arrays.stream(SkillTreeService.SkillTreeEdgeDisplayMode.values())
                    .map(SkillTreeService.SkillTreeEdgeDisplayMode::commandValue)
                    .toList();
        }
        if (args.length == 3 && "option".equalsIgnoreCase(args[0]) && "view-distance".equalsIgnoreCase(args[1])) {
            return List.of(
                    String.valueOf(service.minViewDistance()),
                    String.valueOf(service.viewOptions(player.getBukkit()).viewDistance()),
                    String.valueOf(service.maxViewDistance())
            );
        }
        return List.of();
    }

    private boolean isAdminSubCommand(@NotNull String subCommand) {
        return "reload".equalsIgnoreCase(subCommand)
                || "position-item".equalsIgnoreCase(subCommand)
                || "connector-item".equalsIgnoreCase(subCommand)
                || "points".equalsIgnoreCase(subCommand);
    }
}
