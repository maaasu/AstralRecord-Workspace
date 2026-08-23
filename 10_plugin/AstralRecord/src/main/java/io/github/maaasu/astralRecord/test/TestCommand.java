package io.github.maaasu.astralRecord.test;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkin;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * 指定されたプレイヤースキンを仮想 Player として一時表示するテストコマンド。
 */
public final class TestCommand extends AstCommand {
    private static final long DISPLAY_DURATION_TICKS = 10L * 20L;

    private final MobService mobService;

    public TestCommand(@NotNull MobService mobService) {
        super("test", "指定したプレイヤースキンを一時表示します。", "/test <texture> <signature>",
                true, UserPermission.ADMIN.getValue());
        this.mobService = mobService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }

        MobSkin skin = new MobSkin(args[0], args[1]);

        boolean displayed = mobService.showTemporaryPlayerSkin(
                player.getBukkit(),
                skin,
                displayBaseLocation(player.getBukkit()),
                DISPLAY_DURATION_TICKS
        );
        if (!displayed) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5050.getId()));
            return;
        }
        sendSuccess(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5051.getId()));
    }

    private @NotNull Location displayBaseLocation(@NotNull Player player) {
        Vector direction = player.getLocation().getDirection().normalize().multiply(2.0D);
        return player.getLocation().clone().add(direction).add(0.0D, 0.2D, 0.0D);
    }
}
