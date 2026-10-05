package io.github.maaasu.astralRecord.feature.buff.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifierType;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.buff.repository.BuffRepository;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuffServiceRuntimeTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/3-メソッド仕様/05_3-サービス.md
     * 章・見出し: # 05_3-サービス > ## 1. BuffService メソッド仕様 > ### バフ付与
     * 検証契約: 実行時バフは同じstackGroupの旧個体を置換し、持続時間増加を適用してマスタ取得を行わない。
     */
    @Test
    void runtimeBuffReplacesGroupAndScalesItsOwnDeadlineWithoutLoadingMaster() {
        AstPlayer player = mock(AstPlayer.class);
        List<ActiveBuff> buffs = new ArrayList<>();
        when(player.getActiveBuffs()).thenReturn(buffs);
        BuffRepository repository = mock(BuffRepository.class);
        BuffService service = new BuffService(repository);
        service.apply(player, type("old", false), 0.0D);
        ActiveBuff applied = service.apply(player, type("new", false), 50.0D);
        assertEquals(1, buffs.size());
        assertSame(applied, buffs.getFirst());
        assertEquals(1500L, Duration.between(applied.getStartedAt(), applied.getExpiresAt()).toMillis());
        verifyNoInteractions(repository);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/3-メソッド仕様/05_3-サービス.md
     * 章・見出し: # 05_3-サービス > ## 1. BuffService メソッド仕様 > ### バフ付与
     * 検証契約: 実行時デバフは持続時間増加を受けず、元のtick数を期限へ反映する。
     */
    @Test
    void runtimeDebuffDoesNotReceiveDurationIncrease() {
        AstPlayer player = mock(AstPlayer.class);
        when(player.getActiveBuffs()).thenReturn(new ArrayList<ActiveBuff>());
        ActiveBuff applied = new BuffService(mock(BuffRepository.class)).apply(player, type("debuff", true), 100.0D);
        assertEquals(1000L, Duration.between(applied.getStartedAt(), applied.getExpiresAt()).toMillis());
    }

    private static BuffType type(String id, boolean debuff) {
        return new BuffType(id, "BUFF", "実行時効果", 20, debuff, true, "group",
                List.of(new BuffModifier(StatusType.MP_REGEN, BuffModifierType.FLAT, 7.0D)));
    }
}
