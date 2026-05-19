package io.github.maaasu.astralRecord.feature.resourcepack.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

/**
 * 管理対象リソースパック要求に対するクライアント結果を追跡するイベントハンドラ。
 */
public class ResourcePackStatusEventHandler extends AbstractEventHandler {

    private final ResourcePackService resourcePackService;

    /**
     * リソースパックステータスイベントハンドラを生成します。
     *
     * @param resourcePackService リソースパックサービス
     */
    public ResourcePackStatusEventHandler(ResourcePackService resourcePackService) {
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
     * 管理対象リソースパックのステータス通知を処理します。
     *
     * @param event リソースパックステータスイベント
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        if (!resourcePackService.isManagedPack(event.getID())) {
            return;
        }

        runSafely(
                () -> resourcePackService.handleStatus(event.getPlayer(), event.getStatus()),
                LogId.E_5550,
                event.getPlayer().getName()
        );
    }
}
