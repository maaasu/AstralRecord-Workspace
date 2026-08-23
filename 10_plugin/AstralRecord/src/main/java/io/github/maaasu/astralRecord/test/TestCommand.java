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
    private static final String TEST_SKIN_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTc4NTU4MTYyODk0NSwKICAicHJvZmlsZUlkIiA6ICJjOWZkZjFiMThhZGI0MGE3OTQwMzMyMTg3NThhZDJkMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJfc3BhY2VfODAiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjc3YmYzYTlkM2I0N2YxY2JmMGEyZjE5NmQ4ZTgxNzMzZDlhNzVmZDdiYzNhZDdkYzNkNzVmMzhiYWU0YjdlYiIKICAgIH0KICB9Cn0=";
    private static final String TEST_SKIN_SIGNATURE = "Y5ny2c6z54Etng/c7IXLqxdnyJxpNE9Irj2o7rFMm8hbyJUYf4qlwNfW7MIlJq6fJ+MPoES2ftA+ahSrissBz0g1qvjsbeq/Q7sBYqrS2bWOtM54hGvs+SztB96IarEUfnrDDF8LzxR0ITK6fkN04LSngC+0Y1SXmIeoXXqCMupWDV51VFbXWNnzeg3RqswDFOMiQpDwOBSmgxGFUWBb8o9bK1nEqhhVXjF6c4UnE3aUKP1LhEtiikAHoitfZnueyhhmGgFkVsnqmGLFGYrY3VHxJ0T7wuPy7EDBezOLRWZVgmJNNfsOmYEEBe8kzbjTmXzBHrqevvhXzT9GJw7raLkBOSvTNbo4RtsioXJiqYNY18n15tEBUFGMHMqM1h3dzWIFAbZ0nrSx1o7+WvAQ5jp2jcwLYZSJo0VNKf5hBSKghjAi2XtnIv2h+cNk28xjDf1tW8sYAV0+ytbf/rxqhAw/c0gvZnql/nLENVwccDeazIjVx1PwkYjseJzejmEEwgQ9R3kbJpWhL98/jkuI7nN0QYHHZsIGplW93MuhXIYqCM5VZtz09E1pwhYSNYSKxhb15JYpQEtaYYpcJrdgr3mVr1FzPl26u4Si1pkMEA4KC6xK8pV9Sf5Rdp9gYFrNPeL6n+ggVCBtYVPCdhvCdPITJGRe93S3233tRfk6ZiE=";
    private static final MobSkin TEST_SKIN = new MobSkin(TEST_SKIN_TEXTURE, TEST_SKIN_SIGNATURE);

    private final MobService mobService;

    public TestCommand(@NotNull MobService mobService) {
        super("test", "固定のプレイヤースキンを一時表示します。", "/test",
                true, UserPermission.ADMIN.getValue());
        this.mobService = mobService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        boolean displayed = mobService.showTemporaryPlayerSkin(
                player.getBukkit(),
                TEST_SKIN,
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
