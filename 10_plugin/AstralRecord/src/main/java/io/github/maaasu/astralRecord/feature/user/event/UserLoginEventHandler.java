package io.github.maaasu.astralRecord.feature.user.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.net.InetAddress;

/**
 * プレイヤーログイン時のユーザー処理イベントハンドラー。
 * ユーザーの初回登録・最終ログイン情報の更新を行います。
 * AsyncPlayerPreLoginEvent はサーバーメインスレッド外で呼ばれるため、
 * DB アクセスをブロッキングで行っても問題ありません。
 */
public class UserLoginEventHandler extends AbstractEventHandler {

    private final UserService userService;

    public UserLoginEventHandler(UserService userService) {
        this.userService = userService;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        InetAddress address = event.getAddress();
        String globalIp = address.getHostAddress();

        try {
            boolean allowed = userService.onAsyncPreLogin(event.getUniqueId(), event.getName(), globalIp);
            if (!allowed) {
                event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        PlayerMsgResource.getComponent(PlayerMsgId.P_5307.getId())
                );
            }
        } catch (Exception e) {
            Logger.log(LogId.E_5000, e, event.getName());
        }
    }
}
