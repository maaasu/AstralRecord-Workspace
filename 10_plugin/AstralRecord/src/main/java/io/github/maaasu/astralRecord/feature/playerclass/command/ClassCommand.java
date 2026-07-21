package io.github.maaasu.astralRecord.feature.playerclass.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ADMIN 向けにプレイヤーの選択クラスを変更するコマンドです。
 */
public final class ClassCommand extends AstCommand {

    public ClassCommand() {
        super(
            "class",
            "プレイヤーのクラスを変更・設定します。",
            "/class [change <classId> [player]|level <classId> <level|+delta|-delta> [player]]",
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
        if (args.length == 0) {
            openGui(sender);
            return;
        }
        if (args[0].equalsIgnoreCase("change")) {
            changeClass(sender, args);
            return;
        }
        if (args[0].equalsIgnoreCase("level")) {
            setClassLevel(sender, args);
            return;
        }
        sendUsage(sender);
    }

    private void changeClass(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        PlayerClassService classService = AstralRecord.getInstance().getPlayerClassService();
        if (classService == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5813.getId(), args[1].trim()));
            return;
        }

        String classInput = args[1].trim();
        String resolvedClassId = classService.resolveLoadedClassId(classInput);
        if (resolvedClassId == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5813.getId(), classInput));
            return;
        }

        AstPlayer target = resolveTarget(sender, args.length >= 3 ? args[2] : null);
        if (target == null) {
            return;
        }

        String oldClassId = target.getClassId();
        String newClassId = resolvedClassId;
        classService.changeClass(target, newClassId);
        String oldDisplayName = classService.getDisplayName(oldClassId);
        String newDisplayName = classService.getDisplayName(newClassId);
        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_5812.getId(), oldDisplayName, newDisplayName));
        if (sender != target.getBukkit()) {
            PlayerMessageService.getInstance().send(target, PlayerMsgId.P_5812, oldDisplayName, newDisplayName);
        }
        AstralRecord.getInstance().getStatusService().refreshStatus(target);
    }

    private void setClassLevel(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }

        PlayerClassService classService = AstralRecord.getInstance().getPlayerClassService();
        if (classService == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5813.getId(), args[1].trim()));
            return;
        }

        String classInput = args[1].trim();
        String classId = classService.resolveLoadedClassId(classInput);
        if (classId == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5813.getId(), classInput));
            return;
        }

        AstPlayer target = resolveTarget(sender, args.length >= 4 ? args[3] : null);
        if (target == null) {
            return;
        }

        Long requestedLevel = parseRequestedLevel(args[2], target.getClassProgress(classId).getLevel());
        if (requestedLevel == null) {
            sendUsage(sender);
            return;
        }

        var result = classService.setClassLevel(target, classId, requestedLevel);
        if (result == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5813.getId(), classInput));
            return;
        }

        String classDisplayName = classService.getDisplayName(classId);
        sendSuccess(
            sender,
            PlayerMsgResource.format(
                PlayerMsgId.P_5850.getId(),
                classDisplayName,
                result.getPreviousLevel(),
                result.getCurrentLevel(),
                result.getMaxLevel()
            )
        );
        if (sender != target.getBukkit()) {
            PlayerMessageService.getInstance().send(
                target,
                PlayerMsgId.P_5850,
                classDisplayName,
                result.getPreviousLevel(),
                result.getCurrentLevel(),
                result.getMaxLevel()
            );
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

    private void openGui(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        PlayerClassService classService = AstralRecord.getInstance().getPlayerClassService();
        if (astPlayer == null || classService == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), player.getName()));
            return;
        }
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5065);
            return;
        }
        AstralRecord.getInstance().getMenuView().openClass(player, astPlayer, classService.getClassViewEntries(astPlayer));
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
