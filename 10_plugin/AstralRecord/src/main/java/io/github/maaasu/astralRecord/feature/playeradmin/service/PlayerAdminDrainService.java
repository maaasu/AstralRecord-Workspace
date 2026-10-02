package io.github.maaasu.astralRecord.feature.playeradmin.service;

import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.playeradmin.repository.PlayerAdminRuntimeRepository;
import io.github.maaasu.astralRecord.feature.playeradmin.repository.PlayerAdminRuntimeRepository.Drain;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 管理編集の開始後、対象ユーザーをゲームから退避し、保存とsession終了後だけ確認を返します。
 * API通信と保存待機は非同期で行い、Bukkit操作はメインスレッドへ戻します。
 */
public final class PlayerAdminDrainService {
    private final Plugin plugin;
    private final PlayerService playerService;
    private final InventorySaveCoordinator saveCoordinator;
    private final PlayerJoinEventHandler joinHandler;
    private final PlayerAdminRuntimeRepository repository;
    private final SkillTreeService skillTreeService;
    private final String serverId;
    private final UUID serverSessionId;
    private final Supplier<String> itemCatalogHash;
    private final Supplier<String> classCatalogHash;
    private final Map<UUID, Progress> progressBySession = new ConcurrentHashMap<>();
    /** 編集開始と初回 poll の間に発生した退出保存も、対象ユーザー単位で保持する。 */
    private final Map<UUID, CopyOnWriteArrayList<RecentQuit>> recentQuits = new ConcurrentHashMap<>();
    private final Set<UUID> blockedUsers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean pollFailureLogged = new AtomicBoolean();
    private BukkitTask task;

    public PlayerAdminDrainService(@NotNull Plugin plugin, @NotNull PlayerService playerService,
                                   @NotNull InventorySaveCoordinator saveCoordinator,
                                   @NotNull PlayerJoinEventHandler joinHandler,
                                   @NotNull SkillTreeService skillTreeService,
                                   @NotNull String serverId, @NotNull UUID serverSessionId,
                                   @NotNull Supplier<String> itemCatalogHash,
                                   @NotNull Supplier<String> classCatalogHash) {
        this(plugin, playerService, saveCoordinator, joinHandler, skillTreeService,
            new PlayerAdminRuntimeRepository(), serverId, serverSessionId, itemCatalogHash, classCatalogHash);
    }

    PlayerAdminDrainService(@NotNull Plugin plugin, @NotNull PlayerService playerService,
                            @NotNull InventorySaveCoordinator saveCoordinator,
                            @NotNull PlayerJoinEventHandler joinHandler,
                            @NotNull SkillTreeService skillTreeService,
                            @NotNull PlayerAdminRuntimeRepository repository,
                            @NotNull String serverId, @NotNull UUID serverSessionId,
                            @NotNull Supplier<String> itemCatalogHash,
                            @NotNull Supplier<String> classCatalogHash) {
        this.plugin = plugin;
        this.playerService = playerService;
        this.saveCoordinator = saveCoordinator;
        this.joinHandler = joinHandler;
        this.repository = repository;
        this.skillTreeService = skillTreeService;
        this.serverId = serverId;
        this.serverSessionId = serverSessionId;
        this.itemCatalogHash = itemCatalogHash;
        this.classCatalogHash = classCatalogHash;
    }

