package io.github.maaasu.astralRecord.feature.status.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusSnapshotTemporaryShieldTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-モデル操作.md
     * 章・見出し: # 07_3-モデル操作 > ## 1. StatusSnapshot メソッド仕様 > ### 現在リソース更新
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-モデル操作.md
     * 章・見出し: # 07_3-モデル操作 > ## 1. StatusSnapshot メソッド仕様 > ### 現在Shield更新
     * 検証契約: 最大Shieldが0でも一時ShieldはHP/MP/ENG更新で維持し、通常Shield更新では上限を適用する。
     */
    @Test
    void preservesTemporaryShieldAcrossResourceUpdatesWithoutChangingNormalShieldCap() {
        StatusSnapshot snapshot = new StatusSnapshot(
            Map.of(
                StatusType.MAX_HEALTH, new StatusValue(100.0D, 0.0D),
                StatusType.MAX_MANA, new StatusValue(100.0D, 0.0D),
                StatusType.MAX_ENERGY, new StatusValue(100.0D, 0.0D),
                StatusType.MAX_SHIELD, new StatusValue(0.0D, 0.0D)
            ),
            80.0D, 80.0D, 80.0D, 0.0D, 0L, LocalDateTime.MIN
        ).withTemporaryShield(40.0D);

        StatusSnapshot updated = snapshot.withCurrentValues(70.0D, 60.0D, 50.0D);

        assertEquals(40.0D, updated.getCurrentShield());
        assertEquals(70.0D, updated.getCurrentHp());
        assertEquals(60.0D, updated.getCurrentMp());
        assertEquals(50.0D, updated.getCurrentEnergy());
        assertEquals(0.0D, updated.withCurrentShield(40.0D).getCurrentShield());
    }
}
