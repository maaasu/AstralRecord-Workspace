package io.github.maaasu.astralRecord.feature.playersetting.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.item.view.ItemStackPacketAdapter;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * プレイヤー設定のログイン時ロードとログアウト時クリアを行います。
 */
public final class PlayerSettingJoinEventHandler extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final PlayerSettingService playerSettingService;
    private final ItemStackPacketAdapter itemStackPacketAdapter;

    /**
     * ログイン時の設定ロードと装備表示再同期を行うハンドラを初期化します。
     *
     * @param plugin プラグインインスタンス
     * @param playerSettingService プレイヤー設定サービス
     * @param itemStackPacketAdapter 装備表示を再同期するパケットアダプタ
     */
    public PlayerSettingJoinEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull PlayerSettingService playerSettingService,
        @NotNull ItemStackPacketAdapter itemStackPacketAdapter
    ) {
        this.plugin = plugin;
        this.playerSettingService = playerSettingService;
        this.itemStackPacketAdapter = itemStackPacketAdapter;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID userId = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        long sessionToken = playerSettingService.beginSession(userId);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> runSafely(() -> {
            playerSettingService.warmup(userId, sessionToken);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                var player = plugin.getServer().getPlayer(userId);
                if (player != null
                    && player.isOnline()
                    && playerSettingService.captureSessionToken(userId) == sessionToken) {
                    itemStackPacketAdapter.refreshEquipmentView(player);
                }
            });
        }, LogId.E_5314, playerName));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        runSafely(() -> playerSettingService.clear(event.getPlayer().getUniqueId()), LogId.E_5314, event.getPlayer().getName());
    }
}
