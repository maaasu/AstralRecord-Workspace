package io.github.maaasu.astralRecord.feature.boss.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * ボス挑戦の管理者操作と、パーティーリーダー向け中止操作を提供します。
 */
public final class BossCommand extends AstCommand {
    public BossCommand() {
        super("boss", "ボス挑戦を管理します。", "/boss <instances|stop|cancel> [partyId|challengeId]",
                false);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        BossChallengeService service = AstralRecord.getInstance() == null
                ? null
                : AstralRecord.getInstance().getBossChallengeService();
        if (service == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6520.getId()));
            return;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("cancel")) {
            handlePlayerCancel(sender, service);
            return;
        }
        if (!isAdmin(sender)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
            return;
        }
        switch (action) {
            case "instances", "list" -> handleList(sender, service);
            case "stop" -> handleStop(sender, service, args);
            default -> sendUsage(sender);
        }
    }

    private void handlePlayerCancel(
            @NotNull CommandSender sender,
            @NotNull BossChallengeService service
    ) {
        if (!(sender instanceof Player player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5060.getId()));
            return;
        }
        BossChallengeService.PlayerCancelResult result = service.stopChallengeForLeader(
                player.getUniqueId(),
                null
        );
        switch (result) {
            case STOPPED -> sendSuccess(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6528.getId()));
            case NOT_LEADER -> sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6526.getId()));
            case NO_CHALLENGE -> sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6527.getId()));
        }
    }

    private boolean isAdmin(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        var astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.hasPermissionLevel(UserPermission.ADMIN.getValue());
    }

    private void handleList(@NotNull CommandSender sender, @NotNull BossChallengeService service) {
        List<String> active = service.describeActive();
        if (active.isEmpty()) {
            sendInfo(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6517.getId()));
            return;
        }
        sendInfo(sender, PlayerMsgResource.format(PlayerMsgId.P_6515.getId(), active.size()));
        for (String line : active) {
            sendInfo(sender, PlayerMsgResource.format(PlayerMsgId.P_6516.getId(), line));
        }
    }

    private void handleStop(@NotNull CommandSender sender, @NotNull BossChallengeService service, @NotNull String[] args) {
        if (!checkArgsLength(args, 2, sender)) {
            return;
        }
        if (!service.stopChallenge(args[1])) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_6519.getId(), args[1]));
            return;
        }
        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_6518.getId(), args[1]));
    }
}
