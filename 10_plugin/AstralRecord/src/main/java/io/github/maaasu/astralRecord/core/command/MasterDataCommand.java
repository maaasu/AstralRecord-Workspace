package io.github.maaasu.astralRecord.core.command;

import io.github.maaasu.astralRecord.AstralRecord;
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
        reload.completion().whenComplete((loaded, throwable) -> {
            Throwable cause = unwrapCompletionFailure(throwable);
            if (cause != null) {
                Logger.log(LogId.E_1551, cause, failureMessage(cause));
            } else {
                Logger.log(LogId.I_1555);
            }
            try {
                AsyncTaskUtil.runSync(plugin, () -> notifyCompletion(sender, playerId, loaded, cause));
            } catch (RuntimeException schedulingFailure) {
                Logger.log(LogId.E_1551, schedulingFailure, failureMessage(schedulingFailure));
            }
        });
    }

    private void notifyCompletion(
        @NotNull CommandSender originalSender,
        UUID playerId,
        Integer loaded,
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
        sendSuccess(currentSender, PlayerMsgResource.format(PlayerMsgId.P_5097.getId(), loaded));
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
