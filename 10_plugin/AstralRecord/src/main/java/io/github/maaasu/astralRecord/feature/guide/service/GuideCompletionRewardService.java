package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.guide.model.GuideCompletionRewardStatus;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideCompletionRewardRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 保存済みガイド進捗を使って、全達成報酬の表示と一度だけの受取を調整します。 */
public final class GuideCompletionRewardService {
    private final AstralRecord plugin;
    private final GuideService guideService;
    private final InventorySaveCoordinator saveCoordinator;
    private final GuideCompletionRewardRepository repository;
    private final PlayerMessageService messages;
    private final ConcurrentMap<UUID, GuideCompletionRewardStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, CopyOnWriteArrayList<Runnable>> refreshCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Object> refreshInFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Object> claimInFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> unresolvedRewardBoundaries = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> announcementRequested = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> announced = ConcurrentHashMap.newKeySet();

    /**
     * 報酬受取サービスを作成します。
     *
     * @param plugin プラグイン本体
     * @param guideService ガイド進捗
     * @param saveCoordinator player-state 保存境界
     * @param repository 報酬 API
     * @param messages プレイヤー通知
     */
    public GuideCompletionRewardService(
        @NotNull AstralRecord plugin,
        @NotNull GuideService guideService,
        @NotNull InventorySaveCoordinator saveCoordinator,
        @NotNull GuideCompletionRewardRepository repository,
        @NotNull PlayerMessageService messages
    ) {
        this.plugin = plugin;
        this.guideService = guideService;
        this.saveCoordinator = saveCoordinator;
        this.repository = repository;
        this.messages = messages;
    }

