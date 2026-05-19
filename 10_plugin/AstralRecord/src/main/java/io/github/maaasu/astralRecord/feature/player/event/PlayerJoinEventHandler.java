package io.github.maaasu.astralRecord.feature.player.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

/**
 * プレイヤーのログイン・ログアウト処理を行うイベントハンドラー。
 * <p>
 * ログイン時は外部データ取得を非同期で行い、Bukkit API 操作だけをメインスレッドに戻して
 * {@link AstPlayer} をキャッシュに登録します。OP権限の付与・剥奪は
 * {@link AstPlayer#applyPermission(io.github.maaasu.astralRecord.feature.user.model.UserModel)} が行います。
 * ログアウト時は {@link PlayerService#onPlayerQuit(org.bukkit.entity.Player)} でキャッシュを削除します。
 */
public class PlayerJoinEventHandler extends AbstractEventHandler {

    private final PlayerService playerService;
    private final AstralRecord plugin;

    public PlayerJoinEventHandler(AstralRecord plugin, PlayerService playerService) {
        this.plugin = plugin;
        this.playerService = playerService;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getName();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            runSafely(() -> {
                var joinData = playerService.loadPlayerJoinData(playerUuid, playerName);
                if (joinData == null) {
                    return;
                }

                plugin.getServer().getScheduler().runTask(plugin, () ->
                    runSafely(() -> playerService.applyPlayerJoin(player, joinData), LogId.E_5070, playerName)
                );
            }, LogId.E_5070, playerName)
        );
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        runSafely(() -> playerService.onPlayerQuit(event.getPlayer()), LogId.E_5070, event.getPlayer().getName());
    }
}


