package io.github.maaasu.astralRecord.feature.world.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/** {@code /world tp} の短縮コマンドです。TEMPワールドは {@code [temp]worldName} で指定できます。 */
public final class WorldTeleportCommand extends AstCommand {
    private final WorldCommand worldCommand;

    public WorldTeleportCommand(@NotNull WorldCommand worldCommand) {
        super("wtp", "管理対象ワールドへテレポートします。", "/wtp <worldId|[temp]worldName>", true, UserPermission.ADMIN.getValue());
        this.worldCommand = worldCommand;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        worldCommand.teleport(player, args);
    }
}