    /**
     * 保存を確定してから非同期で API の報酬状態を取得します。GUI の再描画 callback は主スレッドで呼びます。
     * GET 後保存が失敗した場合は同じ操作 ID の回復照会で境界を確定します。
     *
     * @param player 対象プレイヤー
     * @param announce 受取可能になった場合に一度だけ案内するか
     * @param onChanged 状態更新後の再描画
     */
    public void refresh(@NotNull Player player, boolean announce, @NotNull Runnable onChanged) {
        AstPlayer session = AstPlayerCache.get(player);
        UUID accountId = session == null ? null : session.getAccount().getUuid();
        if (accountId == null || !guideService.isProgressLoaded(accountId)
            || !guideService.areAllGuidesCompleted(accountId)) {
            if (accountId != null) statuses.remove(accountId);
            onChanged.run();
            return;
        }
        refreshCallbacks.computeIfAbsent(accountId, ignored -> new CopyOnWriteArrayList<>()).add(onChanged);
        if (announce) announcementRequested.add(accountId);
        // A claim already owns the account lane. Its result will satisfy the queued redraws.
        if (claimInFlight.containsKey(accountId)) return;
        Object token = new Object();
        if (refreshInFlight.putIfAbsent(accountId, token) != null) return;
        UUID recoveryToken = unresolvedRewardBoundaries.get(accountId);
        UUID operationId = recoveryToken == null ? UUID.randomUUID() : recoveryToken;
        AtomicBoolean operationStarted = new AtomicBoolean();
        if (recoveryToken == null) unresolvedRewardBoundaries.put(accountId, operationId);
        var lookup = recoveryToken == null
            ? saveCoordinator.executeExclusiveAfterSave(accountId, operationId, ignored -> {
                operationStarted.set(true);
                // A failed GET cannot change player state and must not strand an external boundary.
                try {
                    return repository.find(accountId);
                } catch (RuntimeException failure) {
                    return null;
                }
            })
            : saveCoordinator.executeExclusiveAfterSaveRecovery(accountId, recoveryToken, ignored -> {
                operationStarted.set(true);
                return repository.find(accountId);
            });
        lookup
            .whenComplete((status, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!refreshInFlight.remove(accountId, token)) return;
                if (error == null || !operationStarted.get()) {
                    unresolvedRewardBoundaries.remove(accountId, operationId);
                }
                if (error == null && status != null && sameSession(player, session, accountId)
                    && guideService.areAllGuidesCompleted(accountId)) {
                    statuses.put(accountId, status);
                    if (announcementRequested.remove(accountId) && canClaim(status)
                        && announced.add(accountId)) {
                        messages.send(player, PlayerMsgId.P_5220);
                    }
                } else {
                    statuses.remove(accountId);
                    announcementRequested.remove(accountId);
                }
                var callbacks = refreshCallbacks.remove(accountId);
                if (callbacks != null) callbacks.forEach(Runnable::run);
            }));
    }

    /**
     * 未受取かつ全ガイド完了の表示可能な状態を返します。
     *
     * @param accountId 対象アカウント
     * @return ボタンを表示してよい場合 true
     */
    public boolean canClaim(@Nullable UUID accountId) {
        return accountId != null && guideService.isProgressLoaded(accountId)
            && guideService.areAllGuidesCompleted(accountId)
            && canClaim(statuses.get(accountId)) && !claimInFlight.containsKey(accountId)
            && !refreshInFlight.containsKey(accountId) && !unresolvedRewardBoundaries.containsKey(accountId);
    }

    /**
     * 保存済み進捗を確定させてから非同期で API の受取処理を呼びます。
     * 結果が不明なら GET で再照会し、保存境界が残る場合は同じ操作 ID で回復します。
     * {@code onChanged} は主スレッドで呼びます。
     *
     * @param player 対象プレイヤー
     * @param onChanged 状態更新後の再描画
     */
    public void claim(@NotNull Player player, @NotNull Runnable onChanged) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        UUID accountId = astPlayer == null ? null : astPlayer.getAccount().getUuid();
        if (accountId == null || !canClaim(accountId)) return;
        Object token = new Object();
        if (claimInFlight.putIfAbsent(accountId, token) != null) return;
        UUID updatedBy = astPlayer.getUser().getUuid();
        UUID operationId = UUID.randomUUID();
        AtomicBoolean operationStarted = new AtomicBoolean();
        unresolvedRewardBoundaries.put(accountId, operationId);
        saveCoordinator.executeExclusiveAfterSave(accountId, operationId, ignored -> {
            operationStarted.set(true);
            try {
                return new ClaimOutcome(repository.claim(accountId, updatedBy), null);
            } catch (RuntimeException failure) {
                // The API only records the mail claim; it does not mutate the player-state snapshot.
                return new ClaimOutcome(null, failure);
            }
        }).whenComplete((outcome, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!claimInFlight.remove(accountId, token)
                    || !sameSession(player, astPlayer, accountId)) return;
                if (error == null || !operationStarted.get()) {
                    unresolvedRewardBoundaries.remove(accountId, operationId);
                }
                if (error == null && outcome != null && outcome.status() != null && outcome.status().claimed()) {
                    statuses.put(accountId, outcome.status());
                    messages.send(player, PlayerMsgId.P_5221);
                    onChanged.run();
                    var callbacks = refreshCallbacks.remove(accountId);
                    if (callbacks != null) callbacks.forEach(Runnable::run);
                    return;
                }
                Throwable cause = error instanceof CompletionException completion ? completion.getCause() : error;
                if (cause == null && outcome != null) cause = outcome.failure();
                statuses.remove(accountId);
                if (cause instanceof GuideCompletionRewardRepository.RewardUnavailableException) {
                    messages.send(player, PlayerMsgId.P_5223);
                    refresh(player, false, onChanged);
                } else {
                    refresh(player, false, () -> {
                        GuideCompletionRewardStatus checked = statuses.get(accountId);
                        messages.send(player, checked != null && checked.claimed()
                            ? PlayerMsgId.P_5221 : PlayerMsgId.P_5222);
                        onChanged.run();
                    });
                }
            }));
    }

    /**
     * ログアウト時にプレイヤー固有の一時状態を破棄します。
     * 未解決の保存境界の操作 ID は次のログインで回復できるよう保持します。
     *
     * @param accountId 対象アカウント
     */
    public void discardAccountState(@NotNull UUID accountId) {
        statuses.remove(accountId);
        refreshCallbacks.remove(accountId);
        refreshInFlight.remove(accountId);
        claimInFlight.remove(accountId);
        announcementRequested.remove(accountId);
        announced.remove(accountId);
    }

    private static boolean canClaim(@Nullable GuideCompletionRewardStatus status) {
        return status != null && status.configured() && status.allCompleted()
            && !status.claimed() && status.canClaim();
    }

    private record ClaimOutcome(
        @Nullable GuideCompletionRewardStatus status,
        @Nullable RuntimeException failure
    ) {
    }

    private static boolean sameSession(
        @NotNull Player player,
        @NotNull AstPlayer session,
        @NotNull UUID accountId
    ) {
        return player.isOnline() && AstPlayerCache.get(player) == session
            && accountId.equals(session.getAccount().getUuid());
    }
}
