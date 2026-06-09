package io.github.maaasu.astralRecord.feature.player.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /message コマンドで管理ダイレクトメッセージを送信する。
 */
public final class DirectMessageCommand extends AstCommand {

    /**
     * DirectMessageCommand を初期化する。
     */
    public DirectMessageCommand() {
        super("message", "Send a managed direct message.", "/message <player> <message>", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5905, args[0]);
            return;
        }
        if (target.getUniqueId().equals(player.getBukkit().getUniqueId())) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5947);
            return;
        }

        String message = joinArgs(args, 1).trim();
        if (message.isBlank()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5946);
            return;
        }

        PlayerMessageService.getInstance().sendDirectMessage(player.getBukkit(), target, message);
    }
}
