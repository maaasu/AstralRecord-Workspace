package io.github.maaasu.astralRecord.feature.resourcepack.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Java Edition プレイヤーの参加時にリソースパック要求を送信するイベントハンドラ。
 */
public class ResourcePackJoinEventHandler extends AbstractEventHandler {

    private final ResourcePackService resourcePackService;

    /**
     * リソースパック参加イベントハンドラを生成します。
     *
     * @param resourcePackService リソースパックサービス
     */
    public ResourcePackJoinEventHandler(ResourcePackService resourcePackService) {
        this.resourcePackService = resourcePackService;
    }

    /**
     * リソースパック機能が有効な場合のみイベントハンドラを登録します。
     *
     * @return 有効なら true
     */
    @Override
    public boolean isEnabled() {
        return resourcePackService.isEnabled();
    }

    /**
     * プレイヤー参加時にリソースパック要求を送信します。
     *
     * @param event プレイヤー参加イベント
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        runSafely(() -> resourcePackService.applyTo(event.getPlayer()), LogId.E_5550, event.getPlayer().getName());
    }
}
