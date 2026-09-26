package io.github.maaasu.astralRecord.shared.challenge;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * インスタンス作成の通常枠・優先予約枠と待機列を管理します。
 * <p>
 * すべてのメソッドは Plugin のメインスレッドから呼び出す前提です。枠は受付完了時ではなく
 * {@link #enqueue(List, boolean, String, Consumer)} の開始 callback 実行時に占有され、
 * feature のワールド破棄完了後に {@link #release(UUID)} で解放します。
 */
public final class InstanceCreationQueue {
    private final InstanceCreationQueueConfig.InstanceCreationLimits limits;
    private final Deque<Pending> normalWaiting = new ArrayDeque<>();
    private final Deque<Pending> reservedWaiting = new ArrayDeque<>();
    private final Map<UUID, Pending> active = new java.util.LinkedHashMap<>();
    private boolean draining;

    /**
     * 作成キューを構成します。
     *
     * @param limits 通常枠・予約枠の上限
     */
    public InstanceCreationQueue(
            @NotNull InstanceCreationQueueConfig.InstanceCreationLimits limits
    ) {
        this.limits = limits;
    }

    /**
     * 作成要求を待機列へ追加し、空き枠があれば直ちに開始します。
     * 優先予約枠が0件の場合、優先利用者も通常枠へ入ります。
     *
     * @param participantIds 参加予定者
     * @param priority 優先予約枠を要求するか
     * @param displayName 待機表示名
     * @param onGranted 枠が割り当てられたときの開始処理
     * @return 作成要求チケット
     */
    public @NotNull Ticket enqueue(
            @NotNull List<UUID> participantIds,
            boolean priority,
            @NotNull String displayName,
            @NotNull Consumer<Ticket> onGranted
    ) {
        return enqueue(UUID.randomUUID(), participantIds, priority, displayName, onGranted);
    }

    /**
     * 呼出元のセッション ID をチケット ID として作成要求を待機列へ追加します。
     *
     * @param ticketId 呼出元セッションの一意な ID
     * @param participantIds 参加予定者
     * @param priority 優先予約枠を要求するか
     * @param displayName 待機表示名
     * @param onGranted 枠が割り当てられたときの開始処理
     * @return 作成要求チケット
     */
    public @NotNull Ticket enqueue(
            @NotNull UUID ticketId,
            @NotNull List<UUID> participantIds,
            boolean priority,
            @NotNull String displayName,
            @NotNull Consumer<Ticket> onGranted
    ) {
        if (active.containsKey(ticketId) || containsWaiting(ticketId)) {
            throw new IllegalArgumentException("Creation queue ticket already exists: " + ticketId);
        }
        boolean reserved = priority && limits.reservedLimit() > 0;
        Ticket ticket = new Ticket(
                ticketId,
                reserved,
                List.copyOf(participantIds),
                displayName
        );
        Pending pending = new Pending(ticket, onGranted);
        waiting(reserved).addLast(pending);
        rethrow(drain());
        return ticket;
    }

    /**
     * 通常枠へ即時入場できない場合だけ非同期で優先回数を消費し、予約列へ登録します。
     * 消費の完了までは開始せず、待機取消・開始 callback 例外時は消費済み回数を返却します。
     * 呼出元は future の完了をメインスレッドへ戻す必要があります。
     *
     * @param ticketId セッションの作成枠 ID
     * @param participantIds 参加予定者
     * @param displayName 待機表示名
     * @param consumePriority この待機登録に対する冪等な消費処理
     * @param refundPriority 同じ消費要求に対する冪等な返却処理
     * @param onGranted 枠取得後の開始処理
     * @return 作成要求チケット（昇格後の状態は waitingTickets から取得）
     */
    public @NotNull Ticket enqueueWithPriority(
            @NotNull UUID ticketId,
            @NotNull List<UUID> participantIds,
            @NotNull String displayName,
            @NotNull Supplier<CompletableFuture<Boolean>> consumePriority,
            @NotNull Runnable refundPriority,
            @NotNull Consumer<Ticket> onGranted
    ) {
        if (active.containsKey(ticketId) || containsWaiting(ticketId)) {
            throw new IllegalArgumentException("Creation queue ticket already exists: " + ticketId);
        }
        if (limits.reservedLimit() == 0 || (normalWaiting.isEmpty() && hasCapacity(false))) {
            return enqueue(ticketId, participantIds, false, displayName, onGranted);
        }
        Pending pending = new Pending(new Ticket(ticketId, false, participantIds, displayName), onGranted);
        pending.deciding = true;
        pending.refund = refundPriority;
        normalWaiting.addLast(pending);
        CompletableFuture<Boolean> decision;
        try {
            decision = consumePriority.get();
        } catch (RuntimeException exception) {
            normalWaiting.remove(pending);
            throw exception;
        }
        decision.whenComplete((consumed, error) -> {
            pending.deciding = false;
            pending.paid = Boolean.TRUE.equals(consumed);
            if (pending.cancelled) {
                pending.refundIfPaid();
                return;
            }
            if (pending.paid) {
                normalWaiting.remove(pending);
                pending.ticket = new Ticket(ticketId, true,
                        pending.ticket.participantIds(), pending.ticket.displayName());
                reservedWaiting.addLast(pending);
            }
            rethrow(drain());
        });
        return pending.ticket;
    }

    /**
     * 待機中の作成要求を取り消します。開始済みの枠は解放しません。
     *
     * @param ticketId 取消対象チケット
     * @return 待機列から削除できた場合は true
     */
    public boolean cancelWaiting(@NotNull UUID ticketId) {
        boolean removed = removeWaiting(normalWaiting, ticketId) || removeWaiting(reservedWaiting, ticketId);
        if (removed) {
            rethrow(drain());
        }
        return removed;
    }

    /**
     * 待機中チケットの参加者と予約枠要求を更新します。
     * <p>
     * 同じ枠のままなら現在の位置を維持します。通常枠・予約枠をまたぐ場合は、移動先の列の末尾へ移します。
     * 開始済み、または存在しないチケットは更新しません。
     *
     * @param ticketId 更新対象チケット
     * @param participantIds 更新後の参加予定者
     * @param priority 更新後の予約枠要求
     * @return 更新後チケット。対象が待機中でなければ {@code null}
     */
    public @Nullable Ticket updateWaiting(
            @NotNull UUID ticketId,
            @NotNull List<UUID> participantIds,
            boolean priority
    ) {
        boolean reserved = priority && limits.reservedLimit() > 0;
        for (Deque<Pending> lane : List.of(normalWaiting, reservedWaiting)) {
            List<Pending> snapshot = new ArrayList<>(lane);
            for (int index = 0; index < snapshot.size(); index++) {
                Pending pending = snapshot.get(index);
                if (!pending.ticket().id().equals(ticketId)) {
                    continue;
                }
                Ticket updated = new Ticket(ticketId, reserved, participantIds, pending.ticket().displayName());
                boolean sameLane = pending.ticket().reserved() == reserved;
                Pending replacement = pending;
                replacement.ticket = updated;
                lane.clear();
                if (sameLane) {
                    snapshot.set(index, replacement);
                    lane.addAll(snapshot);
                } else {
                    snapshot.remove(index);
                    lane.addAll(snapshot);
                    waiting(reserved).addLast(replacement);
                    rethrow(drain());
                }
                return updated;
            }
        }
        return null;
    }

    /**
     * 作成完了後に占有枠を解放し、次の要求を開始します。
     *
     * @param ticketId 解放対象チケット
     * @return 開始済みチケットを解放できた場合は true
     */
    public boolean release(@NotNull UUID ticketId) {
        Pending removed = active.remove(ticketId);
        if (removed == null) {
            return false;
        }
        rethrow(drain());
        return true;
    }

    /**
     * 待機中チケットの順番と同じ枠の待機人数を返します。
     * 順番は同じ枠の待機列内で数え、稼働中のインスタンスは含めません。
     *
     * @param ticketId チケット
     * @return 順番情報。稼働中または存在しない場合は null
     */
    public @Nullable QueuePosition position(@NotNull UUID ticketId) {
        for (Deque<Pending> lane : List.of(normalWaiting, reservedWaiting)) {
            int position = 1;
            for (Pending pending : lane) {
                if (pending.ticket().id().equals(ticketId)) {
                    return new QueuePosition(
                            position,
                            waitingParticipantCount(lane),
                            pending.ticket().reserved()
                    );
                }
                position++;
            }
        }
        return null;
    }

    /**
     * 待機中チケットのスナップショットを返します。
     *
     * @return 通常枠、予約枠の順に並んだ待機チケット
     */
    public @NotNull List<Ticket> waitingTickets() {
        List<Ticket> result = new ArrayList<>(normalWaiting.size() + reservedWaiting.size());
        normalWaiting.forEach(pending -> result.add(pending.ticket()));
        reservedWaiting.forEach(pending -> result.add(pending.ticket()));
        return List.copyOf(result);
    }

    /**
     * 待機列に存在するチケットだけを条件付きで取り消します。
     *
     * @param predicate 取消条件
     * @return 取り消したチケット
     */
    public @NotNull List<Ticket> cancelWaitingIf(@NotNull Predicate<Ticket> predicate) {
        List<Ticket> removed = new ArrayList<>();
        removeWaitingIf(normalWaiting, predicate, removed);
        removeWaitingIf(reservedWaiting, predicate, removed);
        if (!removed.isEmpty()) {
            rethrow(drain());
        }
        return List.copyOf(removed);
    }

    /**
     * 停止時に待機・稼働中のチケットをすべて破棄します。
     */
    public void clear() {
        normalWaiting.forEach(Pending::cancel);
        reservedWaiting.forEach(Pending::cancel);
        normalWaiting.clear();
        reservedWaiting.clear();
        active.clear();
    }

    /**
     * チケットが現在稼働枠を占有しているか返します。
     *
     * @param ticketId チケット
     * @return 稼働中なら true
     */
    public boolean isActive(@NotNull UUID ticketId) {
        return active.containsKey(ticketId);
    }

    private @Nullable Throwable drain() {
        if (draining) {
            return null;
        }
        draining = true;
        Throwable failure = null;
        try {
            failure = drainLane(normalWaiting, false, failure);
            failure = drainLane(reservedWaiting, true, failure);
        } finally {
            draining = false;
        }
        return failure;
    }

    private @Nullable Throwable drainLane(
            @NotNull Deque<Pending> lane,
            boolean reserved,
            @Nullable Throwable firstFailure
    ) {
        while (!lane.isEmpty() && !lane.peekFirst().deciding && hasCapacity(reserved)) {
            try {
                grant(lane.removeFirst());
            } catch (RuntimeException | Error ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }
        }
        return firstFailure;
    }

    private void grant(@NotNull Pending pending) {
        active.put(pending.ticket().id(), pending);
        try {
            pending.onGranted().accept(pending.ticket());
        } catch (RuntimeException | Error ex) {
            active.remove(pending.ticket().id(), pending);
            pending.refundIfPaid();
            throw ex;
        }
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Instance creation queue callback failed", failure);
    }

    private boolean hasCapacity(boolean reserved) {
        long count = active.values().stream()
                .filter(pending -> pending.ticket().reserved() == reserved)
                .count();
        int limit = reserved ? limits.reservedLimit() : limits.normalLimit();
        return count < limit;
    }

    private @NotNull Deque<Pending> waiting(boolean reserved) {
        return reserved ? reservedWaiting : normalWaiting;
    }

    private static boolean removeWaiting(@NotNull Deque<Pending> lane, @NotNull UUID ticketId) {
        return lane.removeIf(pending -> {
            if (!pending.ticket().id().equals(ticketId)) {
                return false;
            }
            pending.cancel();
            return true;
        });
    }

    private boolean containsWaiting(@NotNull UUID ticketId) {
        return normalWaiting.stream().anyMatch(pending -> pending.ticket().id().equals(ticketId))
                || reservedWaiting.stream().anyMatch(pending -> pending.ticket().id().equals(ticketId));
    }

    private static int waitingParticipantCount(@NotNull Deque<Pending> lane) {
        return lane.stream()
                .mapToInt(pending -> pending.ticket().participantIds().size())
                .sum();
    }

    private static void removeWaitingIf(
            @NotNull Deque<Pending> lane,
            @NotNull Predicate<Ticket> predicate,
            @NotNull List<Ticket> removed
    ) {
        lane.removeIf(pending -> {
            if (!predicate.test(pending.ticket())) {
                return false;
            }
            removed.add(pending.ticket());
            pending.cancel();
            return true;
        });
    }

    private static final class Pending {
        private Ticket ticket;
        private final Consumer<Ticket> onGranted;
        private boolean deciding;
        private boolean cancelled;
        private boolean paid;
        private Runnable refund = () -> { };

        private Pending(Ticket ticket, Consumer<Ticket> onGranted) {
            this.ticket = ticket;
            this.onGranted = onGranted;
        }

        private Ticket ticket() { return ticket; }
        private Consumer<Ticket> onGranted() { return onGranted; }

        /** 未開始の登録を取消し、消費が確定済みなら一度だけ返却します。 */
        private void cancel() {
            cancelled = true;
            refundIfPaid();
        }

        /** 未開始または開始失敗の消費を一度だけ補償します。 */
        private void refundIfPaid() {
            if (paid) {
                paid = false;
                refund.run();
            }
        }
    }

    /** 作成要求を識別するチケットです。 */
    public record Ticket(
            @NotNull UUID id,
            boolean reserved,
            @NotNull List<UUID> participantIds,
            @NotNull String displayName
    ) {
        public Ticket {
            participantIds = List.copyOf(participantIds);
            if (displayName.isBlank()) {
                throw new IllegalArgumentException("displayName must not be blank");
            }
        }
    }

    /** 待機列内の表示順と待機人数です。 */
    public record QueuePosition(int position, int waitingParticipantCount, boolean reserved) {
    }
}
