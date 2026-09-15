package io.github.maaasu.astralRecord.feature.user.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.BanDuration;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.feature.network.NetworkBanClient;
import io.github.maaasu.astralRecord.feature.network.NetworkBanState;
import io.github.maaasu.astralRecord.feature.network.NetworkChannelAccessService;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB に登録済みのプレイヤーへ BAN を設定するコマンドです。
 */
public final class BanCommand extends AstCommand {
    private static final String SOURCE_PLAYER = "PLAYER";
    private static final String SOURCE_CONSOLE = "CONSOLE";
    private static final DateTimeFormatter DISPLAY_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final @Nullable UserService userService;
    private final Set<String> pendingMcids = ConcurrentHashMap.newKeySet();
    private final NetworkBanClient networkBanClient = new NetworkBanClient();

    /**
     * BAN コマンドを初期化します。
     *
     * @param userService ユーザーサービス。{@code null} の場合は実行時にプラグインから解決します
     */
    public BanCommand(@Nullable UserService userService) {
        super(
                "ban",
                "ユーザーへ BAN を設定します。",
                "/ban <mcid> <indefinite|temporary> [days]",
                false,
                UserPermission.ADMIN.getValue()
        );
        this.userService = userService;
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        BanRequest request = parseRequest(sender, args);
        if (request == null) {
            return;
        }

        AstralRecord plugin = AstralRecord.getInstance();
        UserService resolvedUserService = resolveUserService();
        if (plugin == null || resolvedUserService == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5302.getId()));
            return;
        }

        String pendingKey = request.mcid().toLowerCase(Locale.ROOT);
        if (!pendingMcids.add(pendingKey)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5309.getId()));
            return;
        }

        UUID executorUuid = getUpdatedBy(sender);
        String source = sender instanceof Player ? SOURCE_PLAYER : SOURCE_CONSOLE;
        try {
            AsyncTaskUtil.supplyAsync(plugin, () -> {
                UserModel before = resolvedUserService.getUserByMcid(request.mcid());
                if (before == null) {
                    return BanUpdateResult.notFound();
                }
                if (NetworkChannelAccessService.getInstance().isManaged()) {
                    try {
                        NetworkBanState current = networkBanClient.get(before.getUuid());
                        NetworkBanState updated = networkBanClient.update(
                            before.getUuid(),
                            executorUuid,
                            ConfigProperties.getInstance().getNetworkChannelName(),
                            current.revision(),
                            true,
                            request.duration() == BanDuration.INDEFINITE ? null : request.expiresAtUtc(),
                            current.reason()
                        );
                        return BanUpdateResult.network(before, current, updated);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Network BAN update was interrupted", exception);
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException("Network BAN update failed", exception);
                    }
                }
                UserModel updated = resolvedUserService.setBan(
                    before.getUuid(),
                    request.duration() == BanDuration.INDEFINITE,
                    request.legacyBanDate(),
                    executorUuid
                );
                return BanUpdateResult.legacy(before, updated);
            }).whenComplete((result, failure) -> AsyncTaskUtil.runSync(plugin, () -> {
                pendingMcids.remove(pendingKey);
                if (failure != null) {
                    Logger.log(LogId.E_5063, failure, request.mcid());
                    sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
                    return;
                }
                if (result == null || result.before() == null) {
                    sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_7115.getId(), request.mcid()));
                    return;
                }

                if (!result.updated()) {
                    sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5303.getId(), request.mcid()));
                    return;
                }

                boolean beforeIndefinite = result.beforeIndefinite();
                LocalDateTime beforeBanDate = result.beforeBanDate();
                boolean updatedIndefinite = result.updatedIndefinite();
                LocalDateTime updatedBanDate = result.updatedBanDate();

                Logger.log(
                        LogId.I_5054,
                        executorUuid,
                        result.before().getUuid(),
                        beforeIndefinite,
                        formatDate(beforeBanDate),
                        updatedIndefinite,
                        formatDate(updatedBanDate),
                        source
                );

                if (updatedIndefinite) {
                    sendSuccess(
                            sender,
                            PlayerMsgResource.format(PlayerMsgId.P_5308.getId(), result.before().getMcid())
                    );
                } else {
                    sendSuccess(
                            sender,
                            PlayerMsgResource.format(
                                    PlayerMsgId.P_5312.getId(),
                                    result.before().getMcid(),
                                    request.days(),
                                    formatDate(updatedBanDate)
                            )
                    );
                }

                Player online = Bukkit.getPlayer(result.before().getUuid());
                if (result.active() && online != null && online.isOnline()) {
                    online.kick(PlayerMsgResource.getComponent(PlayerMsgId.P_5307.getId()));
                }
            }));
        } catch (RuntimeException exception) {
            pendingMcids.remove(pendingKey);
            Logger.log(LogId.E_5063, exception, request.mcid());
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5062.getId()));
        }
    }

    @Nullable
    private BanRequest parseRequest(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2 || args.length > 3) {
            sendInvalidArgument(sender);
            return null;
        }

        String mcid = args[0].trim();
        BanDuration duration = BanDuration.fromArgument(args[1]);
        if (mcid.isEmpty() || duration == null) {
            sendInvalidArgument(sender);
            return null;
        }

        if (duration == BanDuration.INDEFINITE) {
            if (args.length != 2) {
                sendInvalidArgument(sender);
                return null;
            }
            return new BanRequest(mcid, duration, 0L, null);
        }

        if (args.length != 3) {
            sendInvalidArgument(sender);
            return null;
        }

        long days;
        try {
            days = Long.parseLong(args[2]);
        } catch (NumberFormatException exception) {
            sendInvalidDays(sender);
            return null;
        }
        if (days <= 0) {
            sendInvalidDays(sender);
            return null;
        }

        try {
            Instant expiresAtUtc = Instant.now().plusSeconds(Math.multiplyExact(days, 86_400L));
            return new BanRequest(mcid, duration, days, expiresAtUtc);
        } catch (DateTimeException | ArithmeticException exception) {
            sendInvalidDays(sender);
            return null;
        }
    }

    @Nullable
    private UserService resolveUserService() {
        if (userService != null) {
            return userService;
        }
        AstralRecord plugin = AstralRecord.getInstance();
        return plugin == null ? null : plugin.getUserService();
    }

    private UUID getUpdatedBy(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        return SystemUser.INSTANCE.getUuid();
    }

    @NotNull
    private String formatDate(@Nullable LocalDateTime date) {
        return date == null ? "-" : date.format(DISPLAY_DATE_FORMAT);
    }

    private void sendInvalidArgument(@NotNull CommandSender sender) {
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5310.getId()));
        sendUsage(sender);
    }

    private void sendInvalidDays(@NotNull CommandSender sender) {
        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5311.getId()));
        sendUsage(sender);
    }

    private record BanRequest(
            @NotNull String mcid,
            @NotNull BanDuration duration,
            long days,
            @Nullable Instant expiresAtUtc
    ) {
        private @Nullable LocalDateTime legacyBanDate() {
            return expiresAtUtc == null ? null : LocalDateTime.ofInstant(expiresAtUtc, ZoneId.systemDefault());
        }
    }

    private record BanUpdateResult(
            @Nullable UserModel before,
            boolean updated,
            boolean beforeIndefinite,
            @Nullable LocalDateTime beforeBanDate,
            boolean updatedIndefinite,
            @Nullable LocalDateTime updatedBanDate,
            boolean active
    ) {
        private static BanUpdateResult notFound() {
            return new BanUpdateResult(null, false, false, null, false, null, false);
        }

        private static BanUpdateResult legacy(@NotNull UserModel before, @Nullable UserModel updated) {
            return updated == null
                ? new BanUpdateResult(before, false, false, null, false, null, false)
                : new BanUpdateResult(
                    before,
                    true,
                    before.getBanIndefinite(),
                    before.getBanDate(),
                    updated.getBanIndefinite(),
                    updated.getBanDate(),
                    updated.getBanIndefinite() || updated.getBanDate() != null
                );
        }

        private static BanUpdateResult network(
            @NotNull UserModel before,
            @NotNull NetworkBanState current,
            @NotNull NetworkBanState updated
        ) {
            return new BanUpdateResult(
                before,
                true,
                current.indefinite(),
                toLocalDateTime(current.expiresAtUtc()),
                updated.indefinite(),
                toLocalDateTime(updated.expiresAtUtc()),
                updated.active()
            );
        }

        private static @Nullable LocalDateTime toLocalDateTime(@Nullable Instant value) {
            return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
        }
    }
}
