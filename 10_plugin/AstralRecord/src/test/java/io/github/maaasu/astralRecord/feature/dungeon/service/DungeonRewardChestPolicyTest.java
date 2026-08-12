package io.github.maaasu.astralRecord.feature.dungeon.service;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonRewardChestPolicyTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 現在参加中でeligible keyを持つプレイヤーは報酬一覧が空でも同一WorldのCHESTを操作できる。
     */
    @Test
    void allowsEligibleCurrentParticipantWithEmptyRewardList() {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();

        assertTrue(DungeonRewardChestPolicy.canAccess(
                true, false, Set.of(playerId), Map.of(playerId, List.of()),
                playerId, worldId, worldId, Material.CHEST
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: eligible keyがあっても現在参加者でなければ報酬CHESTを操作できない。
     */
    @Test
    void rejectsPlayerWhoIsNotCurrentParticipant() {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();

        assertFalse(DungeonRewardChestPolicy.canAccess(
                true, false, Set.of(), Map.of(playerId, List.of()),
                playerId, worldId, worldId, Material.CHEST
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 現在参加者でもeligible keyがなければ報酬CHESTを操作できない。
     */
    @Test
    void rejectsParticipantWithoutEligibleKey() {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();

        assertFalse(DungeonRewardChestPolicy.canAccess(
                true, false, Set.of(playerId), Map.of(),
                playerId, worldId, worldId, Material.CHEST
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: ボス部屋クリア前は現在参加者かつeligibleでも報酬CHESTを操作できない。
     */
    @Test
    void rejectsUnclearedSession() {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();

        assertFalse(DungeonRewardChestPolicy.canAccess(
                false, false, Set.of(playerId), Map.of(playerId, List.of()),
                playerId, worldId, worldId, Material.CHEST
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: セッション終了処理中は現在参加者かつeligibleでも報酬CHESTを操作できない。
     */
    @Test
    void rejectsEndingSession() {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();

        assertFalse(DungeonRewardChestPolicy.canAccess(
                true, true, Set.of(playerId), Map.of(playerId, List.of()),
                playerId, worldId, worldId, Material.CHEST
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 報酬CHESTとプレイヤーのWorldが異なる場合は操作できない。
     */
    @Test
    void rejectsPlayerInDifferentWorld() {
        UUID playerId = UUID.randomUUID();

        assertFalse(DungeonRewardChestPolicy.canAccess(
                true, false, Set.of(playerId), Map.of(playerId, List.of()),
                playerId, UUID.randomUUID(), UUID.randomUUID(), Material.CHEST
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 報酬座標のMaterialがCHESTでなくなった場合は操作できない。
     */
    @Test
    void rejectsMissingChestMaterial() {
        UUID playerId = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();

        assertFalse(DungeonRewardChestPolicy.canAccess(
                true, false, Set.of(playerId), Map.of(playerId, List.of()),
                playerId, worldId, worldId, Material.AIR
        ));
    }
}
