package io.github.maaasu.astralRecord.feature.hud.service;

import io.github.maaasu.astralRecord.feature.hud.event.AdminMessageBossBarEventHandler;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminMessageBossBarServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-サービス.md
     * 章・見出し: # 10_3-サービス > ## 10. 管理者メッセージ BossBar 更新
     * 検証契約: 残りtick数を総tick数で割った進捗率を0.0以上1.0以下へ制限する。
     */
    @Test
    void calculatesClampedProgressFromRemainingTicks() {
        assertEquals(1.0D, AdminMessageBossBarService.calculateProgress(20L, 20L), 0.0001D);
        assertEquals(0.5D, AdminMessageBossBarService.calculateProgress(10L, 20L), 0.0001D);
        assertEquals(0.0D, AdminMessageBossBarService.calculateProgress(-1L, 20L), 0.0001D);
        assertEquals(1.0D, AdminMessageBossBarService.calculateProgress(40L, 20L), 0.0001D);
        assertEquals(0.0D, AdminMessageBossBarService.calculateProgress(10L, 0L), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-サービス.md
     * 章・見出し: # 10_3-サービス > ## 9. 管理者メッセージ BossBar 表示
     * 検証契約: 表示開始時に全online playerを同一BossBarへ追加し、入力メッセージのlegacy color codeを変換する。
     */
    @Test
    void showsColoredMessageToAllOnlinePlayers() {
        Plugin plugin = mockPlugin();
        Player first = server().addPlayer("first");
        Player second = server().addPlayer("second");
        AdminMessageBossBarService service = new AdminMessageBossBarService(plugin);

        service.show("&aServer notice", 2L);

        assertNotNull(service.activeBossBar());
        BossBar bossBar = service.activeBossBar();
        assertEquals("§aServer notice", bossBar.getTitle());
        assertTrue(bossBar.getPlayers().contains(first));
        assertTrue(bossBar.getPlayers().contains(second));
        assertEquals(1.0D, bossBar.getProgress(), 0.0001D);
        service.stop();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-イベント.md
     * 章・見出し: # 10_3-イベント > ## 1. 管理者メッセージ表示中の参加
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-イベント.md
     * 章・見出し: # 10_3-イベント > ## 2. 管理者メッセージ表示中の退出
     * 検証契約: 表示中の参加者をBossBarへ追加し、退出者をBossBarから削除する。
     */
    @Test
    void updatesViewersWhenPlayersJoinAndQuitDuringDisplay() {
        Plugin plugin = mockPlugin();
        Player existing = server().addPlayer("existing");
        Player joining = server().addPlayer("joining");
        AdminMessageBossBarService service = new AdminMessageBossBarService(plugin);
        service.show("notice", 10L);
        assertNotNull(service.activeBossBar());
        BossBar bossBar = service.activeBossBar();
        AdminMessageBossBarEventHandler handler = new AdminMessageBossBarEventHandler(service);

        handler.onPlayerJoin(new PlayerJoinEvent(joining, Component.empty()));
        assertTrue(bossBar.getPlayers().contains(joining));

        handler.onPlayerQuit(new PlayerQuitEvent(
                existing,
                Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED
        ));
        assertFalse(bossBar.getPlayers().contains(existing));
        service.stop();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-サービス.md
     * 章・見出し: # 10_3-サービス > ## 10. 管理者メッセージ BossBar 更新
     * 検証契約: 20tickの表示時間が経過したtickでBossBarを非表示化し、表示対象を空にする。
     */
    @Test
    void removesBossBarAfterSpecifiedSeconds() {
        Plugin plugin = mockPlugin();
        Player player = server().addPlayer("timed");
        AdminMessageBossBarService service = new AdminMessageBossBarService(plugin);
        service.show("notice", 1L);
        assertNotNull(service.activeBossBar());
        BossBar bossBar = service.activeBossBar();

        server().getScheduler().performTicks(19L);
        assertTrue(service.isActive());
        assertEquals(0.05D, bossBar.getProgress(), 0.0001D);

        server().getScheduler().performOneTick();
        assertFalse(service.isActive());
        assertFalse(bossBar.isVisible());
        assertTrue(bossBar.getPlayers().isEmpty());
        assertTrue(player.isOnline());
    }

    private Plugin mockPlugin() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(server());
        return plugin;
    }
}
