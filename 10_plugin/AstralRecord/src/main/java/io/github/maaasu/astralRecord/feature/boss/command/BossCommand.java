package io.github.maaasu.astralRecord.feature.boss.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * ボス挑戦の管理者操作と、パーティーリーダー向け中止操作を提供します。
 */
public final class BossCommand extends AstCommand {
    public BossCommand() {
        super("boss", "ボス挑戦を管理します。", "/boss <instances|list|stop|cancel|teleport> [partyId|partyKey|challengeIdPrefix|type instanceId]",
                false);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        AstralRecord plugin = AstralRecord.getInstance();
        BossChallengeService service = plugin == null
                ? null
                : plugin.getBossChallengeService();
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
            case "instances", "list" -> handleList(sender, service, plugin);
            case "stop" -> handleStop(sender, service, args);
            case "teleport" -> handleTeleport(sender, service, plugin, args);
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

    private void handleList(
            @NotNull CommandSender sender,
            @NotNull BossChallengeService service,
            AstralRecord plugin
    ) {
        List<ActiveChallengeLine> active = new ArrayList<>();
        for (BossChallengeService.AdminChallengeInfo info : service.describeActiveForAdmin()) {
            active.add(new ActiveChallengeLine(ChallengeKind.BOSS, info.challengeId(), info.description()));
        }
        DungeonService dungeonService = plugin == null ? null : plugin.getDungeonService();
        if (dungeonService != null) {
            for (DungeonService.AdminSessionInfo info : dungeonService.describeActiveForAdmin()) {
                active.add(new ActiveChallengeLine(ChallengeKind.DUNGEON, info.sessionId(), info.description()));
            }
        }
        if (active.isEmpty()) {
            sendInfo(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6538.getId()));
            return;
        }
        sendInfo(sender, PlayerMsgResource.format(PlayerMsgId.P_6536.getId(), active.size()));
        for (ActiveChallengeLine line : active) {
            String description = line.kind().displayName + " | " + line.description();
            if (sender instanceof Player player) {
                PlayerMessageService.getInstance().sendClickable(
                        player,
                        PlayerMsgId.P_6537,
                        "/boss teleport " + line.kind().commandValue + " " + line.id(),
                        description
                );
            } else {
                sendInfo(sender, PlayerMsgResource.format(PlayerMsgId.P_6537.getId(), description));
            }
        }
    }

    private void handleTeleport(
            @NotNull CommandSender sender,
            @NotNull BossChallengeService bossService,
            AstralRecord plugin,
            @NotNull String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5060.getId()));
            return;
        }
        if (args.length != 3) {
            sendUsage(sender);
            return;
        }
        ChallengeKind kind = ChallengeKind.fromCommandValue(args[1]);
        if (kind == null) {
            sendTeleportError(player);
            return;
        }
        UUID instanceId;
        try {
            instanceId = UUID.fromString(args[2]);
        } catch (IllegalArgumentException exception) {
            sendTeleportError(player);
            return;
        }
        boolean teleported = switch (kind) {
            case BOSS -> bossService.teleportAdminToLeader(player, instanceId);
            case DUNGEON -> plugin != null
                    && plugin.getDungeonService() != null
                    && plugin.getDungeonService().teleportAdminToLeader(player, instanceId);
        };
        if (!teleported) {
            sendTeleportError(player);
        }
    }

    private void sendTeleportError(@NotNull Player player) {
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6539);
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

    private enum ChallengeKind {
        BOSS("boss", "ボス"),
        DUNGEON("dungeon", "ダンジョン");

        private final String commandValue;
        private final String displayName;

        ChallengeKind(@NotNull String commandValue, @NotNull String displayName) {
            this.commandValue = commandValue;
            this.displayName = displayName;
        }

        private static ChallengeKind fromCommandValue(@NotNull String value) {
            for (ChallengeKind kind : values()) {
                if (kind.commandValue.equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private record ActiveChallengeLine(
            @NotNull ChallengeKind kind,
            @NotNull UUID id,
            @NotNull String description
    ) {
    }
}
