package io.github.maaasu.astralRecord.shared.gui.navigation;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * GUI を閉じない連続操作だけを対象に、プレイヤーごとの戻る履歴を管理します。
 */
public final class GuiNavigationService {
    private final AstralRecord plugin;

    /**
     * GUI 履歴サービスを生成します。
     *
     * @param plugin スケジューラを利用するプラグイン本体
     */
    public GuiNavigationService(@NotNull AstralRecord plugin) {
        this.plugin = plugin;
    }

    /**
     * 開かれた GUI を現在位置として記録し、戻り先がなければ戻るボタンを除去します。
     *
     * @param player 対象プレイヤー
     * @param inventory 開かれた GUI
     */
    public void registerOpen(@NotNull Player player, @NotNull Inventory inventory) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (holder == null || astPlayer == null) {
            return;
        }

        GuiNavigationState state = astPlayer.getGuiNavigationState();
        if (!state.completeBack(inventory)) {
            Inventory current = state.getCurrentGui();
            boolean replaceCurrent = current != null
                && navigationId(current).equals(holder.getNavigationId());
            state.recordOpen(inventory, replaceCurrent);
        }
        updateNavigationButton(inventory, holder, state.getPreviousGui() != null);
    }

    /**
     * 共有 GUI セッションの終了確定後に、戻る履歴を破棄します。
     *
     * @param player 対象プレイヤー
     * @param closedInventory 終了した GUI inventory
     */
    public void completeSessionClose(@NotNull Player player, @NotNull Inventory closedInventory) {
        if (navigationHolder(closedInventory) == null) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            astPlayer.getGuiNavigationState().clear();
        }
    }

    /**
     * 一つ前の GUI を開きます。
     *
     * @param player 対象プレイヤー
     * @return 戻り先を開けた場合は {@code true}
     */
    public boolean openPrevious(@NotNull Player player) {
        return openPrevious(player, () -> {
        });
    }

    /**
     * 一つ前の GUI を開き、表示に成功した場合だけ指定処理を実行します。
     *
     * <p>GUI のセッション終了処理は共有の {@code GuiSessionEndEvent} が担当します。
     * このコールバックは、戻り先へ実際に遷移できたときだけ、遷移元に属する状態を
     * 片付ける用途に限定します。</p>
     *
     * @param player 対象プレイヤー
     * @param onOpened 戻り先を開けた場合に実行する処理
     * @return 戻り先を開く予約を開始できた場合は {@code true}
     */
    public boolean openPrevious(@NotNull Player player, @NotNull Runnable onOpened) {
        return openPrevious(player, onOpened, () -> {
        });
    }

    /**
     * 一つ前の GUI を開き、遷移結果に応じた処理を実行します。
     *
     * <p>GUI のセッション終了処理は共有の {@code GuiSessionEndEvent} が担当します。
     * 戻り先を実際に開けた場合は {@code onOpened}、戻り先の表示が取消・失敗した場合は
     * {@code onCancelled} を実行します。</p>
     *
     * @param player 対象プレイヤー
     * @param onOpened 戻り先を開けた場合に実行する処理
     * @param onCancelled 戻り先を開けなかった場合に実行する処理
     * @return 戻り先を開く予約を開始できた場合は {@code true}
     */
    public boolean openPrevious(
        @NotNull Player player,
        @NotNull Runnable onOpened,
        @NotNull Runnable onCancelled
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            onCancelled.run();
            return false;
        }
        GuiNavigationState state = astPlayer.getGuiNavigationState();
        GuiNavigationState.BackReservation reservation = state.beginBack();
        if (reservation == null) {
            onCancelled.run();
            return false;
        }
        GuiOpenSupport.open(
            player,
            reservation.inventory(),
            () -> {
                state.completeBack(reservation);
                onOpened.run();
            },
            () -> {
                state.rollbackBack(reservation);
                onCancelled.run();
            }
        );
        return true;
    }

    /**
     * GUI セッション内に戻り先が存在するか返します。
     *
     * @param player 対象プレイヤー
     * @return 戻り先が存在する場合は {@code true}
     */
    public boolean hasPrevious(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getGuiNavigationState().hasPreviousGui();
    }

    /**
     * 指定クリックが holder の戻るスロットか判定します。
     *
     * @param inventory クリック対象 GUI
     * @param rawSlot クリックされた raw slot
     * @return 戻るスロットなら {@code true}
     */
    public boolean isBackClick(@NotNull Inventory inventory, int rawSlot) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        return holder != null && holder.getBackSlot() >= 0 && holder.getBackSlot() == rawSlot;
    }

    /**
     * 指定クリックが共通処理対象の戻るボタンか判定します。
     *
     * @param inventory クリック対象 GUI
     * @param rawSlot クリックされた raw slot
     * @return 共通処理対象の戻るボタンなら {@code true}
     */
    public boolean isDirectBackClick(@NotNull Inventory inventory, int rawSlot) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        return holder != null
            && holder.isDirectBackNavigation()
            && isBackClick(inventory, rawSlot);
    }

    /**
     * 指定 GUI のナビゲーションスロットが閉じる操作か返します。
     *
     * @param player 対象プレイヤー
     * @param inventory クリック対象 GUI
     * @return 閉じる操作の場合は {@code true}
     */
    public boolean isCloseNavigation(@NotNull Player player, @NotNull Inventory inventory) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        return holder != null && (holder.isAlwaysCloseNavigation() || !hasPrevious(player));
    }

    private void updateNavigationButton(
        @NotNull Inventory inventory,
        @NotNull GuiNavigationHolder holder,
        boolean hasPrevious
    ) {
        int backSlot = holder.getBackSlot();
        if (backSlot < 0 || backSlot >= inventory.getSize()) {
            return;
        }
        if (holder.isAlwaysCloseNavigation() || !hasPrevious) {
            inventory.setItem(backSlot, GuiItems.closeButton());
        }
    }

    private @NotNull String navigationId(@NotNull Inventory inventory) {
        GuiNavigationHolder holder = navigationHolder(inventory);
        return holder == null ? "" : holder.getNavigationId();
    }

    private GuiNavigationHolder navigationHolder(@NotNull Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof GuiNavigationHolder navigationHolder ? navigationHolder : null;
    }
}
