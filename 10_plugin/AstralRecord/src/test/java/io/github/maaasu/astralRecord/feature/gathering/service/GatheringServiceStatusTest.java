package io.github.maaasu.astralRecord.feature.gathering.service;

import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatheringServiceStatusTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7. GatheringService メソッド仕様 > ### 採集開始・継続
     * 検証契約: MINING_SPEED未設定/0でも最低1 damageを与える。
     */
    @Test
    void missingMiningSpeedUsesMinimumDamage() {
        assertEquals(1, GatheringService.resolveMiningDamage((StatusValue) null));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7. GatheringService メソッド仕様 > ### 採集開始・継続
     * 検証契約: 固定MINING_SPEEDを四捨五入して採集damageへ使う。
     */
    @Test
    void fixedMiningSpeedBecomesGatheringDamage() {
        assertEquals(10, GatheringService.resolveMiningDamage(new StatusValue(10.0D, 0.0D)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 7. GatheringService メソッド仕様 > ### 採集開始・継続
     * 検証契約: tool由来MINING_SPEEDを重複加算せず1回分damageとして使う。
     */
    @Test
    void toolMiningSpeedIsUsedAsSingleGatheringDamage() {
        assertEquals(2, GatheringService.resolveMiningDamage(new StatusValue(0.0D, 2.0D)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 26. 採集定義
     * 検証契約: range MINING_SPEEDのroll値を上下限内の採集damageにする。
     */
    @Test
    void rangedMiningSpeedRollsDamageWithinBounds() {
        StatusValue value = new StatusValue(0.0D, 0.0D, 2.0D, 10.0D);

        for (int i = 0; i < 1_000; i++) {
            int damage = GatheringService.resolveMiningDamage(value);
            assertTrue(damage >= 2 && damage <= 10);
        }
    }
}
