package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatusBuffExpiryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### 有効バフ一覧取得
     * 検証契約: GUIによる有効バフ取得が最初の失効検知でも、一覧からの除去とステータス再計算を行う。
     */
    @Test
    void readingBuffListRefreshesStatusWhenItRemovesExpiredBuff() {
        AstPlayer player = mock(AstPlayer.class);
        Player bukkit = mock(Player.class);
        when(bukkit.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getBukkit()).thenReturn(bukkit);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(StatusType.MP_REGEN, 9.0D), 10.0D, 5.0D, 0.0D);
        when(player.getStatusSnapshot()).thenReturn(snapshot);
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        BuffType type = new BuffType("expired", "BUFF", "期限付き効果", 20, false, false, null, List.of());
        when(player.getActiveBuffs()).thenReturn(new ArrayList<>(List.of(new ActiveBuff(type, past, past.plusSeconds(1)))));
        StatusService service = spy(new StatusService());
        doReturn(snapshot).when(service).refreshStatus(player);
        assertTrue(service.getActiveBuffs(player).isEmpty());
        verify(service).refreshStatus(player);
    }
}
