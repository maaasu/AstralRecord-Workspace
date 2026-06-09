package io.github.maaasu.astralRecord.feature.menu.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /player info でプレイヤー情報 GUI を開くコマンドです。
 */
public final class PlayerInfoCommand extends AstCommand {

    public PlayerInfoCommand() {
        super("player", "Open player info GUI.", "/player info <playerName>", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, player.getBukkit())) {
            return;
        }
        if (!"info".equalsIgnoreCase(args[0])) {
            sendUsage(player.getBukkit());
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5603, args[1]);
            return;
        }

        PlayerBrowserGuiEventHandler handler = AstralRecord.getInstance().getPlayerBrowserGuiEventHandler();
        if (handler == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5603, args[1]);
            return;
        }
        handler.openDetailFromCommand(player.getBukkit(), target);
    }
}
