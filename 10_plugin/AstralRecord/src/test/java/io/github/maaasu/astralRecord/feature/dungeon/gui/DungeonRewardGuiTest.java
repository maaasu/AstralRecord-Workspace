package io.github.maaasu.astralRecord.feature.dungeon.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DungeonRewardGuiTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: GUI holderは描画時のslotとclaim ID対応をimmutableに固定し、範囲外slotを報酬へ解決しない。
     */
    @Test
    void holderPinsVisibleClaimIdsAtRenderTime() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<UUID> source = new ArrayList<>(List.of(first, second));
        DungeonRewardGui.Holder holder = new DungeonRewardGui.Holder(
                UUID.randomUUID(), UUID.randomUUID(), 0, source);

        source.removeFirst();

        assertEquals(first, holder.claimIdAt(0));
        assertEquals(second, holder.claimIdAt(1));
        assertNull(holder.claimIdAt(-1));
        assertNull(holder.claimIdAt(DungeonRewardGui.CONTENT_SIZE));
    }
}
