package io.github.maaasu.astralRecord.feature.skilltree.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * /skilltree コマンドです。
 */
public class SkillTreeCommand extends AstCommand {
    private static final String BACK_ARGUMENT = "back";
    private static final String HELP_ARGUMENT = "help";
    private static final String NODE_ARGUMENT = "node";
    private static final String HIGHLIGHT_ARGUMENT = "highlight";
    private static final String FILTER_ARGUMENT = "filter";
    private static final String FILTER_OFF_ARGUMENT = "off";
    private static final String FILTER_CLEAR_ARGUMENT = "clear";

    private final SkillTreeService service;

    public SkillTreeCommand(@NotNull SkillTreeService service) {
        super("skilltree", "スキルツリーを開く、または表示設定を変更します。",
                "/skilltree [back|help|node highlight [true|false]|node filter [status[:status...]]]", true);
        this.service = service;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            handleTeleport(player);
            return;
        }
        if (args.length == 1) {
            if (BACK_ARGUMENT.equalsIgnoreCase(args[0])) {
                handleBack(player);
                return;
            }
            if (HELP_ARGUMENT.equalsIgnoreCase(args[0])) {
                sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5878.getId()));
                return;
            }
        }
        if (args.length >= 2 && NODE_ARGUMENT.equalsIgnoreCase(args[0])) {
            handleNodeDisplayCommand(player, args);
            return;
        }
        sendUsage(player.getBukkit());
    }

    private void handleBack(@NotNull AstPlayer player) {
        service.returnToBase(player.getBukkit()).thenAccept(success ->
                Bukkit.getScheduler().runTask(io.github.maaasu.astralRecord.AstralRecord.getInstance(), () -> {
                    if (!success) {
                        sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5820.getId()));
                    }
                })
        );
    }

    private void handleTeleport(@NotNull AstPlayer player) {
        if (!service.canTeleportFrom(player.getBukkit().getWorld())) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5829.getId()));
            return;
        }
        service.teleportToSkillTree(player).thenAccept(success ->
                Bukkit.getScheduler().runTask(io.github.maaasu.astralRecord.AstralRecord.getInstance(), () -> {
                    if (!success) {
                        sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5820.getId()));
                        return;
                    }
                    sendSuccess(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5819.getId()));
                })
        );
    }

    private void handleNodeDisplayCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (HIGHLIGHT_ARGUMENT.equalsIgnoreCase(args[1])) {
            handleSkillNodeHighlight(player, args);
            return;
        }
        if (FILTER_ARGUMENT.equalsIgnoreCase(args[1])) {
            handleStatusFilter(player, args);
            return;
        }
        sendUsage(player.getBukkit());
    }

    private void handleSkillNodeHighlight(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length > 3) {
            sendUsage(player.getBukkit());
            return;
        }
        boolean enabled;
        if (args.length == 2) {
            enabled = service.toggleSkillNodeHighlight(player.getBukkit());
        } else {
            Boolean requested = parseBoolean(args[2]);
            if (requested == null) {
                sendUsage(player.getBukkit());
                return;
            }
            enabled = requested;
            service.setSkillNodeHighlightEnabled(player.getBukkit(), enabled);
        }
        sendSuccess(
                player.getBukkit(),
                PlayerMsgResource.format(PlayerMsgId.P_5875.getId(), enabled ? "ON" : "OFF")
        );
    }

    private void handleStatusFilter(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length > 3) {
            sendUsage(player.getBukkit());
            return;
        }
        if (args.length == 2
                || FILTER_OFF_ARGUMENT.equalsIgnoreCase(args[2])
                || FILTER_CLEAR_ARGUMENT.equalsIgnoreCase(args[2])) {
            service.setStatusFilter(player.getBukkit(), Set.of());
            sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5876.getId(), "OFF"));
            return;
        }

        Set<StatusType> statusFilter = new LinkedHashSet<>();
        for (String rawStatus : args[2].split(":", -1)) {
            StatusType statusType = resolveStatusType(rawStatus);
            if (statusType == null) {
                sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5877.getId(), rawStatus));
                return;
            }
            statusFilter.add(statusType);
        }
        service.setStatusFilter(player.getBukkit(), statusFilter);
        String descriptions = statusFilter.stream()
                .map(StatusType::getDisplayName)
                .collect(java.util.stream.Collectors.joining(", "));
        sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_5876.getId(), descriptions));
    }

    private @Nullable Boolean parseBoolean(@NotNull String rawValue) {
        return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * 英語IDまたは日本語表示名からステータス種別を解決します。
     *
     * @param rawStatus コマンド引数のステータス表記
     * @return 解決したステータス種別。空文字または未定義ならnull
     */
    private @Nullable StatusType resolveStatusType(@NotNull String rawStatus) {
        String normalized = rawStatus.trim();
        if (normalized.isBlank()) {
            return null;
        }
        String statusId = normalized.replace('-', '_').toUpperCase(Locale.ROOT);
        StatusType byId = StatusType.fromId(statusId);
        if (byId != null) {
            return byId;
        }
        return java.util.Arrays.stream(StatusType.values())
                .filter(statusType -> statusType.getDisplayName().equals(normalized))
                .findFirst()
                .orElse(null);
    }

}
