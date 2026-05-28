package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.mob.service.MobService;
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

    /**
     * MobTabCompleter を初期化します。
     *
     * @param mobService Mob サービス
     */
    public MobTabCompleter(@NotNull MobService mobService) {
        super(true);
        this.mobService = mobService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("load", "list", "spawn", "delete");
        }
        if (args.length == 2 && "spawn".equalsIgnoreCase(args[0])) {
            return List.copyOf(mobService.getLoadedMobIds());
        }
        if (args.length == 2 && "delete".equalsIgnoreCase(args[0])) {
            List<String> completions = new ArrayList<>(mobService.getLoadedMobIds());
            mobService.getInstanceIds().forEach(id -> completions.add(id.toString()));
            return completions;
        }
        return List.of();
    }
}
