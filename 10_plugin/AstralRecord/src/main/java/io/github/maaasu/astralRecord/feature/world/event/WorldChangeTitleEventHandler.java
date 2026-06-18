package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ワールド切り替え時に現在ワールド名のタイトル表示を行います。
 */
public final class WorldChangeTitleEventHandler extends AbstractEventHandler {

    private final WorldService worldService;

    /**
     * ハンドラを初期化します。
     *
     * @param worldService ワールドサービス
     */
    public WorldChangeTitleEventHandler(@NotNull WorldService worldService) {
        this.worldService = worldService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        worldService.showWorldChangeTitle(event.getPlayer());
    }
}
