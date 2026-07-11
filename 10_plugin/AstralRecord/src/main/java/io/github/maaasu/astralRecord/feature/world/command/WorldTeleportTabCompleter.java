package io.github.maaasu.astralRecord.feature.world.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** {@code /wtp} のワールド ID 補完を提供します。 */
public final class WorldTeleportTabCompleter extends AstTabCompleter {
    private final WorldService worldService;

    public WorldTeleportTabCompleter(@NotNull WorldService worldService) {
        super(true);
        this.worldService = worldService;
    }

    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 1) {
            return worldService.getAll().stream().map(WorldMasterData::id).toList();
        }
        return List.of();
    }
}
