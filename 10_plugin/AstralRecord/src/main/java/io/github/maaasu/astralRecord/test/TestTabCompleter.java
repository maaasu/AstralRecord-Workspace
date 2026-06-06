package io.github.maaasu.astralRecord.test;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * /test の引数補完。
 */
public final class TestTabCompleter extends AstTabCompleter {

    public TestTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        List<String> materials = completeAtPosition(
                args,
                0,
                Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .map(material -> material.name().toLowerCase())
                        .sorted()
                        .toList()
        );
        if (!materials.isEmpty()) {
            return materials;
        }

        List<String> seconds = completeAtPosition(args, 1, List.of("3", "5", "10", "30", "60"));
        if (!seconds.isEmpty()) {
            return seconds;
        }

        return List.of();
    }
}
