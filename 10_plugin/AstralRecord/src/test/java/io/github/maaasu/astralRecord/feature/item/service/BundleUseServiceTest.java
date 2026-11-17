package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleUseServiceTest {

    /**
     * bundle の開封待機はマスタ指定値を使用し、0以下の値は既定20 tickへ補完する。
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
