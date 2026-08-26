package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleUseServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 6. 使用待機 > ### bundle開封開始・取消
     * 検証契約: bundle の開封待機はマスタ指定値を使用し、0以下の値は既定20 tickへ補完する。
     */
    @Test
    void openTimeTicksUsesMasterValueAndTwentyTickFallback() {
        assertEquals(10L, BundleUseService.resolveOpenTimeTicks(bundle(10L)));
        assertEquals(20L, BundleUseService.resolveOpenTimeTicks(bundle(0L)));
    }

    private ItemBundle bundle(long openTimeTicks) {
        return new ItemBundle(null, List.of(), 0L, openTimeTicks, null);
    }
}
