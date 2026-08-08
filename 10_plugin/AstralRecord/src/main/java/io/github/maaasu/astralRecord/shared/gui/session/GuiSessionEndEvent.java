package io.github.maaasu.astralRecord.shared.gui.session;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * プラグイン管理 GUI のセッション終了が確定した後に発行するイベントです。
 *
 * <p>{@link org.bukkit.event.inventory.InventoryCloseEvent} は GUI 遷移でも発生するため、
 * feature 固有の終了処理はこのイベントで受け取ります。</p>
 */
public final class GuiSessionEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Inventory inventory;
    private final GuiSessionEndReason reason;

    /**
     * GUI セッション終了イベントを生成します。
     *
     * @param player セッションを終了したプレイヤー
     * @param inventory 終了した GUI inventory
     * @param reason 終了理由と CLOSE 音の再生可否
     */
    public GuiSessionEndEvent(
        @NotNull Player player,
        @NotNull Inventory inventory,
        @NotNull GuiSessionEndReason reason
    ) {
        this.player = player;
        this.inventory = inventory;
        this.reason = reason;
    }

    /**
     * セッションを終了したプレイヤーを返します。
     *
     * @return 終了対象プレイヤー
     */
    public @NotNull Player getPlayer() {
        return player;
    }

    /**
     * 終了した GUI inventory を返します。
     *
     * @return 終了対象 inventory
     */
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /**
     * セッションを終了した理由を返します。
     *
     * @return 終了理由
     */
    public @NotNull GuiSessionEndReason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit がイベント handler 一覧を取得するための accessor です。
     *
     * @return GUI セッション終了イベントの handler 一覧
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