    /** サーバーの参加登録と退避要求の定期取得を開始します。 */
    public void start() {
        joinHandler.setAdminEditBlocked(blockedUsers::contains);
        joinHandler.setQuitSaveListener(this::onQuitSave);
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 1L, 40L);
    }

    /** 定期取得を終了します。起動をまたぐ確認を代行しません。 */
    public void stop() {
        if (task != null) task.cancel();
    }

    /** 管理編集のためローカル入力を停止中のユーザーか返します。 */
    public boolean isBlocked(@NotNull UUID userUuid) {
        return blockedUsers.contains(userUuid);
    }

    private void poll() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            repository.register(serverId, serverSessionId, itemCatalogHash.get(), classCatalogHash.get());
            List<Drain> active = repository.findActive(serverId, serverSessionId);
            pollFailureLogged.set(false);
            plugin.getServer().getScheduler().runTask(plugin, () -> reconcile(active));
        } catch (RuntimeException failure) {
            // 登録・取得不能な間はローカル遮断を維持し、確認応答を作らない。
            if (pollFailureLogged.compareAndSet(false, true)) Logger.log(LogId.E_7214, failure, serverId);
        } finally {
            polling.set(false);
        }
    }

    void reconcile(List<Drain> active) {
        Set<UUID> activeIds = new HashSet<>();
        Set<UUID> activeUsers = new HashSet<>();
        for (Drain drain : active) {
            activeIds.add(drain.editSessionId());
            activeUsers.add(drain.userUuid());
            blockedUsers.add(drain.userUuid());
            Progress progress = progressBySession.computeIfAbsent(drain.editSessionId(), ignored -> new Progress(drain));
            for (RecentQuit quit : recentQuits.getOrDefault(drain.userUuid(), new CopyOnWriteArrayList<>())) {
                progress.captureQuit(quit, saveCoordinator);
            }
            if ("DRAINING".equals(drain.status()) || "RECOVERY_REQUIRED".equals(drain.status())) {
                progress.freeze(drain.accountId(), saveCoordinator);
                drain(progress);
            }
        }
        progressBySession.values().removeIf(progress -> {
            if (activeIds.contains(progress.drain.editSessionId())) return false;
            progress.unfreeze(saveCoordinator);
            return true;
        });
        blockedUsers.retainAll(activeUsers);
        long now = System.nanoTime();
        recentQuits.entrySet().removeIf(entry -> {
            if (activeUsers.contains(entry.getKey())) return false;
            entry.getValue().removeIf(quit ->
                (quit.succeeded() && now - quit.startedNanos()
                    > java.util.concurrent.TimeUnit.MINUTES.toNanos(10))
                    || (quit.save().isDone() && !quit.succeeded()
                        && hasLaterSuccessfulQuit(entry.getValue(), quit)));
            return entry.getValue().isEmpty();
        });
    }

    private void drain(Progress progress) {
        if (progress.acknowledged || progress.ackInFlight) return;
        Drain request = progress.drain;
        Player online = plugin.getServer().getPlayer(request.userUuid());
        if (online != null && online.isOnline()) {
            progress.observedOnline = true;
            AstPlayer astPlayer = AstPlayerCache.get(online);
            if (astPlayer == null) {
                // 初期ロード中はゲーム操作を遮断済み。ロード試行の破棄を退出側で待つ。
                online.kick(PlayerMsgResource.formatComponent(PlayerMsgId.P_9052.getId()));
                return;
            }
            UUID activeAccountId = astPlayer.getAccount().getUuid();
            progress.activeAccountId = activeAccountId;
            progress.freeze(activeAccountId, saveCoordinator);
            if (saveCoordinator.hasUnresolvedExternalOperation(activeAccountId)) return;
            if (progress.preSave == null) {
                progress.preSave = playerService.saveForChannelTransfer(astPlayer);
                return;
            }
            if (!progress.preSave.isDone()) return;
            boolean preSaved;
            try {
                preSaved = Boolean.TRUE.equals(progress.preSave.getNow(false));
            } catch (RuntimeException failure) {
                preSaved = false;
            }
            if (!preSaved) {
                progress.preSave = null;
                return;
            }
            online.kick(PlayerMsgResource.formatComponent(PlayerMsgId.P_9052.getId()));
            return;
        }
        if (joinHandler.hasJoinAttempt(request.userUuid())) return;
        if (progress.observedOnline && progress.quitSaves.isEmpty()) return;
        for (RecentQuit quit : progress.quitSaves) {
            if (!quit.save().isDone()) return;
            if (!quit.succeeded() && !hasLaterSuccessfulQuit(progress.quitSaves, quit)) {
                if (!progress.failed) {
                    progress.failed = true;
                    Logger.log(LogId.E_7216, request.accountId());
                }
                return;
            }
        }
        progress.failed = false;
        progress.ackInFlight = true;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 退出保存の結果に加え、既に登録済みの保存laneの終了を確認する。
                Set<UUID> accountsToClose = new HashSet<>(progress.relatedAccountIds);
                accountsToClose.add(request.accountId());
                UUID activeAccountId = progress.activeAccountId;
                if (activeAccountId != null) accountsToClose.add(activeAccountId);
                for (UUID accountId : accountsToClose) playerService.awaitQueuedSavesForAccountSwitch(accountId);
                for (UUID accountId : accountsToClose) {
                    if (!skillTreeService.retryPendingRuntimeLogout(accountId)) return;
                }
                repository.acknowledge(serverId, serverSessionId, request, progress.ackId);
                progress.acknowledged = true;
            } catch (RuntimeException failure) {
                // APIが開いたままのaccount sessionを検出した場合も、次回同じackIdで再試行する。
                if (!progress.ackFailureLogged) {
                    progress.ackFailureLogged = true;
                    Logger.log(LogId.E_7215, failure, request.editSessionId());
                }
            } finally {
                progress.ackInFlight = false;
            }
        });
    }

    void onQuitSave(UUID userUuid, UUID accountId, CompletableFuture<Boolean> save) {
        RecentQuit quit = new RecentQuit(accountId, save, System.nanoTime());
        recentQuits.computeIfAbsent(userUuid, ignored -> new CopyOnWriteArrayList<>()).add(quit);
        for (Progress progress : progressBySession.values()) {
            if (progress.drain.userUuid().equals(userUuid)) progress.captureQuit(quit, saveCoordinator);
        }
    }

    private record RecentQuit(UUID accountId, CompletableFuture<Boolean> save, long startedNanos) {
        boolean succeeded() {
            if (!save.isDone()) return false;
            try {
                return Boolean.TRUE.equals(save.getNow(false));
            } catch (RuntimeException failure) {
                return false;
            }
        }
    }

    private static boolean hasLaterSuccessfulQuit(List<RecentQuit> quits, RecentQuit failedQuit) {
        if (failedQuit.accountId() == null) return false;
        int position = quits.indexOf(failedQuit);
        for (int index = position + 1; index < quits.size(); index++) {
            RecentQuit later = quits.get(index);
            if (failedQuit.accountId().equals(later.accountId()) && later.succeeded()) return true;
        }
        return false;
    }

    private static final class Progress {
        final Drain drain;
        final UUID ackId = UUID.randomUUID();
        final CopyOnWriteArrayList<RecentQuit> quitSaves = new CopyOnWriteArrayList<>();
        final Set<UUID> relatedAccountIds = ConcurrentHashMap.newKeySet();
        volatile CompletableFuture<Boolean> preSave;
        volatile UUID activeAccountId;
        volatile boolean observedOnline;
        volatile boolean failed;
        volatile boolean acknowledged;
        volatile boolean ackInFlight;
        volatile boolean ackFailureLogged;
        final Set<UUID> frozenAccounts = ConcurrentHashMap.newKeySet();

        Progress(Drain drain) {
            this.drain = drain;
        }

        void freeze(UUID accountId, InventorySaveCoordinator coordinator) {
            if (frozenAccounts.add(accountId)) coordinator.beginAdminEditDrain(accountId);
        }

        void captureQuit(RecentQuit quit, InventorySaveCoordinator coordinator) {
            quitSaves.addIfAbsent(quit);
            if (quit.accountId() != null) {
                relatedAccountIds.add(quit.accountId());
                freeze(quit.accountId(), coordinator);
            }
        }

        void unfreeze(InventorySaveCoordinator coordinator) {
            for (UUID accountId : frozenAccounts) coordinator.endAdminEditDrain(accountId);
            frozenAccounts.clear();
        }
    }
}
