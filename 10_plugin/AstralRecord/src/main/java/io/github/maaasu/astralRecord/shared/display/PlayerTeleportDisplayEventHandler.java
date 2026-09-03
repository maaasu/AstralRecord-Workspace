package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーのテレポート前に頭上 TextDisplay を破棄します。
 *
 * <p>プラグイン内のすべてのテレポート経路を捕捉し、旧位置に表示が残ることを防ぎます。</p>
 */
public final class PlayerTeleportDisplayEventHandler extends AbstractEventHandler {

    private final OverheadDisplayService overheadDisplayService;

    /**
     * テレポート時の頭上表示破棄ハンドラを構築します。
     *
     * @param overheadDisplayService 頭上表示サービス
     */
    public PlayerTeleportDisplayEventHandler(@NotNull OverheadDisplayService overheadDisplayService) {
        this.overheadDisplayService = overheadDisplayService;
    }

    /**
     * テレポート前に現在の頭上表示を破棄します。
     *
     * @param event プレイヤーテレポートイベント
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        overheadDisplayService.removePlayerDisplay(event.getPlayer().getUniqueId());
    }
}
