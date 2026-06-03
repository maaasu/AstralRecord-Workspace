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
        if (args.length == 1) {
            return List.of("reload", "position-item", "connector-item", "points");
        }
        if (args.length == 2 && "position-item".equalsIgnoreCase(args[0])) {
            return service.getDefinedPositionIds().stream().sorted().toList();
        }
        if (args.length == 2 && "points".equalsIgnoreCase(args[0])) {
            return List.of("set", "add");
        }
        return List.of();
    }
}
