package io.github.maaasu.astralRecord.core.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.masterdata.MasterDataReloadNotification;
import io.github.maaasu.astralRecord.feature.masterdata.MasterDataReloadResult;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Administrative master-data operations that do not require a plugin restart. */
public final class MasterDataCommand extends AstCommand {
    private static final int REQUIRED_PERMISSION = 99;
    private final AstralRecord plugin;

    public MasterDataCommand(AstralRecord plugin) {
        super("masterdata", "AstralRecord のマスターデータを再読込します。", "/masterdata reload", false, REQUIRED_PERMISSION);
        this.plugin = plugin;
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length != 1 || !"reload".equalsIgnoreCase(args[0])) {
            sendUsage(sender);
            return;
        }

        AstralRecord.MasterDataReloadStart reload = plugin.reloadMasterData();
        if (!reload.started()) {
            sendWarning(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5096.getId()));
            return;
        }

        Logger.log(LogId.I_1554, sender.getName());
        sendInfo(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5095.getId()));
        UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        reload.completion().whenComplete((result, throwable) -> {
            Throwable cause = unwrapCompletionFailure(throwable);
            if (cause != null) {
                Logger.log(LogId.E_1551, cause, failureMessage(cause));
            } else {
                Logger.log(LogId.I_1555);
            }
            try {
                AsyncTaskUtil.runSync(plugin, () -> notifyCompletion(sender, playerId, result, cause));
            } catch (RuntimeException schedulingFailure) {
                Logger.log(LogId.E_1551, schedulingFailure, failureMessage(schedulingFailure));
            }
        });
    }

    /**
     * 実行者を再解決し、公開失敗または部分反映失敗を含む完了結果を通知します。
     *
     * @param originalSender 開始時の送信者
     * @param playerId プレイヤー実行者のUUID。コンソールの場合はnull
     * @param result 公開完了結果。公開失敗時はnull
     * @param failure 公開失敗の原因。完了時はnull
     */
    private void notifyCompletion(
        @NotNull CommandSender originalSender,
        UUID playerId,
        MasterDataReloadResult result,
        Throwable failure
    ) {
        CommandSender currentSender = originalSender;
        if (playerId != null) {
            Player onlinePlayer = Bukkit.getPlayer(playerId);
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                return;
            }
            currentSender = onlinePlayer;
        }
        if (failure != null) {
            sendError(currentSender, PlayerMsgResource.getMessage(PlayerMsgId.P_5098.getId()));
            return;
        }
        MasterDataReloadNotification.sendCompletion(plugin.getPlayerMessageService(), currentSender, result);
    }

    private @NotNull String failureMessage(@NotNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private Throwable unwrapCompletionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
