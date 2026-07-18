package io.github.maaasu.astralRecord.shared.interaction;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プラグイン内のコンテンツが消費したプレイヤーインタラクトを共有します。
 */
public final class PlayerInteractionConsumeService {
    private static final long INTERACTION_PRIORITY_NANOS = 150_000_000L;
    private final Set<PlayerInteractEvent> consumedInteractEvents =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<UUID, Long> prioritizedPlayers = new ConcurrentHashMap<>();

    /**
     * PlayerInteractEvent をコンテンツ処理済みとして記録し、後続の武器入力処理から除外できるようにします。
     *
     * @param event コンテンツが処理した PlayerInteractEvent
     */
    public void consume(@NotNull PlayerInteractEvent event) {
        event.setCancelled(true);
        consumedInteractEvents.add(event);
        prioritize(event.getPlayer());
    }

    /**
     * 指定された PlayerInteractEvent がプラグイン内コンテンツに消費済みか判定します。
     *
     * @param event 判定対象の PlayerInteractEvent
     * @return コンテンツ処理済みとして記録されている場合は true
     */
    public boolean isConsumed(@NotNull PlayerInteractEvent event) {
        return consumedInteractEvents.contains(event) || hasInteractionPriority(event.getPlayer());
    }

    /**
     * エンティティなど明示的なインタラクト先を、短時間だけ武器入力より優先します。
     *
     * @param player インタラクトしたプレイヤー
     */
    public void prioritize(@NotNull Player player) {
        prioritizedPlayers.put(player.getUniqueId(), System.nanoTime() + INTERACTION_PRIORITY_NANOS);
    }

    /**
     * プレイヤー退出時に短期優先状態を破棄します。
     *
     * @param player 対象プレイヤー
     */
    public void clear(@NotNull Player player) {
        prioritizedPlayers.remove(player.getUniqueId());
    }

    private boolean hasInteractionPriority(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        Long expiresAt = prioritizedPlayers.get(playerId);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt < System.nanoTime()) {
            prioritizedPlayers.remove(playerId, expiresAt);
            return false;
        }
        return true;
    }
}
