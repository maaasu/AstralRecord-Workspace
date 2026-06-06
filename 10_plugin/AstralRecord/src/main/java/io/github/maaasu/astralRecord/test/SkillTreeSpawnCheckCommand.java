package io.github.maaasu.astralRecord.test;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * skilltree 用 Display entity の spawn 状態を確認するテストコマンドです。
 */
public final class SkillTreeSpawnCheckCommand extends AstCommand {
    private static final String DEFAULT_WORLD_HINT = "skill_tree";
    private static final String TAG_SKILLTREE = "astralrecord:skilltree";
    private static final String TAG_SKILLTREE_ADMIN = "astralrecord:skilltree:admin";
    private static final String TAG_SKILLTREE_NODE = "astralrecord:skilltree:node";
    private static final String TAG_SKILLTREE_EDGE = "astralrecord:skilltree:edge";

    public SkillTreeSpawnCheckCommand() {
        super("testskilltree", "Inspect spawned skilltree display entities.", "/testskilltree [world]");
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        inspect(player.getBukkit(), args);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        inspect(sender, args);
    }

    private void inspect(@NotNull CommandSender sender, @NotNull String[] args) {
        String worldHint = args.length >= 1 ? args[0] : DEFAULT_WORLD_HINT;
        World world = resolveWorld(worldHint);
        if (world == null) {
            sendError(sender, "world not found: " + worldHint);
            return;
        }

        int totalEntities = 0;
        int totalDisplays = 0;
        int totalItemDisplays = 0;
        int totalTextDisplays = 0;
        int skillTreeDisplays = 0;
        int adminDisplays = 0;
        int nodeDisplays = 0;
        int edgeDisplays = 0;

        for (Entity entity : world.getEntities()) {
            totalEntities++;
            if (!(entity instanceof Display)) {
                continue;
            }
            totalDisplays++;
            if (entity instanceof ItemDisplay) {
                totalItemDisplays++;
            }
            if (entity instanceof TextDisplay) {
                totalTextDisplays++;
            }
            if (!entity.getScoreboardTags().contains(TAG_SKILLTREE)) {
                continue;
            }
            skillTreeDisplays++;
            if (entity.getScoreboardTags().contains(TAG_SKILLTREE_ADMIN)) {
                adminDisplays++;
            }
            if (entity.getScoreboardTags().contains(TAG_SKILLTREE_NODE)) {
                nodeDisplays++;
            }
            if (entity.getScoreboardTags().contains(TAG_SKILLTREE_EDGE)) {
                edgeDisplays++;
            }
        }

        sendInfo(sender, "world=" + world.getName());
        sendInfo(sender, "entities=" + totalEntities + ", displays=" + totalDisplays + ", itemDisplays=" + totalItemDisplays + ", textDisplays=" + totalTextDisplays);
        sendInfo(sender, "skilltreeDisplays=" + skillTreeDisplays + ", admin=" + adminDisplays + ", node=" + nodeDisplays + ", edge=" + edgeDisplays);
    }

    @Nullable
    private World resolveWorld(@NotNull String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            return world;
        }

        world = Bukkit.getWorld(new File(worldName).getName());
        if (world != null) {
            return world;
        }

        String normalized = worldName.replace('\\', '/');
        for (World candidate : Bukkit.getWorlds()) {
            String candidateName = candidate.getName().replace('\\', '/');
            String folderPath = candidate.getWorldFolder().getPath().replace('\\', '/');
            if (candidateName.endsWith(normalized) || folderPath.endsWith(normalized)) {
                return candidate;
            }
        }
        return null;
    }
}
