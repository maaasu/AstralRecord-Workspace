package io.github.maaasu.astralRecord.feature.inventory.command;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

public class InventoryTabCompleter extends AstTabCompleter {
    public InventoryTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return InventoryType.commandSwitchableEntries().stream()
                .map(type -> type.toString().toLowerCase())
                .collect(Collectors.toList());
        }
        return List.of();
    }
}
