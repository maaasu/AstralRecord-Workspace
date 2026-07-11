package io.github.maaasu.astralRecord.feature.boss.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * Admin command for active boss challenges.
 */
public final class BossCommand extends AstCommand {
    public BossCommand() {
        super("boss", "Manage boss challenges.", "/boss <instances|stop> [partyId|challengeId]",
                false, UserPermission.ADMIN.getValue());
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
        switch (action) {
            case "instances", "list" -> handleList(sender, service);
            case "stop" -> handleStop(sender, service, args);
            default -> sendUsage(sender);
        }
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
