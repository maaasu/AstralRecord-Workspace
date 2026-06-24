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
        super("class", "Change player class.", "/class gui|change <classId> [player]", false, UserPermission.ADMIN.getValue());
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!hasAdminPermission(sender)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
            return;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("gui")) {
            openGui(sender);
            return;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("change")) {
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

        AstPlayer target = resolveTarget(sender, args);
        if (target == null) {
            return;
        }

        String oldClassId = target.getClassId();
        String newClassId = resolvedClassId;
        target.setClassId(newClassId);
        target.setClassLevel(Math.max(1, target.getClassLevel()));
        String oldDisplayName = classService.getDisplayName(oldClassId);
        String newDisplayName = classService.getDisplayName(newClassId);
        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_5812.getId(), oldDisplayName, newDisplayName));
        if (sender != target.getBukkit()) {
            PlayerMessageService.getInstance().send(target, PlayerMsgId.P_5812, oldDisplayName, newDisplayName);
        }
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

    private @Nullable AstPlayer resolveTarget(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length >= 3) {
            Player player = Bukkit.getPlayerExact(args[2]);
            AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
            if (astPlayer == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), args[2]));
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
}
