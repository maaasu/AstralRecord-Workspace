package io.github.maaasu.astralRecord.feature.donation.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.donation.model.DonationNotification;
import io.github.maaasu.astralRecord.feature.donation.repository.DonationNotificationRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** オンラインプレイヤーへ寄付結果を表示し、表示後に API へ確認を返します。 */
public final class DonationNotificationService {
    private static final long POLL_TICKS = 40L;
    private static final long ERROR_LOG_INTERVAL_NANOS = 60_000_000_000L;

    private final AstralRecord plugin;
    private final DonationNotificationRepository repository;
    private final Set<UUID> inFlightUsers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<String>> displayedIdsByUser = new HashMap<>();
    private final AtomicLong lastErrorLogNanos = new AtomicLong();
    private volatile boolean running;
    private @Nullable BukkitTask task;

    /**
     * 通知監視を構築します。HTTP 通信は非同期 scheduler でのみ実行します。
     *
     * @param plugin 稼働中の Plugin
     * @param repository 通知 API repository
     */
    public DonationNotificationService(
        @NotNull AstralRecord plugin,
        @NotNull DonationNotificationRepository repository
    ) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /** オンラインユーザーの未確認通知を2秒間隔で確認します。 */
    public void start() {
        if (task != null) {
            return;
        }
        running = true;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Set<UUID> users = new HashSet<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                AstPlayer astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null && users.add(astPlayer.getUser().getUuid())) {
                    poll(astPlayer.getUser().getUuid());
                }
            }
            // ACK待ちのIDはログアウト後も保持し、再ログイン時の重複表示を防ぐ。
            displayedIdsByUser.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }, POLL_TICKS, POLL_TICKS);
    }

    /** 通知監視を停止し、次の取得や表示を抑止します。 */
    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        inFlightUsers.clear();
        displayedIdsByUser.clear();
    }

    /**
     * プレイヤーデータ読み込み完了時に、そのユーザーの通知を直ちに確認します。
     *
     * @param player 読み込み済みプレイヤー
     */
    public void pollOnLogin(@NotNull AstPlayer player) {
        if (player.getBukkit().isOnline()) {
            poll(player.getUser().getUuid());
        }
    }

    /**
     * 一ユーザー分の取得を重複させず非同期で開始します。
     *
     * @param userId 通知対象ユーザー UUID
     */
    private void poll(@NotNull UUID userId) {
        if (!running || !plugin.isEnabled() || !inFlightUsers.add(userId)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<DonationNotification> notifications = repository.findPending(userId);
                if (running && plugin.isEnabled()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> deliver(userId, notifications));
                } else {
                    inFlightUsers.remove(userId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                inFlightUsers.remove(userId);
            } catch (Exception e) {
                if (shouldLogFailure()) {
                    Logger.error(LogId.E_7300, e, userId);
                }
                inFlightUsers.remove(userId);
            }
        });
    }

    /**
     * メインスレッドで表示先を再検証し、表示した通知だけを ACK へ送ります。
     *
     * @param userId 通知対象ユーザー UUID
     * @param notifications API が返した未確認通知
     */
    private void deliver(@NotNull UUID userId, @NotNull List<DonationNotification> notifications) {
        boolean awaitingAcknowledgement = false;
        try {
            if (!running || !plugin.isEnabled()) {
                return;
            }
            AstPlayer recipient = null;
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                AstPlayer loaded = AstPlayerCache.get(player);
                if (loaded != null && userId.equals(loaded.getUser().getUuid())) {
                    recipient = loaded;
                    break;
                }
            }
            if (recipient == null) {
                return;
            }
            Set<String> displayed = displayedIdsByUser.computeIfAbsent(userId, ignored -> new HashSet<>());
            Set<String> pending = new HashSet<>();
            for (DonationNotification notification : notifications) {
                pending.add(notification.id());
            }
            displayed.retainAll(pending);
            for (DonationNotification notification : notifications) {
                if (!displayed.contains(notification.id())) {
                    PlayerMessageService.getInstance().send(
                        recipient, PlayerMsgId.P_7300, safeMessage(notification.message())
                    );
                    displayed.add(notification.id());
                }
            }
            if (!notifications.isEmpty()) {
                acknowledgeAsync(userId, notifications);
                awaitingAcknowledgement = true;
            }
        } finally {
            if (!awaitingAcknowledgement) {
                inFlightUsers.remove(userId);
            }
        }
    }

    /**
     * 表示済み通知をまとめて確認し、取得との競合を避けて成功した ID を解放します。
     * ACK とキャッシュ解放が完了するまで同じユーザーの次の取得を開始しません。
     *
     * @param userId 通知対象ユーザー UUID
     * @param notifications 表示済みの通知
     */
    private void acknowledgeAsync(@NotNull UUID userId, @NotNull List<DonationNotification> notifications) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<String> acknowledged = new HashSet<>();
            try {
                for (DonationNotification notification : notifications) {
                    if (!running || !plugin.isEnabled()) {
                        break;
                    }
                    repository.acknowledge(userId, notification.id());
                    acknowledged.add(notification.id());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (shouldLogFailure()) {
                    Logger.error(LogId.E_7301, e, userId);
                }
            } finally {
                if (running && plugin.isEnabled()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        Set<String> displayed = displayedIdsByUser.get(userId);
                        if (displayed != null) {
                            displayed.removeAll(acknowledged);
                            if (displayed.isEmpty()) {
                                displayedIdsByUser.remove(userId);
                            }
                        }
                        inFlightUsers.remove(userId);
                    });
                } else {
                    inFlightUsers.remove(userId);
                }
            }
        });
    }

    /**
     * API 由来テキストの Minecraft 装飾コードと制御文字を除去します。
     *
     * @param message API が返した通知本文
     * @return 300文字以内の装飾なし本文
     */
    private static @NotNull String safeMessage(@NotNull String message) {
        String plain = message.replaceAll("(?i)[&§][0-9A-FK-ORX]", "")
            .replace('§', ' ')
            .replaceAll("[\\p{Cntrl}]", " ")
            .trim();
        return plain.length() > 300 ? plain.substring(0, 300) : plain;
    }

    /**
     * 通信障害のログを最大1分に一度へ抑えます。
     *
     * @return 今回の障害を記録する場合 true
     */
    private boolean shouldLogFailure() {
        long now = System.nanoTime();
        long previous = lastErrorLogNanos.get();
        return now - previous >= ERROR_LOG_INTERVAL_NANOS
            && lastErrorLogNanos.compareAndSet(previous, now);
    }
}
