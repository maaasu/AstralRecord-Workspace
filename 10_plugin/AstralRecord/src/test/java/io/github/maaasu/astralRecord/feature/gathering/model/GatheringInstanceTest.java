package io.github.maaasu.astralRecord.feature.gathering.model;

import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition.GatheringSoundConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatheringInstanceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 27. 採集インスタンス
     * 検証契約: 同一採集オブジェクトへ複数プレイヤーが同時参加し、それぞれのdamageを累積する。
     */
    @Test
    void multiplePlayersContributeDamageToSameInstance() {
        GatheringInstance instance = createInstance();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        instance.addActivePlayer(first);
        instance.addActivePlayer(second);
        instance.damage(first, "tool-first", 2);
        instance.damage(second, "tool-second", 3);

        assertEquals(5, instance.currentHealth());
        assertEquals(2, instance.activePlayerIds().size());
        assertEquals(2, instance.contributorPlayerIds().size());
        assertEquals("tool-first", instance.contributorEquipmentInstanceId(first));
        assertEquals("tool-second", instance.contributorEquipmentInstanceId(second));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 27. 採集インスタンス
     * 検証契約: 1人の採集終了は他参加者と蓄積HPを変更せず、全体resetだけが初期化する。
     */
    @Test
    void removingOnePlayerKeepsSharedProgressUntilReset() {
        GatheringInstance instance = createInstance();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        instance.addActivePlayer(first);
        instance.addActivePlayer(second);
        instance.damage(first, "tool-first", 4);
        instance.removeActivePlayer(first);

        assertFalse(instance.hasActivePlayer(first));
        assertTrue(instance.hasActivePlayer(second));
        assertEquals(6, instance.currentHealth());

        instance.resetHealth();

        assertEquals(10, instance.currentHealth());
        assertTrue(instance.activePlayerIds().isEmpty());
        assertTrue(instance.contributorPlayerIds().isEmpty());
        assertNull(instance.contributorEquipmentInstanceId(first));
    }

    /** @return 複数参加者の状態遷移検証に使うHP10の採集インスタンス */
    private GatheringInstance createInstance() {
        GatheringDefinition definition = new GatheringDefinition(
            1,
            "test_gathering",
            "MINING",
            "test",
            1,
            10,
            Material.STONE,
            new Vector3f(1.0F, 1.0F, 1.0F),
            List.of("PICKAXE"),
            new MobDropConfig(0, null, List.of(), null),
            GatheringSoundConfig.empty()
        );
        return new GatheringInstance(UUID.randomUUID(), definition, new Location(null, 0.5D, 64.0D, 0.5D), null);
    }
}
