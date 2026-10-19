package io.github.maaasu.astralRecord.feature.player.death;

import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerDeathServiceHookTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 4. 死亡サービス > ### 追加死亡処理・即時復帰通知の設定
     * 検証契約: 追加死亡処理が受理した場合、通常死亡の状態・通知・能力変更を開始しない。
     */
    @Test
    void acceptedAdditionalDeathHandlerSkipsDefaultDeathState() {
        AccountService accounts = mock(AccountService.class);
        StatusService statuses = mock(StatusService.class);
        MobService mobs = mock(MobService.class);
        PlayerDeathService service = new PlayerDeathService(
                mock(Plugin.class), accounts, statuses, mobs, mock(WorldService.class),
                mock(DisplayTextService.class), "join"
        );
        AstPlayer astPlayer = mock(AstPlayer.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location location = mock(Location.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(playerId);
        AtomicInteger started = new AtomicInteger();
        service.setDeathStartedListener(ignored -> started.incrementAndGet());
        service.setAdditionalDeathHandler((actualPlayer, actualLocation) -> {
            assertSame(astPlayer, actualPlayer);
            assertSame(location, actualLocation);
            return true;
        });

        assertTrue(service.startDeath(astPlayer, location));
        assertFalse(service.isDead(playerId));
        assertEquals(0, started.get());
        verifyNoInteractions(accounts, statuses, mobs);
    }
}
