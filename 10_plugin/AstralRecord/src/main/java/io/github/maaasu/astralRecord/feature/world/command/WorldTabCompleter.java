package io.github.maaasu.astralRecord.feature.world.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /world コマンドのタブ補完です。
 */
public class WorldTabCompleter extends AstTabCompleter {

    private final WorldService worldService;

    /**
     * WorldTabCompleter を初期化します。
     *
     * @param worldService WorldMasterData サービス
     */
    public WorldTabCompleter(@NotNull WorldService worldService) {
        super(true);
        this.worldService = worldService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("list", "info", "tp", "loaded", "reload");
        }
        if (args.length == 2 && ("info".equalsIgnoreCase(args[0]) || "tp".equalsIgnoreCase(args[0]))) {
            return worldService.getAll().stream().map(WorldMasterData::id).toList();
        }
        return List.of();
    }
}
