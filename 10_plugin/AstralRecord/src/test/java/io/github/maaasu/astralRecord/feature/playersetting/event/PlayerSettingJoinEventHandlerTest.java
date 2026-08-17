package io.github.maaasu.astralRecord.feature.playersetting.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.view.ItemStackPacketAdapter;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerSettingJoinEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/11_4-統合フロー.md
     * 章・見出し: # 11_4-統合フロー > ## 1. login warmup・logout cleanup
     * 検証契約: 設定 warmup 完了後、同一 session の online player へ装備表示をメインスレッドで再同期する。
     */
    @Test
    void settingWarmupRefreshesEquipmentAfterCompletion() {
        UUID userId = UUID.randomUUID();
        long sessionToken = 7L;
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        ItemStackPacketAdapter itemStackPacketAdapter = mock(ItemStackPacketAdapter.class);
        Player player = mock(Player.class);
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        AtomicReference<Runnable> asyncTask = new AtomicReference<>();
        AtomicReference<Runnable> syncTask = new AtomicReference<>();

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(userId);
        when(player.getName()).thenReturn("player");
        when(player.isOnline()).thenReturn(true);
        when(playerSettingService.beginSession(userId)).thenReturn(sessionToken);
        when(server.getPlayer(userId)).thenReturn(player);
        when(playerSettingService.captureSessionToken(userId)).thenReturn(sessionToken);
        doAnswer(invocation -> {
            asyncTask.set(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            syncTask.set(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));

        PlayerSettingJoinEventHandler handler = new PlayerSettingJoinEventHandler(
            plugin,
            playerSettingService,
            itemStackPacketAdapter
        );

        handler.onPlayerJoin(event);

        verify(itemStackPacketAdapter, never()).refreshEquipmentView(player);
        asyncTask.get().run();
        verify(playerSettingService).warmup(userId, sessionToken);
        verify(itemStackPacketAdapter, never()).refreshEquipmentView(player);

        syncTask.get().run();

        verify(itemStackPacketAdapter).refreshEquipmentView(player);
    }
}
