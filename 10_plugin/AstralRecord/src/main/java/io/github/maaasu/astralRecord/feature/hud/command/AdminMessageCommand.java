package io.github.maaasu.astralRecord.feature.hud.command;

import io.github.maaasu.astralRecord.feature.hud.service.AdminMessageBossBarService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 管理者メッセージを全プレイヤーへ BossBar で表示するコマンドです。
 */
public final class AdminMessageCommand extends AstCommand {
    private static final long MAX_DURATION_SECONDS = 86_400L;

    private final AdminMessageBossBarService bossBarService;

    /**
     * 管理者メッセージコマンドを初期化します。
     *
     * @param bossBarService 管理者メッセージの表示サービス
     */
    public AdminMessageCommand(@NotNull AdminMessageBossBarService bossBarService) {
        super(
                "adminmessage",
                "管理者メッセージを全プレイヤーへ表示します。",
                "/adminmessage <seconds> <message...>",
                false,
                UserPermission.ADMIN.getValue()
        );
        this.bossBarService = bossBarService;
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        Long durationSeconds = parseDuration(sender, args[0]);
        if (durationSeconds == null) {
            return;
        }

        String message = joinArgs(args, 1).replace("\\n", "\n");
        bossBarService.show(message, durationSeconds);
        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_6910.getId(), durationSeconds));
    }

    private @Nullable Long parseDuration(@NotNull CommandSender sender, @NotNull String value) {
        final long durationSeconds;
        try {
            durationSeconds = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6912.getId()));
            return null;
        }

        if (durationSeconds < 1L || durationSeconds > MAX_DURATION_SECONDS) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_6911.getId(), MAX_DURATION_SECONDS));
            return null;
        }
        return durationSeconds;
    }
}
