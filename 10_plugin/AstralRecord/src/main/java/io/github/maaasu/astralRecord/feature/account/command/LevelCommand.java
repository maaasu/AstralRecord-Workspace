package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountLevelSetResult;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ADMIN 向けにプレイヤーレベルを変更するコマンドです。
 */
public final class LevelCommand extends AstCommand {

    public LevelCommand() {
        super(
            "level",
            "プレイヤーレベルを設定します。",
            "/level <level|+delta|-delta> [player]",
            false,
            UserPermission.ADMIN.getValue()
        );
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
            return;
        }
        if (args.length < 1 || args.length > 2) {
            sendUsage(sender);
            return;
        }

        AstPlayer target = resolveTarget(sender, args.length == 2 ? args[1] : null);
        if (target == null) {
            return;
        }

        Long requestedLevel = parseRequestedLevel(args[0], target.getAccount().getLevel());
        if (requestedLevel == null) {
            sendUsage(sender);
            return;
        }

        AccountService accountService = AstralRecord.getInstance().getAccountService();
        if (accountService == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5853.getId()));
            return;
        }

        AccountLevelSetResult result = accountService.setPlayerLevelCached(
            target.getAccount(),
            requestedLevel,
            target.getUser().getUuid()
        );
        target.setAccount(result.updatedAccount());
        sendSuccess(
            sender,
            PlayerMsgResource.format(
                PlayerMsgId.P_5852.getId(),
                result.previousLevel(),
                result.currentLevel(),
                result.maxLevel()
            )
        );
        if (sender != target.getBukkit()) {
            PlayerMessageService.getInstance().send(
                target,
                PlayerMsgId.P_5852,
                result.previousLevel(),
                result.currentLevel(),
                result.maxLevel()
            );
        }

        var skillTreeService = AstralRecord.getInstance().getSkillTreeService();
        if (skillTreeService != null) {
            skillTreeService.refreshProgressDerivedState(target);
        }
        AstralRecord.getInstance().getStatusService().refreshStatus(target);
    }

    private boolean hasAdminPermission(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.hasAdminPermission();
    }

    private @Nullable AstPlayer resolveTarget(@NotNull CommandSender sender, @Nullable String targetName) {
        if (targetName != null) {
            Player player = Bukkit.getPlayerExact(targetName);
            AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
            if (astPlayer == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), targetName));
            }
            return astPlayer;
        }
        if (sender instanceof Player player) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), player.getName()));
            }
            return astPlayer;
        }
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
        return null;
    }

    private @Nullable Long parseRequestedLevel(@NotNull String input, int currentLevel) {
        try {
            if (input.startsWith("+") || input.startsWith("-")) {
                return Math.addExact((long) currentLevel, Long.parseLong(input));
            }
            return Long.parseLong(input);
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null;
        }
    }
}
