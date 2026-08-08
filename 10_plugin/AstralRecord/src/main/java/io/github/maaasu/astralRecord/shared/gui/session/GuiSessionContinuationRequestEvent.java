package io.github.maaasu.astralRecord.shared.gui.session;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * close 後にも同じ GUI セッションを継続するため、共有 lifecycle へ再表示を予約するイベントです。
 *
 * <p>InventoryCloseEvent 内で発行し、共有 handler が source close の終了確定より先に token を
 * 登録します。target supplier は次 tick に評価されるため、close event 内で Inventory を開き直しません。</p>
 */
public final class GuiSessionContinuationRequestEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Inventory sourceInventory;
    private final Supplier<Inventory> targetInventorySupplier;
    private final Runnable targetOpened;

    /**
     * GUI セッション継続の再表示要求を生成します。
     *
     * @param player 継続対象プレイヤー
     * @param sourceInventory close された GUI inventory
     * @param targetInventorySupplier 次 tick に評価する再表示先 GUI の supplier。継続できない場合は {@code null} を返す
     */
    public GuiSessionContinuationRequestEvent(
        @NotNull Player player,
        @NotNull Inventory sourceInventory,
        @NotNull Supplier<Inventory> targetInventorySupplier
    ) {
        this(player, sourceInventory, targetInventorySupplier, () -> {
        });
    }

    /**
     * GUI セッション継続の再表示要求を生成します。
     *
     * @param player 継続対象プレイヤー
     * @param sourceInventory close された GUI inventory
     * @param targetInventorySupplier 次 tick に評価する再表示先 GUI の supplier。継続できない場合は {@code null} を返す
     * @param targetOpened target inventory を実際に開けた後だけ実行する処理
     */
    public GuiSessionContinuationRequestEvent(
        @NotNull Player player,
        @NotNull Inventory sourceInventory,
        @NotNull Supplier<Inventory> targetInventorySupplier,
        @NotNull Runnable targetOpened
    ) {
        this.player = player;
        this.sourceInventory = sourceInventory;
        this.targetInventorySupplier = targetInventorySupplier;
        this.targetOpened = targetOpened;
    }

    /**
     * セッションを継続するプレイヤーを返します。
     *
     * @return 継続対象プレイヤー
     */
    public @NotNull Player getPlayer() {
        return player;
    }

    /**
     * close された遷移元 GUI を返します。
     *
     * @return 遷移元 inventory
     */
    public @NotNull Inventory getSourceInventory() {
        return sourceInventory;
    }

    /**
     * 継続先 GUI を作成する supplier を返します。
     *
     * @return 継続先 inventory の supplier
     */
    public @NotNull Supplier<Inventory> getTargetInventorySupplier() {
        return targetInventorySupplier;
    }

    /**
     * 継続先 GUI を実際に開けた後だけ実行する処理を返します。
     *
     * <p>target open の cancel・失敗、または古い継続 token により再表示を中止した場合は実行しません。</p>
     *
     * @return 継続先 GUI の表示成功後に実行する処理
     */
    public @NotNull Runnable getTargetOpened() {
        return targetOpened;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit がイベント handler 一覧を取得するための accessor です。
     *
     * @return GUI セッション継続要求イベントの handler 一覧
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
