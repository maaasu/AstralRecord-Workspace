package io.github.maaasu.astralRecord.feature.whitelist.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.network.NetworkChannelAccess;
import io.github.maaasu.astralRecord.feature.network.NetworkChannelAccessService;
import io.github.maaasu.astralRecord.feature.whitelist.service.WhitelistService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.jetbrains.annotations.NotNull;

/**
 * whitelist 有効時の接続前拒否を担当します。
 */
public final class WhitelistConnectionEventHandler extends AbstractEventHandler {
    private final WhitelistService whitelistService;
    private final NetworkChannelAccessService networkChannelAccessService;

    /**
     * 接続制御イベントハンドラーを初期化します。
     *
     * @param whitelistService whitelist 状態サービス
     */
    public WhitelistConnectionEventHandler(@NotNull WhitelistService whitelistService) {
        this(whitelistService, NetworkChannelAccessService.getInstance());
    }

    /**
     * 接続制御イベントハンドラーを初期化します。
     *
     * @param whitelistService ローカル whitelist サービス
     * @param networkChannelAccessService Network管理時のチャンネルロール照会
     */
    public WhitelistConnectionEventHandler(
        @NotNull WhitelistService whitelistService,
        @NotNull NetworkChannelAccessService networkChannelAccessService
    ) {
        this.whitelistService = whitelistService;
        this.networkChannelAccessService = networkChannelAccessService;
    }

    /**
     * whitelist に含まれないプレイヤーの接続を拒否します。
     *
     * @param event 接続前イベント
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(@NotNull AsyncPlayerPreLoginEvent event) {
        boolean allowed;
        if (networkChannelAccessService.isManaged()) {
            NetworkChannelAccess access = networkChannelAccessService.resolveForLogin(event.getUniqueId());
            allowed = access != null && access.channelKnown() && access.allowed();
        } else {
            allowed = whitelistService.isAllowed(event.getUniqueId());
        }
        if (!allowed) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                PlayerMsgResource.getComponent(PlayerMsgId.P_7112.getId())
            );
        }
    }
}
