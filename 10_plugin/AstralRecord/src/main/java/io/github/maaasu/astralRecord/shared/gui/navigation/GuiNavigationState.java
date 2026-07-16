package io.github.maaasu.astralRecord.shared.gui.navigation;

import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 一人のプレイヤーが閉じずに移動した GUI の現在位置と履歴を保持します。
 */
public final class GuiNavigationState {
    private static final int MAX_HISTORY_SIZE = 16;

    private final Deque<Inventory> previousGuis = new ArrayDeque<>();
    private Inventory currentGui;
    private Inventory expectedBackGui;

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
     * @return 開くべき一つ前の GUI。存在しない場合は {@code null}
     */
    public @Nullable Inventory beginBack() {
        Inventory previous = previousGuis.pollFirst();
        expectedBackGui = previous;
        return previous;
    }

    /**
     * 予約済みの戻り先 GUI が開かれたことを反映します。
     *
     * @param inventory 開かれた GUI
     * @return 戻る遷移として消費した場合は {@code true}
     */
    public boolean completeBack(@NotNull Inventory inventory) {
        if (expectedBackGui != inventory) {
            return false;
        }
        currentGui = inventory;
        expectedBackGui = null;
        return true;
    }

    /**
     * GUI を閉じたセッションの履歴を破棄します。
     */
    public void clear() {
        currentGui = null;
        expectedBackGui = null;
        previousGuis.clear();
    }
}
