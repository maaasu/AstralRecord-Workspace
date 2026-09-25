package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifierType;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.buff.service.BuffService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusFinalBuffScalarTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/3-メソッド仕様/05_3-サービス.md
     * 章・見出し: # 05_3-サービス > ## 1. BuffService メソッド仕様 > ### 最終倍率取得
     * 検証契約: 正の倍率バフと派生防御値があっても FINAL_SCALAR -1.0 は最終防御値を0にする。
     */
    @Test
    void finalReductionRemovesOtherBuffAndDerivedDefense() {
        LocalDateTime now = LocalDateTime.now();
        AstPlayer player = mock(AstPlayer.class);
        when(player.getActiveBuffs()).thenReturn(new ArrayList<>(List.of(
                activeBuff("defense-up", BuffModifierType.SCALAR, 0.5D, now),
                activeBuff("confusion", BuffModifierType.FINAL_SCALAR, -1.0D, now)
        )));
        BuffService buffs = new BuffService();
        assertEquals(50.0D, buffs.getTotalBonus(player, StatusType.DEFENSE, 100.0D));

        Map<StatusType, StatusValue> values = new EnumMap<>(StatusType.class);
        values.put(StatusType.DEFENSE, new StatusValue(100.0D, 100.0D, 65.0D, 65.0D));
        StatusService.applyFinalBuffScalars(values, buffs.getFinalScalarFactors(player));
        assertEquals(0.0D, values.get(StatusType.DEFENSE).getMaxValue());
    }

    /**
     * 検証する補正を持つ有効なバフを作成します。
     * @param id バフ ID
     * @param modifierType 補正方式
     * @param value 補正値
     * @param now テストの基準時刻
     * @return 基準時刻から1分間有効なバフ
     */
    private static ActiveBuff activeBuff(
            String id, BuffModifierType modifierType, double value, LocalDateTime now
    ) {
        BuffType type = new BuffType(id, "BUFF", id, 200, true, false, null,
                List.of(new BuffModifier(StatusType.DEFENSE, modifierType, value)));
        return new ActiveBuff(type, now, now.plusMinutes(1));
    }
}
