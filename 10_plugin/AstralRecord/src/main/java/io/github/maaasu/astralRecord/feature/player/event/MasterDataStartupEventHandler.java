package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.function.BooleanSupplier;

/** 起動マスタの準備と機能登録が完了するまで参加を拒否します。 */
public final class MasterDataStartupEventHandler implements Listener {
    private final BooleanSupplier ready;

    /** @param ready APIマスタと機能の初期化が完了した場合に真を返す判定 */
    public MasterDataStartupEventHandler(BooleanSupplier ready) { this.ready = ready; }

    /** @param event 非同期参加前イベント。準備未完了では参加を拒否します */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!ready.getAsBoolean()) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                PlayerMsgResource.formatComponent(PlayerMsgId.P_5399.getId()));
    }
}
