package io.github.maaasu.astralRecord.feature.worldplacement.repository;

import org.junit.jupiter.api.Test;
import org.bukkit.Material;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldPlacementRepositoryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/43-world-placement/43_3-配置スナップショット.md
     * 章・見出し: # 配置スナップショット > ## API 応答の検証
     * 検証契約: スラッシュを含む物理ワールド名と既存ウェイストーンIDを保持し、無効行は除外し、未知アイコンと空の作成者は既定表示へ戻す。
     */
    @Test
    void keepsPhysicalWorldAndWaystoneIdentityButOmitsDisabledPlacement() {
        String json = """
            {"schemaVersion":1,"revision":"42","placements":[
              {"placementId":"s1","kind":"spawner","worldId":"base","worldName":"plugins/AstralRecord/worlds/base/starlit_nox","templateId":"wolf","x":1,"y":64,"z":2,"yaw":0,"pitch":0,"enabled":true,"waystone":null},
              {"placementId":"n1","kind":"npc","worldId":"base","worldName":"plugins/AstralRecord/worlds/base/starlit_nox","templateId":"guide","x":1.5,"y":65,"z":2.5,"yaw":90,"pitch":0,"enabled":false,"waystone":null},
              {"placementId":"w1","kind":"waystone","worldId":"base","worldName":"plugins/AstralRecord/worlds/base/starlit_nox","templateId":null,"x":3,"y":64,"z":4,"yaw":0,"pitch":0,"enabled":true,
                "waystone":{"id":"ws-20260801163308-waystone","name":"拠点","lockEnabled":false,"unlockGold":0,"icon":"UNKNOWN_ICON","createdAt":"2026-08-01T16:33:08.928898200Z","createdBy":""}}
            ]}
            """;

        WorldPlacementRepository.Snapshot snapshot = WorldPlacementRepository.parseSnapshot(json);

        assertEquals("42", snapshot.revision());
        assertEquals("plugins/AstralRecord/worlds/base/starlit_nox", snapshot.spawners().getFirst().worldName());
        assertEquals(0, snapshot.npcs().size());
        assertEquals("ws-20260801163308-waystone", snapshot.waystones().getFirst().id());
        assertEquals(Instant.parse("2026-08-01T16:33:08.928898200Z"), snapshot.waystones().getFirst().createdAt());
        assertEquals("system", snapshot.waystones().getFirst().createdBy());
        assertEquals(Material.BEACON, snapshot.waystones().getFirst().displayIcon());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/43-world-placement/43_3-配置スナップショット.md
     * 章・見出し: # 配置スナップショット > ## API 応答の検証
     * 検証契約: 不正行が一件でもあれば一括スナップショットを拒否し、部分的な公開値を返さない。
     */
    @Test
    void rejectsEntireSnapshotForInvalidDisabledRow() {
        String json = """
            {"schemaVersion":1,"revision":"1","placements":[
              {"placementId":"s1","kind":"spawner","worldId":"base","worldName":"world","templateId":"wolf","x":1,"y":64,"z":2,"yaw":0,"pitch":0,"enabled":true,"waystone":null},
              {"placementId":"x1","kind":"unknown","worldId":"base","worldName":"world","templateId":null,"x":0,"y":0,"z":0,"yaw":0,"pitch":0,"enabled":false,"waystone":null}
            ]}
            """;

        assertThrows(IllegalStateException.class, () -> WorldPlacementRepository.parseSnapshot(json));
    }
}
