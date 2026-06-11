package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /mob コマンドのタブ補完です。
 */
public class MobTabCompleter extends AstTabCompleter {

    private final MobService mobService;
    private final MobSpawnerService spawnerService;

    /**
     * MobTabCompleter を初期化します。
     *
     * @param mobService Mob サービス
     * @param spawnerService Mob スポナーサービス
     */
    public MobTabCompleter(@NotNull MobService mobService, @NotNull MobSpawnerService spawnerService) {
        super(true);
        this.mobService = mobService;
        this.spawnerService = spawnerService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("load", "list", "spawn", "delete", "spawner", "npc");
        }
        if (args.length == 2 && "spawn".equalsIgnoreCase(args[0])) {
            return List.copyOf(mobService.getLoadedMobIds());
        }
        if (args.length == 2 && "delete".equalsIgnoreCase(args[0])) {
            List<String> completions = new ArrayList<>(mobService.getLoadedMobIds());
            mobService.getInstanceIds().forEach(id -> completions.add(id.toString()));
            return completions;
        }
        if (args.length == 2 && "spawner".equalsIgnoreCase(args[0])) {
            return List.of("item", "list", "reload");
        }
        if (args.length == 3 && "spawner".equalsIgnoreCase(args[0]) && "item".equalsIgnoreCase(args[1])) {
            return List.copyOf(spawnerService.getLoadedSpawnerIds());
        }
        if (args.length == 2 && "npc".equalsIgnoreCase(args[0])) {
            return List.of("place", "list", "reload");
        }
        if (args.length == 3 && "npc".equalsIgnoreCase(args[0]) && "place".equalsIgnoreCase(args[1])) {
            return List.copyOf(mobService.getLoadedMobIds());
        }
        return List.of();
    }
}
