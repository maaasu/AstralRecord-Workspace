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
    private final boolean shortcut;

    /**
     * `/player info` コマンドを生成します。
     */
    public PlayerInfoCommand() {
        this("player", "/player info [<playerName>]", false);
    }

    /**
     * プレイヤー情報コマンドを指定形式で生成します。
     *
     * @param commandName コマンド名
     * @param usage 使用方法
     * @param shortcut `/player info` を省略した短縮コマンドかどうか
     */
    public PlayerInfoCommand(
        @NotNull String commandName,
        @NotNull String usage,
        boolean shortcut
    ) {
        super(commandName, "プレイヤー情報GUIを開きます。", usage, true);
        this.shortcut = shortcut;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        int targetIndex;
        if (shortcut) {
            if (args.length > 1) {
                sendUsage(player.getBukkit());
                return;
            }
            targetIndex = 0;
        } else {
            if (!checkArgsLength(args, 1, player.getBukkit())) {
                return;
            }
            if (!"info".equalsIgnoreCase(args[0])) {
                sendUsage(player.getBukkit());
                return;
            }
            targetIndex = 1;
        }

        Player target = targetIndex < args.length
            ? Bukkit.getPlayerExact(args[targetIndex])
            : player.getBukkit();
        if (target == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5603, args[targetIndex]);
            return;
        }

        PlayerBrowserGuiEventHandler handler = AstralRecord.getInstance().getPlayerBrowserGuiEventHandler();
        if (handler == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5603, target.getName());
            return;
        }
        handler.openDetailFromCommand(player.getBukkit(), target);
    }
}
