package io.github.maaasu.astralRecord.shared.interaction;

import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * プラグイン内のコンテンツが消費したプレイヤーインタラクトを共有します。
 */
public final class PlayerInteractionConsumeService {
    private final Set<PlayerInteractEvent> consumedInteractEvents =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /**
     * PlayerInteractEvent をコンテンツ処理済みとして記録し、後続の武器入力処理から除外できるようにします。
     *
     * @param event コンテンツが処理した PlayerInteractEvent
     */
    public void consume(@NotNull PlayerInteractEvent event) {
        event.setCancelled(true);
        consumedInteractEvents.add(event);
    }

    /**
     * 指定された PlayerInteractEvent がプラグイン内コンテンツに消費済みか判定します。
     *
     * @param event 判定対象の PlayerInteractEvent
     * @return コンテンツ処理済みとして記録されている場合は true
     */
    public boolean isConsumed(@NotNull PlayerInteractEvent event) {
        return consumedInteractEvents.contains(event);
    }
}
