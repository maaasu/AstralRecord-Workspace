package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
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
    private final PlayerRegionService playerRegionService;

    /**
     * ハンドラを初期化します。
     *
     * @param worldService ワールドサービス
     * @param playerRegionService プレイヤー地域サービス
     */
    public WorldChangeTitleEventHandler(
            @NotNull WorldService worldService,
            @NotNull PlayerRegionService playerRegionService
    ) {
        this.worldService = worldService;
        this.playerRegionService = playerRegionService;
    }

    /**
     * ワールド移動タイトルを表示し、プレイヤー地域を移動先ワールド種別へ同期します。
     *
     * @param event ワールド移動イベント
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        worldService.showWorldChangeTitle(event.getPlayer());
        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (astPlayer != null) {
            playerRegionService.handleWorldChange(astPlayer);
        }
    }
}
