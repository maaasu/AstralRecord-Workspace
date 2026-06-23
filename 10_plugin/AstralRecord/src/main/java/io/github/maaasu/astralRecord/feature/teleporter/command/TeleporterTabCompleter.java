package io.github.maaasu.astralRecord.feature.teleporter.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /teleporter のタブ補完です。
 */
public final class TeleporterTabCompleter extends AstTabCompleter {
    private final TeleporterService teleporterService;

    public TeleporterTabCompleter(@NotNull TeleporterService teleporterService) {
        super(true);
        this.teleporterService = teleporterService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!player.hasPermissionLevel(99)) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("set", "remove", "list", "reload");
        }
        if (args.length == 3 && "set".equalsIgnoreCase(args[0])) {
            return List.of("true", "false");
        }
        if (args.length == 2 && "remove".equalsIgnoreCase(args[0])) {
            return teleporterService.getAll().stream().map(WaystoneDefinition::id).toList();
        }
        return List.of();
    }
}
