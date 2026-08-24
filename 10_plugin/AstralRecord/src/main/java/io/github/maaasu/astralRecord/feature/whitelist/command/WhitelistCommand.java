package io.github.maaasu.astralRecord.feature.whitelist.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.whitelist.service.WhitelistService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * whitelist の有効・無効を切り替える管理コマンドです。
 */
public final class WhitelistCommand extends AstCommand {
    private final WhitelistService whitelistService;

    /**
     * whitelist コマンドを初期化します。
     *
     * @param whitelistService whitelist 状態サービス
     */
    public WhitelistCommand(@NotNull WhitelistService whitelistService) {
        super("whitelist", "サーバーのwhitelistを切り替えます。", "/whitelist [true|false]", false,
            UserPermission.ADMIN.getValue());
        this.whitelistService = whitelistService;
    }

    /**
     * 引数を解釈して whitelist 状態を更新します。
     * 引数なしでは現在値を反転し、true/false 以外は usage とエラーを返します。
     *
     * @param sender コマンド実行者
     * @param args コマンド引数
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length > 1) {
            sendInvalidArgument(sender);
            return;
        }

        boolean enabled;
        if (args.length == 0) {
            enabled = !whitelistService.isEnabled();
        } else if ("true".equalsIgnoreCase(args[0])) {
            enabled = true;
        } else if ("false".equalsIgnoreCase(args[0])) {
            enabled = false;
        } else {
            sendInvalidArgument(sender);
            return;
        }

        whitelistService.setEnabled(enabled);
        sendSuccess(
            sender,
            PlayerMsgResource.getMessage(enabled ? PlayerMsgId.P_7110.getId() : PlayerMsgId.P_7111.getId())
        );
    }

    private void sendInvalidArgument(@NotNull CommandSender sender) {
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7114.getId()));
        sendUsage(sender);
    }
}
