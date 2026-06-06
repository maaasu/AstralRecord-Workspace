package io.github.maaasu.astralRecord.test;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /testskilltree の補完です。
 */
public final class SkillTreeSpawnCheckTabCompleter extends AstTabCompleter {

    public SkillTreeSpawnCheckTabCompleter() {
        super(true);
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        List<String> worldNames = new ArrayList<>();
        worldNames.add("skill_tree");
        Bukkit.getWorlds().forEach(world -> worldNames.add(world.getName()));
        return completeAtPosition(args, 0, worldNames.stream().distinct().sorted().toList());
    }
}
