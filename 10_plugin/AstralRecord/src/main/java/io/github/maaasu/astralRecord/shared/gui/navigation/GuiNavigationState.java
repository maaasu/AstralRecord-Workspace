package io.github.maaasu.astralRecord.shared.gui.navigation;

import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * 一人のプレイヤーが閉じずに移動した GUI の現在位置と履歴を保持します。
 */
public final class GuiNavigationState {
    private static final int MAX_HISTORY_SIZE = 16;

    private final Deque<Inventory> previousGuis = new ArrayDeque<>();
    private Inventory currentGui;
    private BackReservation pendingBack;

    /**
     * 現在表示中として記録された GUI を返します。
     *
     * @return 現在の GUI。GUI セッションがない場合は {@code null}
     */
    public @Nullable Inventory getCurrentGui() {
        return currentGui;
    }

    /**
     * 一つ前に表示していた GUI を返します。
     *
     * @return 戻り先 GUI。存在しない場合は {@code null}
     */
    public @Nullable Inventory getPreviousGui() {
        return previousGuis.peekFirst();
    }

    /**
     * GUI を現在位置として記録します。
     *
     * @param inventory 新しく表示した GUI
     * @param replaceCurrent 同じ画面の再描画として現在位置だけを置き換える場合は {@code true}
     */
    public void recordOpen(@NotNull Inventory inventory, boolean replaceCurrent) {
        if (currentGui != null && currentGui != inventory && !replaceCurrent) {
            previousGuis.addFirst(currentGui);
            while (previousGuis.size() > MAX_HISTORY_SIZE) {
                previousGuis.removeLast();
            }
        }
        currentGui = inventory;
    }

    /**
     * 戻り先を履歴から取り出し、次の open を戻る遷移として予約します。
     *
     * @return 開くべき一つ前の GUI を含む予約。存在しない場合は {@code null}
     */
    public @Nullable BackReservation beginBack() {
        rollbackPendingBack();
        Inventory previous = previousGuis.pollFirst();
        if (previous == null) {
            return null;
        }
        pendingBack = new BackReservation(UUID.randomUUID(), previous);
        return pendingBack;
    }

    /**
     * 予約済みの戻り先 GUI が開かれたことを反映します。
     *
     * @param inventory 開かれた GUI
     * @return 戻る遷移として消費した場合は {@code true}
     */
    public boolean completeBack(@NotNull Inventory inventory) {
        BackReservation reservation = pendingBack;
        if (reservation == null || reservation.inventory() != inventory) {
            return false;
        }
        currentGui = inventory;
        pendingBack = null;
        return true;
    }

    /**
     * 指定した戻る予約が成功したことを反映します。
     *
     * @param reservation 戻る遷移の予約 token
     * @return 現在有効な予約を完了できた場合は {@code true}
     */
    public boolean completeBack(@NotNull BackReservation reservation) {
        if (pendingBack == null || !pendingBack.id().equals(reservation.id())) {
            return false;
        }
        currentGui = reservation.inventory();
        pendingBack = null;
        return true;
    }

    /**
     * 指定した戻る予約を履歴へ戻します。
     *
     * @param reservation 取消・失敗した戻る遷移の予約 token
     * @return 現在有効な予約を復元できた場合は {@code true}
     */
    public boolean rollbackBack(@NotNull BackReservation reservation) {
        if (pendingBack == null || !pendingBack.id().equals(reservation.id())) {
            return false;
        }
        pendingBack = null;
        previousGuis.addFirst(reservation.inventory());
        return true;
    }

    /**
     * 戻る操作の対象が履歴または予約として存在するか返します。
     *
     * @return 戻る対象が存在する場合は {@code true}
     */
    public boolean hasPreviousGui() {
        return pendingBack != null || !previousGuis.isEmpty();
    }

    /**
     * GUI を閉じたセッションの履歴を破棄します。
     */
    public void clear() {
        currentGui = null;
        pendingBack = null;
        previousGuis.clear();
    }

    private void rollbackPendingBack() {
        if (pendingBack != null) {
            previousGuis.addFirst(pendingBack.inventory());
            pendingBack = null;
        }
    }

    /**
     * 戻る遷移を一意に識別し、古い取消 callback が新しい予約を戻さないための token です。
     *
     * @param id 一意な予約 ID
     * @param inventory 予約した戻り先 GUI
     */
    public record BackReservation(@NotNull UUID id, @NotNull Inventory inventory) {
    }
}
