package io.github.maaasu.astralRecord.feature.player.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 保存不能 state の復旧時に、プレイヤーの保存を伴わない runtime を破棄するための正規イベントです。
 * <p>
 * Bukkit の {@code PlayerQuitEvent} を擬装せず、永続保存・パーティー退出・通知・ネットワーク退出を
 * 発生させない cleanup だけを明示的に実行します。
 */
public final class PlayerRuntimeDiscardEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;

    /**
     * runtime を破棄する player を指定してイベントを生成します。
     *
     * @param player runtime を破棄するオンライン player
     */
    public PlayerRuntimeDiscardEvent(@NotNull Player player) {
        this.player = player;
    }

    /**
     * runtime を破棄する player を返します。
     *
     * @return 対象 player
     */
    public @NotNull Player getPlayer() {
        return player;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Bukkit の handler list を返します。 */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
